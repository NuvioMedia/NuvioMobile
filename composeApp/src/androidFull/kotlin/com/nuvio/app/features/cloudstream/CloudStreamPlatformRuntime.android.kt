package com.nuvio.app.features.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import co.touchlab.kermit.Logger
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = true

    private val log = Logger.withTag("CloudStreamDex")
    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()
    private val loadedLock = Any()
    private val loaded = linkedMapOf<String, LoadedPlugin>()
    private var appContext: Context? = null
    private var activityReference: WeakReference<Activity>? = null

    actual fun initialize(context: Any?) {
        val androidContext = context as? Context ?: return
        appContext = androidContext.applicationContext
        activityReference = (androidContext as? Activity)?.let(::WeakReference)
        activityReference?.get()?.let(CommonActivity::setActivityInstance)
    }

    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? {
        if (plugin.compatibility.runtimeKind != CloudStreamRuntimeKind.AndroidDex) return null
        return withContext(Dispatchers.IO) {
            loadMutex.withLock {
                synchronized(loadedLock) {
                    loaded[plugin.metadata.id.value]?.provider
                        ?: loadPlugin(plugin).also { loaded[plugin.metadata.id.value] = it }.provider
                }
            }
        }
    }

    actual suspend fun hasSettings(plugin: CloudStreamPluginItem): Boolean {
        if (plugin.compatibility.runtimeKind != CloudStreamRuntimeKind.AndroidDex || !plugin.isInstalled) {
            return false
        }
        return try {
            provider(plugin)
            val instance = synchronized(loadedLock) { loaded[plugin.metadata.id.value]?.instance }
            (instance as? Plugin)?.openSettings != null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.w(error) { "Could not inspect CloudStream settings capability id=${plugin.metadata.id.value}" }
            false
        }
    }

    actual suspend fun openSettings(plugin: CloudStreamPluginItem): Boolean {
        if (!hasSettings(plugin)) return false
        val cloudStreamPlugin = synchronized(loadedLock) { loaded[plugin.metadata.id.value]?.instance as? Plugin }
            ?: return false
        val callback = cloudStreamPlugin.openSettings ?: return false
        val context = activityReference?.get() ?: appContext ?: return false
        return try {
            callback.invoke(context)
            true
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.e(error) { "Failed to open CloudStream settings id=${plugin.metadata.id.value}" }
            false
        }
    }

    actual fun unload(pluginId: String) {
        synchronized(loadedLock) { loaded.remove(pluginId) }?.unload()
        val remainingContext = synchronized(loadedLock) { loaded.values.lastOrNull()?.context }
        if (remainingContext != null) {
            CloudStreamApp.context = remainingContext
            setContext(WeakReference(remainingContext))
        } else {
            appContext?.let(::prepareHostContext)
        }
    }

    actual fun clear() {
        val plugins = synchronized(loadedLock) {
            loaded.values.toList().also {
                loaded.clear()
            }
        }
        plugins.forEach(LoadedPlugin::unload)
        PluginManager.clear()
        appContext?.let(::prepareHostContext)
    }

    private fun loadPlugin(item: CloudStreamPluginItem): LoadedPlugin {
        val context = requireNotNull(appContext) { "CloudStream Android runtime is not initialized" }
        prepareHostContext(context)
        val filePath = requireNotNull(CloudStreamPlatformStorage.packagePath(item.metadata.id.storageKey)) {
            "Installed CloudStream package is missing"
        }
        val file = File(filePath)
        require(file.isFile) { "Installed CloudStream package is missing" }
        item.metadata.fileSize?.let { expectedSize ->
            if (file.length() != expectedSize) {
                log.w {
                    "Installed CloudStream package metadata size mismatch id=${item.metadata.id.value} " +
                        "expected=$expectedSize actual=${file.length()}"
                }
            }
        }
        val packageBytes = file.readBytes()
        // Recheck the archive on every load and validate against the checksum that
        // belongs to this installed version. Repository metadata may already describe
        // a newer version whose checksum must not be applied to the older installed file.
        CloudStreamPackageInspector.inspect(packageBytes)
        require(matchesInstalledCloudStreamPackage(item.installedFileHash, packageBytes)) {
            "Installed CloudStream package SHA-256 no longer matches its installed checksum"
        }
        require(file.setReadOnly() || !file.canWrite()) { "CloudStream package could not be made read-only" }

        val manifest = ZipFile(file).use { archive ->
            val entry = archive.getEntry("manifest.json")
                ?: error("CloudStream package has no manifest.json")
            archive.getInputStream(entry).bufferedReader().use { reader ->
                json.decodeFromString<CloudStreamCs3Manifest>(reader.readText())
            }
        }
        val pluginClassName = manifest.pluginClassName?.takeIf(String::isNotBlank)
            ?: error("CloudStream manifest has no pluginClassName")
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val providersBefore = APIHolder.allProviders.toSet()
        val extractorsBefore = extractorApis.toSet()
        var instance: BasePlugin? = null
        PluginManager.currentlyLoading = item.metadata.internalName
        try {
            val pluginClass = loader.loadClass(pluginClassName)
            require(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
                "CloudStream entry point does not extend BasePlugin"
            }

            @Suppress("UNCHECKED_CAST")
            val pluginInstance = (pluginClass as Class<out BasePlugin>).getDeclaredConstructor().newInstance()
            instance = pluginInstance
            pluginInstance.filename = file.absolutePath
            val identityContext = CloudStreamIdentityContext(context)
            CloudStreamApp.context = identityContext
            setContext(WeakReference(identityContext))
            applyHostCompatibilityDefaults(pluginClassName)
            if (manifest.requiresResources) pluginInstance.attachResources(identityContext, file)

            if (pluginInstance is Plugin) pluginInstance.load(identityContext) else pluginInstance.load()
            val providers = APIHolder.allProviders
                .filter { it !in providersBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
            require(providers.isNotEmpty()) {
                "Plugin loaded but registered no providers. It may reject the host runtime."
            }
            providers.forEach(MainAPI::init)
            val registeredExtractors = extractorApis
                .filter { it !in extractorsBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
            val data = PluginData(
                internalName = item.metadata.internalName,
                url = item.metadata.packageUrl,
                isOnline = true,
                filePath = file.absolutePath,
                version = manifest.version ?: item.metadata.version,
            )
            PluginManager.register(data, pluginInstance)
            log.i {
                "Loaded ${item.metadata.internalName}: ${providers.size} provider(s), " +
                    "${registeredExtractors.size} extractor(s)"
            }
            return LoadedPlugin(
                id = item.metadata.id.value,
                path = file.absolutePath,
                context = identityContext,
                instance = pluginInstance,
                providers = providers,
                extractors = registeredExtractors,
                provider = AndroidDexCloudStreamProvider(item.metadata.id.value, providers),
            )
        } catch (error: Throwable) {
            log.e(error) { "Failed to load ${item.metadata.internalName}" }
            runCatching { instance?.beforeUnload() }
            val addedProviders = APIHolder.allProviders.filter {
                it !in providersBefore && it.sourcePlugin == file.absolutePath
            }
            addedProviders.forEach(APIHolder::removePluginMapping)
            APIHolder.allProviders.removeAll { it !in providersBefore && it.sourcePlugin == file.absolutePath }
            extractorApis.removeAll { it !in extractorsBefore && it.sourcePlugin == file.absolutePath }
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == file.absolutePath }
            PluginManager.unregister(file.absolutePath)
            prepareHostContext(context)
            throw error
        } finally {
            PluginManager.currentlyLoading = null
        }
    }

    private fun prepareHostContext(context: Context) {
        activityReference?.get()?.let(CommonActivity::setActivityInstance)
        CloudStreamApp.context = context
        setContext(WeakReference(context))
    }

    /**
     * CineStream's optional Simkl catalogue eagerly constructs CloudStream account-sync APIs.
     * Those APIs are application services rather than extension APIs and are intentionally not
     * embedded in Nuvio. The catalogue does not participate in CineStream's source extraction;
     * disabling it lets the primary CineStream/TMDB catalogues and every built-in link provider
     * load exactly as configured by the extension.
     */
    private fun applyHostCompatibilityDefaults(pluginClassName: String) {
        if (pluginClassName != CINESTREAM_PLUGIN_CLASS) return
        val preferences = appContext?.getSharedPreferences(
            com.lagradost.cloudstream3.utils.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ) ?: return
        if (preferences.getString(CINESTREAM_SIMKL_PROVIDER_KEY, null) != "false") {
            CloudStreamApp.setKey(CINESTREAM_SIMKL_PROVIDER_KEY, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun BasePlugin.attachResources(context: Context, file: File) {
        val plugin = this as? Plugin ?: return
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        plugin.resources = Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    private const val CINESTREAM_PLUGIN_CLASS = "com.megix.CineStream"
    private const val CINESTREAM_SIMKL_PROVIDER_KEY = "ProviderSimkl"

    private data class LoadedPlugin(
        val id: String,
        val path: String,
        @Suppress("unused") val context: Context,
        val instance: BasePlugin,
        val providers: List<MainAPI>,
        val extractors: List<com.lagradost.cloudstream3.utils.ExtractorApi>,
        val provider: CloudStreamProvider,
    ) {
        fun unload() {
            runCatching { instance.beforeUnload() }
            providers.forEach { api ->
                APIHolder.allProviders.remove(api)
                APIHolder.removePluginMapping(api)
            }
            extractorApis.removeAll(extractors.toSet())
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == path }
            PluginManager.unregister(path)
        }
    }
}

private class CloudStreamIdentityContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = CLOUDSTREAM_PACKAGE_NAME
    override fun getApplicationContext(): Context = this

    companion object {
        private const val CLOUDSTREAM_PACKAGE_NAME = "com.lagradost.cloudstream3"
    }
}

private class AndroidDexCloudStreamProvider(
    override val id: String,
    private val providers: List<MainAPI>,
) : CloudStreamProvider {
    private val log = Logger.withTag("CloudStreamProvider")

    override suspend fun getMainPage(
        page: Int,
    ): List<Pair<String, List<CloudStreamSearchItem>>> = withContext(Dispatchers.IO) {
        val sections = Collections.synchronizedList(
            mutableListOf<Pair<String, List<CloudStreamSearchItem>>>(),
        )
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val providerSemaphore = Semaphore(3)
        providers.filter { api ->
            // Some providers declare categories without setting hasMainPage.
            api.hasMainPage || api.mainPage.any { data ->
                data.name.isNotBlank() || data.data.isNotBlank()
            }
        }.map { api ->
            async {
                providerSemaphore.withPermit {
                    val declaredRequests = api.mainPage.filter { data ->
                        data.name.isNotBlank() || data.data.isNotBlank()
                    }
                    val requests = declaredRequests.ifEmpty {
                        listOf(com.lagradost.cloudstream3.MainPageData(api.name, api.mainUrl, false))
                    }
                    suspend fun loadRequest(data: com.lagradost.cloudstream3.MainPageData) {
                        runCatching {
                            withTimeout(stageTimeout(api.getMainPageTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                                api.getMainPage(
                                    page.coerceAtLeast(1),
                                    MainPageRequest(data.name, data.data, data.horizontalImages),
                                )
                            }
                        }.onSuccess { response ->
                            response?.items.orEmpty().forEach { section ->
                                val title = if (providers.size > 1) "${api.name} · ${section.name}" else section.name
                                val items = section.list.map { it.toNuvioSearchItem(api) }
                                if (items.isNotEmpty()) sections += title to items
                            }
                        }.onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            errors += error
                            log.w(error) { "CloudStream main page failed api=${api.name}" }
                        }
                    }

                    if (api.sequentialMainPage) {
                        requests.forEachIndexed { index, data ->
                            if (index > 0 && api.sequentialMainPageDelay > 0) {
                                delay(api.sequentialMainPageDelay)
                            }
                            loadRequest(data)
                        }
                    } else {
                        coroutineScope {
                            requests.map { data -> async { loadRequest(data) } }.awaitAll()
                        }
                    }
                }
            }
        }.awaitAll()
        if (sections.isEmpty()) throw errors.lastOrNull() ?: return@withContext emptyList()
        synchronized(sections) { sections.toList() }
    }

    override suspend fun search(query: String): List<CloudStreamSearchItem> = withContext(Dispatchers.IO) {
        val providerSemaphore = Semaphore(3)
        val results = providers.map { api ->
            async {
                providerSemaphore.withPermit {
                    runCatching {
                        withTimeout(stageTimeout(api.searchTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                            api.search(query, 1)?.items.orEmpty()
                        }
                    }.onSuccess { items ->
                        log.d {
                            "CloudStream search api=${api.name} query=$query returned=${items.size} " +
                                "sample=${items.take(3).joinToString { it.name }}"
                        }
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        log.w(error) { "CloudStream search failed api=${api.name}" }
                    }.map { items -> items.map { it.toNuvioSearchItem(api) } }
                }
            }
        }.awaitAll()
        val successfulResults = results.mapNotNull { it.getOrNull() }.flatten()
        if (successfulResults.isEmpty()) {
            val lastError = results.asReversed().mapNotNull { it.exceptionOrNull() }.firstOrNull()
            throw lastError ?: return@withContext emptyList()
        }
        successfulResults.distinctBy { it.data }
    }

    override suspend fun loadByExternalId(externalId: String): CloudStreamLoadItem? = withContext(Dispatchers.IO) {
        val syncName = when {
            externalId.matches(IMDB_ID_REGEX) -> SyncIdName.Imdb
            else -> return@withContext null
        }
        providers.firstNotNullOfOrNull { api ->
            if (syncName !in api.supportedSyncNames) return@firstNotNullOfOrNull null
            runCatching {
                val url = withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.getLoadUrl(syncName, externalId)
                }?.takeIf(String::isNotBlank) ?: return@runCatching null
                withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.load(url)
                }?.toNuvioLoadItem(api)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                log.w(error) { "CloudStream external-id load failed api=${api.name} id=$externalId" }
            }.getOrNull()
        }
    }

    override suspend fun load(data: String): CloudStreamLoadItem = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        val response = withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
            api.load(route.data)
        } ?: error("CloudStream provider returned no details")
        response.toNuvioLoadItem(api)
    }

    override suspend fun loadLinks(data: String): List<CloudStreamPlaybackSource> = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        val subtitles = Collections.synchronizedList(mutableListOf<CloudStreamSubtitle>())
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val completed = withTimeout(stageTimeout(api.loadLinksTimeoutMs, DEFAULT_LINK_TIMEOUT_MS)) {
            api.loadLinks(
                route.data,
                false,
                { subtitle ->
                    subtitles += CloudStreamSubtitle(
                        url = subtitle.url,
                        language = subtitle.lang,
                        name = subtitle.lang,
                        headers = subtitle.headers.orEmpty(),
                    )
                },
                links::add,
            )
        }
        val subtitleSnapshot = synchronized(subtitles) { subtitles.toList() }
        val linkSnapshot = synchronized(links) { links.toList() }
        if (!completed && linkSnapshot.isEmpty()) error("CloudStream provider could not resolve this source")
        linkSnapshot.distinctBy { link ->
            listOf(link.url, link.referer, link.quality.toString(), link.type.name, link.headers.toString())
        }.map { link ->
            CloudStreamPlaybackSource(
                name = link.name.ifBlank { link.source },
                url = link.url,
                quality = link.quality.takeIf { it > 0 },
                referer = link.referer.takeIf(String::isNotBlank),
                headers = link.headers,
                subtitles = subtitleSnapshot,
                isHls = link.type == ExtractorLinkType.M3U8,
                isDash = link.type == ExtractorLinkType.DASH,
            )
        }.also { if (it.isEmpty()) error("CloudStream provider returned no playable links") }
    }

    private fun findProvider(className: String): MainAPI =
        providers.firstOrNull { it.javaClass.name == className }
            ?: error("CloudStream provider is no longer registered")

    private fun SearchResponse.toNuvioSearchItem(api: MainAPI): CloudStreamSearchItem =
        CloudStreamSearchItem(
            providerId = this@AndroidDexCloudStreamProvider.id,
            data = encodeAndroidDexRoute(api.javaClass.name, url),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            year = when (this) {
                is AnimeSearchResponse -> year
                is MovieSearchResponse -> year
                is TvSeriesSearchResponse -> year
                is LiveSearchResponse -> null
                else -> null
            },
        )

    private fun LoadResponse.toNuvioLoadItem(api: MainAPI): CloudStreamLoadItem {
        val playbackData = when (this) {
            is MovieLoadResponse -> dataUrl
            is LiveStreamLoadResponse -> dataUrl
            is TorrentLoadResponse -> magnet ?: torrent ?: url
            else -> url
        }
        val episodes = when (this) {
            is TvSeriesLoadResponse -> episodes.map { episode -> episode.toNuvioEpisode(api) }
            is AnimeLoadResponse -> episodes.flatMap { (dubStatus, values) ->
                values.map { episode ->
                    episode.toNuvioEpisode(api, dubStatus.name.takeUnless { episodes.size == 1 })
                }
            }
            else -> emptyList()
        }
        return CloudStreamLoadItem(
            providerId = id,
            data = encodeAndroidDexRoute(api.javaClass.name, playbackData),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            backgroundUrl = backgroundPosterUrl,
            description = plot,
            year = year,
            ratingPercent = score?.toInt(100),
            tags = tags.orEmpty(),
            episodes = episodes,
        )
    }

    private fun com.lagradost.cloudstream3.Episode.toNuvioEpisode(
        api: MainAPI,
        variant: String? = null,
    ): CloudStreamEpisode = CloudStreamEpisode(
        data = encodeAndroidDexRoute(api.javaClass.name, data),
        name = listOfNotNull(name?.takeIf(String::isNotBlank), variant).joinToString(" · ")
            .ifBlank { "Episode ${episode ?: ""}".trim() },
        season = season,
        episode = episode,
        posterUrl = posterUrl,
        description = description,
    )

    private companion object {
        private val IMDB_ID_REGEX = Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)
        private fun stageTimeout(providerHint: Long?, hostMaximum: Long): Long =
            providerHint?.coerceIn(5_000L, hostMaximum) ?: hostMaximum

        private const val DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_LOAD_TIMEOUT_MS = 30_000L
        private const val DEFAULT_LINK_TIMEOUT_MS = 120_000L
    }
}

private data class AndroidDexRoute(val providerClassName: String, val data: String)

private fun encodeAndroidDexRoute(providerClassName: String, data: String): String =
    "nuvio-cs-api:${providerClassName.length}:$providerClassName$data"

private fun decodeAndroidDexRoute(value: String): AndroidDexRoute {
    val payload = value.removePrefix("nuvio-cs-api:")
    require(payload != value) { "Invalid CloudStream provider route" }
    val separator = payload.indexOf(':')
    require(separator > 0) { "Invalid CloudStream provider route" }
    val providerLength = payload.substring(0, separator).toIntOrNull()
        ?: error("Invalid CloudStream provider route")
    val content = payload.substring(separator + 1)
    require(providerLength in 1..content.length) { "Invalid CloudStream provider route" }
    return AndroidDexRoute(
        providerClassName = content.substring(0, providerLength),
        data = content.substring(providerLength),
    )
}

private fun TvType?.toNuvioTvType(): CloudStreamTvType = when (this) {
    TvType.Movie -> CloudStreamTvType.Movie
    TvType.AnimeMovie -> CloudStreamTvType.AnimeMovie
    TvType.TvSeries -> CloudStreamTvType.TvSeries
    TvType.Cartoon -> CloudStreamTvType.Cartoon
    TvType.Anime -> CloudStreamTvType.Anime
    TvType.OVA -> CloudStreamTvType.Ova
    TvType.Torrent -> CloudStreamTvType.Torrent
    TvType.Documentary -> CloudStreamTvType.Documentary
    TvType.AsianDrama -> CloudStreamTvType.AsianDrama
    TvType.Live -> CloudStreamTvType.Live
    TvType.Music, TvType.Audio, TvType.AudioBook, TvType.Podcast -> CloudStreamTvType.Music
    else -> CloudStreamTvType.Other
}
