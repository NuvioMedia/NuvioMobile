package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LiveTvRepository {
    private val mutableUiState = MutableStateFlow(LiveTvUiState())
    val uiState = mutableUiState.asStateFlow()
    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epgMutex = Mutex()
    private var epgJob: Job? = null
    private var favoriteEpgReloadJob: Job? = null
    private var activeEpgSourceUrl: String? = null
    private var activeEpgUrls: List<String> = emptyList()
    private var activeEpgLoadToken: Any? = null
    private var epgLoadingActive = false
    private var featureEnabled = true

    private var initialized = false

    fun ensureLoaded() {
        if (!featureEnabled) return
        if (initialized) return
        initialized = true
        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvStorage.loadSourceType(),
            sourceUrl = LiveTvStorage.loadSourceUrl().orEmpty(),
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = LiveTvStorage.loadFavoriteUrls(),
            recentChannel = LiveTvStorage.loadRecentChannel(),
        )
    }

    fun onProfileChanged() {
        epgJob?.cancel()
        favoriteEpgReloadJob?.cancel()
        activeEpgSourceUrl = null
        activeEpgUrls = emptyList()
        activeEpgLoadToken = null
        initialized = false
        mutableUiState.value = LiveTvUiState()
        if (featureEnabled) ensureLoaded()
    }

    fun setFeatureEnabled(enabled: Boolean) {
        if (featureEnabled == enabled) return
        featureEnabled = enabled
        if (!enabled) {
            epgJob?.cancel()
            epgJob = null
            favoriteEpgReloadJob?.cancel()
            favoriteEpgReloadJob = null
            activeEpgSourceUrl = null
            activeEpgUrls = emptyList()
            activeEpgLoadToken = null
            epgLoadingActive = false
            initialized = false
            mutableUiState.value = LiveTvUiState()
        } else {
            ensureLoaded()
        }
    }

    fun setEpgLoadingActive(active: Boolean) {
        if (!featureEnabled && active) return
        if (epgLoadingActive == active) return
        epgLoadingActive = active
        if (!active) {
            epgJob?.cancel()
            epgJob = null
            activeEpgLoadToken = null
            favoriteEpgReloadJob?.cancel()
            favoriteEpgReloadJob = null
            mutableUiState.value = mutableUiState.value.copy(
                currentProgrammes = emptyMap(),
                programmesByChannel = emptyMap(),
                isEpgLoading = false,
            )
            return
        }

        val state = mutableUiState.value
        if (activeEpgUrls.isNotEmpty() && activeEpgSourceUrl == state.sourceUrl) {
            mutableUiState.value = state.copy(isEpgLoading = true)
                loadEpgInBackground(state.sourceUrl, activeEpgUrls)
        }
    }

    suspend fun load(sourceUrl: String): Result<List<LiveTvChannel>> {
        if (!featureEnabled) return liveTvDisabledResult()
        val normalizedUrl = sourceUrl.trim()
        if (!normalizedUrl.startsWith("http://", ignoreCase = true) && !normalizedUrl.startsWith("https://", ignoreCase = true)) {
            val error = IllegalArgumentException("Enter a valid HTTP or HTTPS M3U URL.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceUrl = normalizedUrl,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            if (normalizedUrl.looksLikeDirectVideoUrl()) {
                val channel = directStreamChannel(normalizedUrl)
                LiveTvStorage.saveSourceUrl(normalizedUrl)
                withContext(Dispatchers.IO) { LiveTvStorage.saveLocalPlaylistData("") }
                LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
                mutableUiState.value = LiveTvUiState(
                    sourceType = LiveTvSourceType.M3u,
                    sourceUrl = normalizedUrl,
                    stalkerSettings = mutableUiState.value.stalkerSettings,
                    xtreamSettings = mutableUiState.value.xtreamSettings,
                    channels = listOf(channel),
                    favoriteUrls = mutableUiState.value.favoriteUrls,
                    recentChannel = mutableUiState.value.recentChannel,
                    isLoaded = true,
                )
                loadEpgInBackground(normalizedUrl, emptyList())
                return@runCatching listOf(channel)
            }

            val playlistData = withContext(Dispatchers.IO) {
                httpGetTextWithHeaders(
                    url = normalizedUrl,
                    headers = M3U_PLAYLIST_REQUEST_HEADERS,
                )
            }
            val playlist = withContext(Dispatchers.Default) {
                if (playlistData.looksLikeHlsManifest()) {
                    ParsedM3uPlaylist(
                        channels = listOf(directStreamChannel(normalizedUrl)),
                        epgUrls = emptyList(),
                    )
                } else {
                    parseM3uPlaylistData(playlistData)
                }
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { "No playable channels were found in this M3U playlist." }
            LiveTvStorage.saveSourceUrl(normalizedUrl)
            withContext(Dispatchers.IO) { LiveTvStorage.saveLocalPlaylistData("") }
            LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = normalizedUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            loadEpgInBackground(normalizedUrl, playlist.epgUrls)
            channels
        }.onFailure { error ->
            
            if (error is CancellationException) {
                mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                throw error
            }
mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "The M3U playlist could not be loaded.",
            )
        }
    }

    suspend fun loadLocalPlaylist(fileName: String, playlistData: String): Result<List<LiveTvChannel>> {
        if (!featureEnabled) return liveTvDisabledResult()
        val trimmedData = playlistData.trim()
        val displayName = fileName.trim().ifBlank { "Local M3U playlist" }
        if (trimmedData.isBlank()) {
            val error = IllegalArgumentException("The selected M3U file is empty.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.M3u,
            sourceUrl = displayName,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val playlist = withContext(Dispatchers.Default) {
                parseM3uPlaylistData(trimmedData)
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { "No playable channels were found in this M3U file." }
            LiveTvStorage.saveSourceUrl(displayName)
            withContext(Dispatchers.IO) { LiveTvStorage.saveLocalPlaylistData(trimmedData) }
            LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = displayName,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            loadEpgInBackground(displayName, playlist.epgUrls)
            channels
        }.onFailure { error ->
            
            if (error is CancellationException) {
                mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                throw error
            }
mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "The M3U file could not be loaded.",
            )
        }
    }

    suspend fun loadStoredLocalPlaylist(): Result<List<LiveTvChannel>> {
        if (!featureEnabled) return liveTvDisabledResult()
        val saved = withContext(Dispatchers.IO) {
            LiveTvStorage.loadSourceUrl().orEmpty() to LiveTvStorage.loadLocalPlaylistData().orEmpty()
        }
        if (saved.second.isBlank()) {
            return Result.failure(IllegalStateException("No saved M3U playlist was found."))
        }
        return loadLocalPlaylist(
            fileName = saved.first.ifBlank { "Local M3U playlist" },
            playlistData = saved.second,
        )
    }

    suspend fun loadStalker(settings: LiveTvStalkerSettings): Result<List<LiveTvChannel>> {
        if (!featureEnabled) return liveTvDisabledResult()
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException("A portal URL and MAC address are required.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.portalUrl.startsWith("http://", ignoreCase = true) && !normalizedSettings.portalUrl.startsWith("https://", ignoreCase = true)) {
            val error = IllegalArgumentException("Enter a valid HTTP or HTTPS Stalker portal URL.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.Stalker,
            sourceUrl = normalizedSettings.portalUrl,
            stalkerSettings = normalizedSettings,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val channels = withContext(Dispatchers.IO) {
                fetchStalkerChannels(normalizedSettings)
            }
            require(channels.isNotEmpty()) { "No playable channels were found in this Stalker portal." }
            withContext(Dispatchers.IO) { LiveTvStorage.saveLocalPlaylistData("") }
            LiveTvStorage.saveSourceType(LiveTvSourceType.Stalker)
            LiveTvStorage.saveStalkerSettings(normalizedSettings)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Stalker,
                sourceUrl = normalizedSettings.portalUrl,
                stalkerSettings = normalizedSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isLoaded = true,
            )
            loadEpgInBackground(normalizedSettings.portalUrl, emptyList())
            channels
        }.onFailure { error ->
            
            if (error is CancellationException) {
                mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                throw error
            }
mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "The Stalker portal could not be loaded.",
            )
        }
    }

    suspend fun loadXtream(settings: LiveTvXtreamSettings): Result<List<LiveTvChannel>> {
        if (!featureEnabled) return liveTvDisabledResult()
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException("Server URL, username, and password are required.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.serverUrl.startsWith("http://", ignoreCase = true) && !normalizedSettings.serverUrl.startsWith("https://", ignoreCase = true)) {
            val error = IllegalArgumentException("Enter a valid HTTP or HTTPS Xtream server URL.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.Xtream,
            sourceUrl = normalizedSettings.serverUrl,
            xtreamSettings = normalizedSettings,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val channels = withContext(Dispatchers.IO) {
                fetchXtreamChannels(normalizedSettings)
            }
            require(channels.isNotEmpty()) { "No playable channels were found for this Xtream provider." }
            withContext(Dispatchers.IO) { LiveTvStorage.saveLocalPlaylistData("") }
            LiveTvStorage.saveSourceType(LiveTvSourceType.Xtream)
            LiveTvStorage.saveXtreamSettings(normalizedSettings)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Xtream,
                sourceUrl = normalizedSettings.serverUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = normalizedSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isLoaded = true,
            )
            loadEpgInBackground(normalizedSettings.serverUrl, emptyList())
            channels
        }.onFailure { error ->
            
            if (error is CancellationException) {
                mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                throw error
            }
mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "Xtream provider could not be loaded.",
            )
        }
    }

    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel =
        if (!featureEnabled) {
            error("Live TV is disabled.")
        } else if (mutableUiState.value.sourceType == LiveTvSourceType.Stalker && !channel.stalkerCommand.isNullOrBlank()) {
            resolveStalkerPlaybackChannel(channel)
        } else {
            channel
        }

    fun disconnect() {
        epgJob?.cancel()
        favoriteEpgReloadJob?.cancel()
        activeEpgSourceUrl = null
        activeEpgUrls = emptyList()
        activeEpgLoadToken = null
        LiveTvStorage.saveSourceUrl("")
        LiveTvStorage.saveLocalPlaylistData("")
        LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
        LiveTvRepositoryStalker.clearSession()
        mutableUiState.value = LiveTvUiState(
            sourceType = LiveTvSourceType.M3u,
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = mutableUiState.value.favoriteUrls,
            recentChannel = mutableUiState.value.recentChannel,
        )
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        if (!featureEnabled) return
        val state = mutableUiState.value
        val favorites = state.favoriteUrls.toMutableSet()
        if (!favorites.add(channel.streamUrl)) {
            favorites.remove(channel.streamUrl)
        }
        LiveTvStorage.saveFavoriteUrls(favorites)
        mutableUiState.value = state.copy(favoriteUrls = favorites)

        if (
            epgLoadingActive &&
            !channel.tvgId.isNullOrBlank() &&
            activeEpgUrls.isNotEmpty() &&
            activeEpgSourceUrl == state.sourceUrl
        ) {
            favoriteEpgReloadJob?.cancel()
            favoriteEpgReloadJob = epgScope.launch {
                delay(600L)
                val latestState = mutableUiState.value
                if (activeEpgUrls.isNotEmpty() && activeEpgSourceUrl == latestState.sourceUrl) {
                    mutableUiState.value = latestState.copy(isEpgLoading = true)
                    loadEpgInBackground(latestState.sourceUrl, activeEpgUrls)
                }
            }
        }
    }

    suspend fun hasStoredLocalPlaylist(): Boolean =
        withContext(Dispatchers.IO) { LiveTvStorage.loadLocalPlaylistData().orEmpty().isNotBlank() }

    fun reportPlaybackError(error: Throwable) {
        if (!featureEnabled) return
        val message = error.message?.takeIf(String::isNotBlank)
            ?: "Could not prepare this channel for playback."
        mutableUiState.value = mutableUiState.value.copy(errorMessage = message)
    }

    fun recordRecentChannel(channel: LiveTvChannel) {
        if (!featureEnabled) return
        val state = mutableUiState.value
        // Stalker create_link returns a short-lived playback URL. Persist the
        // original channel URL/identity so Continue can resolve a fresh link.
        val channelToPersist = if (
            state.sourceType == LiveTvSourceType.Stalker &&
            !channel.stalkerCommand.isNullOrBlank()
        ) {
            state.channels.firstOrNull { it.id == channel.id }
                ?: state.channels.firstOrNull { it.stalkerCommand == channel.stalkerCommand }
                ?: channel
        } else {
            channel
        }
        val recentChannel = LiveTvRecentChannel(
            streamUrl = channelToPersist.streamUrl,
            name = channelToPersist.name,
            logoUrl = channelToPersist.logoUrl,
            group = channelToPersist.group,
            tvgId = channelToPersist.tvgId,
        )
        LiveTvStorage.saveRecentChannel(recentChannel)
        mutableUiState.value = state.copy(recentChannel = recentChannel)
    }

    private fun loadEpgInBackground(sourceUrl: String, epgUrls: List<String>) {
        if (!featureEnabled) return
        val profileIdAtStart = resolveLiveTvStorageProfileId()
        val loadToken = Any()
        activeEpgLoadToken = loadToken
        activeEpgSourceUrl = sourceUrl
        activeEpgUrls = epgUrls
        epgJob?.cancel()
        favoriteEpgReloadJob?.cancel()
        favoriteEpgReloadJob = null
        if (epgUrls.isEmpty() || !epgLoadingActive) {
            if (mutableUiState.value.sourceUrl == sourceUrl) {
                mutableUiState.value = mutableUiState.value.copy(
                    isEpgLoading = false,
                    currentProgrammes = emptyMap(),
                    programmesByChannel = emptyMap(),
                )
            }
            return
        }
        if (mutableUiState.value.sourceUrl == sourceUrl) {
            mutableUiState.value = mutableUiState.value.copy(
                isEpgLoading = true,
            )
        }
        epgJob = epgScope.launch {
            epgMutex.lock()
            try {
                val loadContext = currentCoroutineContext()
                loadContext.ensureActive()
                val nowEpochMs = LiveTvClock.nowEpochMs()
                val stateAtStart = mutableUiState.value
                var resolvedChannels = stateAtStart.channels
                val accumulatedProgrammes = mutableMapOf<String, MutableList<LiveTvProgramme>>()

                for (epgUrl in epgUrls) {
                    loadContext.ensureActive()
                    var freshContent: String? = null
                    repeat(2) { attempt ->
                        if (freshContent != null) return@repeat
                        loadContext.ensureActive()
                        try {
                            val payload = withContext(Dispatchers.IO) {
                                httpGetBytesWithHeaders(
                                    url = epgUrl,
                                    headers = mapOf(
                                        "Accept" to "application/xml,text/xml,application/gzip," +
                                            "application/x-xz,application/octet-stream,*/*",
                                    ),
                                    maxResponseBodyBytes = maxLiveTvEpgDownloadBytes(epgUrl),
                                )
                            }
                            freshContent = withContext(Dispatchers.Default) {
                                decodeLiveTvEpgPayload(
                                    url = epgUrl,
                                    payload = payload,
                                    onProgress = { loadContext.ensureActive() },
                                ).takeIf(::isValidXmlTvContent)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                        }
                        if (freshContent == null && attempt == 0) delay(350L)
                    }
                    loadContext.ensureActive()
                    ensureActiveEpgLoad(sourceUrl, loadToken)
                    val cachedEntry = if (freshContent == null) {
                        LiveTvStorage.readEpgCache(profileIdAtStart, epgUrl)
                    } else {
                        null
                    }
                    loadContext.ensureActive()
                    val content = when (chooseLiveTvEpgContent(freshContent != null, cachedEntry != null)) {
                        LiveTvEpgContentChoice.Fresh -> freshContent
                        LiveTvEpgContentChoice.Cached -> cachedEntry?.content
                        LiveTvEpgContentChoice.Failed -> null
                    }
                    if (content == null) {
                        continue
                    }
                    val schedule = try {
                        val parsed = withContext(Dispatchers.Default) {
                            val providerChannels = extractXmlTvChannelAliases(
                                content = content,
                                onProgress = { loadContext.ensureActive() },
                            )
                            val updatedChannels = resolveXmlTvChannelIds(resolvedChannels, providerChannels)
                            val relevantChannelIds = updatedChannels
                                .mapNotNull(LiveTvChannel::tvgId)
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .toSet()
                            val retainedScheduleChannelIds = updatedChannels
                                .asSequence()
                                .filter { channel -> channel.streamUrl in stateAtStart.favoriteUrls }
                                .mapNotNull(LiveTvChannel::tvgId)
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .toSet()
                            loadContext.ensureActive()
                            val parsedSchedule = parseXmlTvProgrammeSchedule(
                                content = content,
                                nowEpochMs = nowEpochMs,
                                relevantChannelIds = relevantChannelIds,
                                retainedScheduleChannelIds = retainedScheduleChannelIds,
                                onProgress = { loadContext.ensureActive() },
                            )
                            updatedChannels to parsedSchedule
                        }
                        resolvedChannels = parsed.first
                        loadContext.ensureActive()
                        if (
                            epgLoadingActive &&
                            activeEpgLoadToken === loadToken &&
                            mutableUiState.value.sourceUrl == sourceUrl
                        ) {
                            mutableUiState.value = mutableUiState.value.copy(channels = resolvedChannels)
                        }
                        val parsedSchedule = parsed.second
                        if (freshContent != null) {
                            loadContext.ensureActive()
                            ensureActiveEpgLoad(sourceUrl, loadToken)
                            LiveTvStorage.writeEpgCache(
                                profileId = profileIdAtStart,
                                entry = LiveTvEpgCacheEntry(
                                    url = epgUrl,
                                    content = content,
                                    savedAtEpochMs = nowEpochMs,
                                ),
                            )
                            loadContext.ensureActive()
                        }
                        parsedSchedule
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        continue
                    }
                    schedule.forEach { (channelId, programmes) ->
                        accumulatedProgrammes.getOrPut(channelId) { mutableListOf() }.addAll(programmes)
                    }
                }

                loadContext.ensureActive()
                val programmesByChannel = mergeXmlTvProgrammeSchedules(listOf(accumulatedProgrammes))
                if (
                    epgLoadingActive &&
                    activeEpgLoadToken === loadToken &&
                    mutableUiState.value.sourceUrl == sourceUrl
                ) {
                    mutableUiState.value = mutableUiState.value.copy(
                        channels = resolvedChannels,
                        programmesByChannel = programmesByChannel,
                        currentProgrammes = currentXmlTvProgrammes(programmesByChannel, nowEpochMs),
                        isEpgLoading = false,
                    )
                }
            } finally {
                if (
                    epgLoadingActive &&
                    activeEpgLoadToken === loadToken &&
                    mutableUiState.value.sourceUrl == sourceUrl
                ) {
                    mutableUiState.value = mutableUiState.value.copy(isEpgLoading = false)
                }
                epgMutex.unlock()
            }
        }
    }

    private fun ensureActiveEpgLoad(sourceUrl: String, loadToken: Any) {
        if (!epgLoadingActive || activeEpgLoadToken !== loadToken || mutableUiState.value.sourceUrl != sourceUrl) {
            throw CancellationException("EPG load superseded")
        }
    }
}

private fun liveTvDisabledResult(): Result<List<LiveTvChannel>> =
    Result.failure(IllegalStateException("Live TV is disabled."))

internal expect object LiveTvStorage {
    fun loadSourceType(): LiveTvSourceType
    fun saveSourceType(type: LiveTvSourceType)
    fun loadSourceUrl(): String?
    fun saveSourceUrl(url: String)
    fun loadLocalPlaylistData(): String?
    fun saveLocalPlaylistData(data: String)
    fun loadStalkerSettings(): LiveTvStalkerSettings
    fun saveStalkerSettings(settings: LiveTvStalkerSettings)
    fun loadXtreamSettings(): LiveTvXtreamSettings
    fun saveXtreamSettings(settings: LiveTvXtreamSettings)
    fun loadFavoriteUrls(): Set<String>
    fun saveFavoriteUrls(urls: Set<String>)
    fun loadRecentChannel(): LiveTvRecentChannel?
    fun saveRecentChannel(channel: LiveTvRecentChannel?)
}

private data class StalkerSession(
    val settings: LiveTvStalkerSettings,
    val token: String,
)

private suspend fun fetchStalkerChannels(settings: LiveTvStalkerSettings): List<LiveTvChannel> {
    val session = LiveTvRepositoryStalker.session(settings)
    val genres = LiveTvRepositoryStalker.getGenres(session)
    return LiveTvRepositoryStalker.getChannels(session, genres)
}

private suspend fun resolveStalkerPlaybackChannel(channel: LiveTvChannel): LiveTvChannel {
    val settings = LiveTvRepository.uiState.value.stalkerSettings.normalized()
    if (!settings.isConfigured) return channel
    val session = LiveTvRepositoryStalker.session(settings)
    val resolvedUrl = LiveTvRepositoryStalker.createLink(session, channel.stalkerCommand.orEmpty())
        ?: channel.streamUrl
    return channel.copy(
        streamUrl = resolvedUrl,
        headers = channel.headers + LiveTvRepositoryStalker.playbackHeaders(session),
    )
}

private suspend fun fetchXtreamChannels(settings: LiveTvXtreamSettings): List<LiveTvChannel> {
    val categories = LiveTvRepositoryXtream.getLiveCategories(settings)
    return LiveTvRepositoryXtream.getLiveStreams(settings, categories)
}

private object LiveTvRepositoryXtream {
    suspend fun getLiveCategories(settings: LiveTvXtreamSettings): Map<String, String> {
        val data = request(settings, action = "get_live_categories").jsonArrayOrEmpty()
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("category_id") ?: obj.stringValue("id") ?: return@associateNotNull null
            val name = obj.stringValue("category_name") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to name
        }
    }

    suspend fun getLiveStreams(
        settings: LiveTvXtreamSettings,
        categories: Map<String, String>,
    ): List<LiveTvChannel> {
        val data = request(settings, action = "get_live_streams").jsonArrayOrEmpty()
        return data.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = obj.stringValue("name") ?: return@mapIndexedNotNull null
            val streamId = obj.stringValue("stream_id") ?: obj.stringValue("id") ?: return@mapIndexedNotNull null
            val directSource = obj.stringValue("direct_source")
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            val extension = obj.stringValue("container_extension")
                ?.trim()
                ?.trimStart('.')
                ?.takeIf(String::isNotBlank)
                ?: "ts"
            val streamUrl = directSource ?: settings.liveStreamUrl(streamId, extension)
            val categoryId = obj.stringValue("category_id")
            LiveTvChannel(
                id = "xtream-$streamId-$index",
                name = name,
                streamUrl = streamUrl,
                tvgId = obj.stringValue("epg_channel_id") ?: obj.stringValue("tvg_id"),
                logoUrl = obj.stringValue("stream_icon") ?: obj.stringValue("logo"),
                group = categoryId?.let(categories::get).orEmpty(),
                headers = M3U_STREAM_REQUEST_HEADERS,
            )
        }.distinctBy { it.streamUrl }
    }

    private suspend fun request(settings: LiveTvXtreamSettings, action: String): JsonElement {
        val parameters = buildMap {
            put("username", settings.username)
            put("password", settings.password)
            put("action", action)
        }
        val url = settings.playerApiEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        return stalkerJson.parseToJsonElement(httpGetTextWithHeaders(url, M3U_PLAYLIST_REQUEST_HEADERS))
    }
}

private object LiveTvRepositoryStalker {
    private var cachedSession: StalkerSession? = null

    fun clearSession() {
        cachedSession = null
    }

    suspend fun session(settings: LiveTvStalkerSettings): StalkerSession {
        cachedSession
            ?.takeIf { it.settings == settings && it.token.isNotBlank() }
            ?.let { return it }

        val token = request(settings, type = "stb", action = "handshake")
            .stalkerJs()
            .stringValue("token")
            .orEmpty()
            .trim()
        require(token.isNotBlank()) { "Could not retrieve a Stalker portal token." }
        return StalkerSession(settings = settings, token = token).also {
            cachedSession = it
        }
    }

    suspend fun getGenres(session: StalkerSession): Map<String, String> {
        val data = request(session.settings, session.token, type = "itv", action = "get_genres")
            .stalkerJs()
            .arrayValue("data")
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("id") ?: obj.stringValue("alias") ?: return@associateNotNull null
            val title = obj.stringValue("title") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to title
        }
    }

    suspend fun getChannels(
        session: StalkerSession,
        genres: Map<String, String>,
    ): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        for (pageIndex in 0 until 20) {
            val page = pageIndex + 1
            val data = request(
                settings = session.settings,
                token = session.token,
                type = "itv",
                action = "get_ordered_list",
                extraParameters = mapOf("p" to page.toString()),
            ).stalkerJs().arrayValue("data")
            if (data.isEmpty()) break
            data.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val name = obj.stringValue("name")
                    ?: obj.stringValue("title")
                    ?: return@forEachIndexed
                val command = obj.stringValue("cmd")
                    ?: obj.stringValue("mc_cmd")
                    ?: obj.stringValue("url")
                    ?: return@forEachIndexed
                val streamUrl = command.toStalkerPlayableUrl()
                if (streamUrl.isBlank()) return@forEachIndexed
                val genreId = obj.stringValue("tv_genre_id") ?: obj.stringValue("genre_id")
                channels += LiveTvChannel(
                    id = obj.stringValue("id") ?: "stalker-${page}-$index-${streamUrl.hashCode()}",
                    name = name,
                    streamUrl = streamUrl,
                    tvgId = obj.stringValue("xmltv_id") ?: obj.stringValue("tvg_id"),
                    logoUrl = obj.stringValue("logo") ?: obj.stringValue("logo_url"),
                    group = genreId?.let(genres::get).orEmpty(),
                    headers = playbackHeaders(session),
                    stalkerCommand = command,
                )
            }
        }
        return channels.distinctBy { it.id.ifBlank { it.streamUrl } }
    }

    suspend fun createLink(session: StalkerSession, command: String): String? {
        val data = request(
            settings = session.settings,
            token = session.token,
            type = "itv",
            action = "create_link",
            extraParameters = mapOf("cmd" to command),
        ).stalkerJs()
        return (data.stringValue("cmd") ?: data.stringValue("url") ?: data.stringValue("stream_url"))
            ?.toStalkerPlayableUrl()
            ?.takeIf { it.isNotBlank() }
    }

    fun playbackHeaders(session: StalkerSession): Map<String, String> =
        baseHeaders(session.settings) + mapOf(
            "Authorization" to "Bearer ${session.token}",
        )

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String? = null,
        type: String,
        action: String,
        extraParameters: Map<String, String> = emptyMap(),
    ): JsonObject {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extraParameters)
        }
        val url = settings.portalEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = if (settings.portalEndpoint().contains("?")) "&" else "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val payload = httpGetTextWithHeaders(url, baseHeaders(settings) + tokenHeader(token))
        return stalkerJson.parseToJsonElement(payload).jsonObject
    }

    private fun baseHeaders(settings: LiveTvStalkerSettings): Map<String, String> =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
            "X-User-Agent" to "Model: MAG254; Link: Ethernet",
            "Referer" to settings.portalBaseUrl(),
            "Cookie" to "mac=${settings.macAddress}; stb_lang=en; timezone=Europe%2FIstanbul",
        )

    private fun tokenHeader(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
}

private val stalkerJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun LiveTvStalkerSettings.normalized(): LiveTvStalkerSettings =
    copy(
        portalUrl = portalUrl.trim().trimEnd('/'),
        macAddress = macAddress.trim().uppercase(),
        username = username.trim(),
        password = password.trim(),
    )

private fun LiveTvXtreamSettings.normalized(): LiveTvXtreamSettings =
    copy(
        serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
        username = username.trim(),
        password = password.trim(),
    )

private fun LiveTvXtreamSettings.playerApiEndpoint(): String =
    "${serverUrl.trim().trimEnd('/')}/player_api.php"

private fun LiveTvXtreamSettings.liveStreamUrl(streamId: String, extension: String): String =
    buildString {
        append(serverUrl.trim().trimEnd('/'))
        append("/live/")
        append(username.encodeURLParameter())
        append("/")
        append(password.encodeURLParameter())
        append("/")
        append(streamId.encodeURLParameter())
        append(".")
        append(extension.trim().trimStart('.').ifBlank { "ts" })
    }

private fun LiveTvStalkerSettings.portalEndpoint(): String {
    val normalized = portalUrl.trim().trimEnd('/')
    return when {
        normalized.endsWith("portal.php", ignoreCase = true) -> normalized
        normalized.contains("portal.php?", ignoreCase = true) -> normalized
        else -> "$normalized/portal.php"
    }
}

private fun LiveTvStalkerSettings.portalBaseUrl(): String =
    portalUrl.trim().substringBefore("/portal.php").trimEnd('/') + "/c/"

internal fun String.toStalkerPlayableUrl(): String {
    var normalized = trim()
    while (true) {
        val prefix = when {
            normalized.startsWith("ffmpeg ", ignoreCase = true) -> "ffmpeg "
            normalized.startsWith("auto ", ignoreCase = true) -> "auto "
            else -> null
        } ?: break
        normalized = normalized.substring(prefix.length).trimStart()
    }
    return normalized.substringBefore(' ').trim()
}

private fun JsonObject.stalkerJs(): JsonObject =
    (this["js"] as? JsonObject) ?: this

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.arrayValue(name: String): List<JsonElement> =
    (this[name] as? JsonArray)?.toList().orEmpty()

private fun JsonElement.jsonArrayOrEmpty(): List<JsonElement> =
    (this as? JsonArray)?.toList()
        ?: (this as? JsonObject)?.arrayValue("data")
        ?: emptyList()

private inline fun <K, V> Iterable<JsonElement>.associateNotNull(transform: (JsonElement) -> Pair<K, V>?): Map<K, V> =
    mapNotNull(transform).toMap()

internal fun defaultM3uStreamHeaders(url: String): Map<String, String> {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return emptyMap()
    return M3U_STREAM_REQUEST_HEADERS
}

private fun directStreamChannel(url: String): LiveTvChannel =
    LiveTvChannel(
        id = "direct-${url.hashCode()}",
        name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Live stream" },
        streamUrl = url,
        group = "Direct stream",
        headers = defaultM3uStreamHeaders(url),
        streamType = url.inferM3uStreamType(),
    )

private fun String.looksLikeDirectVideoUrl(): Boolean {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    if (normalized.endsWith(".m3u") || normalized.endsWith(".m3u8")) return false
    return listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".mpeg", ".mpg")
        .any(normalized::endsWith)
}

internal fun String.inferM3uStreamType(): String? {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    return when {
        normalized.endsWith(".m3u8") -> "hls"
        normalized.endsWith(".mkv") -> "matroska"
        normalized.endsWith(".mp4") || normalized.endsWith(".m4v") -> "mp4"
        normalized.endsWith(".webm") -> "webm"
        normalized.endsWith(".ts") || normalized.endsWith(".mts") || normalized.endsWith(".m2ts") -> "mpegts"
        else -> null
    }
}

internal fun String.looksLikeHlsManifest(): Boolean =
    lineSequence().any { it.trim().startsWith("#EXT-X-", ignoreCase = true) }

private val M3U_PLAYLIST_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
    "Accept" to "application/x-mpegURL, application/vnd.apple.mpegurl, audio/mpegurl, text/plain, */*",
)

private val M3U_STREAM_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
)

internal fun isLikelyCategoryHeading(name: String): Boolean {
    val normalized = name.trim()
    return normalized.length >= 8 && Regex("""^\s*#+\s*.+\s*#+\s*$""").matches(normalized)
}

internal expect object LiveTvClock {
    fun nowEpochMs(): Long
    fun formatLocalTime(epochMs: Long): String
    fun parseXmlTvTimestamp(value: String): Long?
}

internal fun parseCurrentXmlTvProgrammes(
    content: String,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): Map<String, LiveTvProgramme> =
    currentXmlTvProgrammes(
        programmesByChannel = parseXmlTvProgrammeSchedule(content, nowEpochMs),
        nowEpochMs = nowEpochMs,
    )
