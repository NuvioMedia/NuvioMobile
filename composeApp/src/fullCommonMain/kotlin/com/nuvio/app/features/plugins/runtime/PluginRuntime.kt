package com.nuvio.app.features.plugins.runtime

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.plugins.runtime.crypto.CryptoBridge
import com.nuvio.app.features.plugins.runtime.dom.DomBridge
import com.nuvio.app.features.plugins.runtime.host.HostApiRegistry
import com.nuvio.app.features.plugins.runtime.host.HostFunctions
import com.nuvio.app.features.plugins.runtime.js.JsBindings
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.nuvio.app.features.plugins.runtime.network.FetchBridge
import com.nuvio.app.features.plugins.runtime.network.UrlBridge
import com.nuvio.app.features.plugins.runtime.wasm.WasmBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.generic_unknown
import org.jetbrains.compose.resources.getString

internal const val MAX_CONCURRENT_PLUGINS = 10
internal const val PLUGIN_TIMEOUT_MS = 60_000L

internal object PluginRuntime {
    private val json = Json { ignoreUnknownKeys = true }
    private val scraperSemaphore = Semaphore(MAX_CONCURRENT_PLUGINS)
    private val searchPaused = MutableStateFlow(false)

    fun setSearchPaused(paused: Boolean) {
        searchPaused.value = paused
    }

    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        respectSearchPause: Boolean = true,
    ): List<PluginRuntimeResult> {
        suspend fun run(): List<PluginRuntimeResult> {
            val scraperSettingsJson = PluginStorage.loadScraperSettings(scraperId) ?: "{}"
            val scraperSettingsMap = runCatching {
                json.decodeFromString<Map<String, JsonElement>>(scraperSettingsJson)
            }.getOrElse { emptyMap() }

            return scraperSemaphore.withPermit {
                withContext(pluginDispatcher) {
                    withTimeout(PLUGIN_TIMEOUT_MS) {
                        executePluginInternal(
                            code = code,
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            season = season,
                            episode = episode,
                            scraperId = scraperId,
                            scraperSettings = scraperSettingsMap,
                        )
                    }
                }
            }
        }

        return if (respectSearchPause) {
            runWhenSearchActive { run() }
        } else {
            run()
        }
    }

    suspend fun getPluginSettingsLayout(
        code: String,
        scraperId: String,
    ): String? = scraperSemaphore.withPermit {
        withContext(pluginDispatcher) {
            withTimeout(PLUGIN_TIMEOUT_MS) {
                val jsRuntime = JsRuntime()
                val deferred = CompletableDeferred<String?>()
                try {
                    jsRuntime.use {
                        HostFunctions(
                            scraperId = scraperId,
                            scraperSettingsJson = "{}",
                            onResult = { deferred.complete(it) },
                        ).register(this)
                        FetchBridge().register(this)
                        UrlBridge().register(this)
                        CryptoBridge().register(this)

                        evaluateCached({ JsRuntime.polyfillBytecode(this) }, JsBindings.staticPolyfillCode)
                        evaluate<Any?>(wrapPluginModule(code))
                        evaluateCached({ JsRuntime.settingsCallBytecode(this) }, JsBindings.staticSettingsCallCode)
                        deferred.await()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, JsonElement>,
    ): List<PluginRuntimeResult> {
        val jsRuntime = JsRuntime()
        val deferred = CompletableDeferred<String>()
        val settingsJson = JsonObject(scraperSettings).toString()
        val callArgsJson = JsonObject(
            mapOf(
                "tmdbId" to JsonPrimitive(tmdbId),
                "mediaType" to JsonPrimitive(mediaType),
                "season" to (season?.let(::JsonPrimitive) ?: JsonNull),
                "episode" to (episode?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ).toString()

        val domBridge = DomBridge()
        val hostRegistry = HostApiRegistry().apply {
            addModule(
                HostFunctions(
                    scraperId = scraperId,
                    scraperSettingsJson = settingsJson,
                    callArgsJson = callArgsJson,
                    onResult = { deferred.complete(it) },
                ),
            )
            addModule(FetchBridge())
            addModule(UrlBridge())
            addModule(CryptoBridge())
            addModule(WasmBridge())
            addModule(domBridge)
        }

        try {
            jsRuntime.use {
                hostRegistry.registerAll(this)
                evaluateCached({ JsRuntime.polyfillBytecode(this) }, JsBindings.staticPolyfillCode)
                evaluate<Any?>(wrapPluginModule(code))
                evaluateCached({ JsRuntime.callBytecode(this) }, JsBindings.staticCallCode)
                deferred.await()
            }
            return parseJsonResults(deferred.await())
        } finally {
            domBridge.clear()
        }
    }

    private fun wrapPluginModule(code: String): String = """
        var module = { exports: {} };
        var exports = module.exports;
        (function() {
            $code
        })();
    """.trimIndent()

    private suspend fun com.dokar.quickjs.QuickJs.evaluateCached(
        bytecode: com.dokar.quickjs.QuickJs.() -> ByteArray,
        source: String,
    ) {
        val compiled = runCatching { bytecode() }.getOrNull()
        if (compiled != null) {
            evaluate<Any?>(compiled)
        } else {
            evaluate<Any?>(source)
        }
    }

    private fun parseJsonResults(rawJson: String): List<PluginRuntimeResult> {
        return runCatching {
            val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = when (val urlValue = item["url"]) {
                    is JsonPrimitive -> urlValue.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonObject -> urlValue["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null

                val headers = (item["headers"] as? JsonObject)
                    ?.mapNotNull { (key, value) ->
                        value.jsonPrimitive.contentOrNull?.let { key to it }
                    }
                    ?.toMap()
                    ?.takeIf { it.isNotEmpty() }

                val subtitles = (item["subtitles"] as? JsonArray)?.mapNotNull { subElement ->
                    val subObj = subElement as? JsonObject ?: return@mapNotNull null
                    val subUrl = subObj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val subLang = subObj["language"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val subName = subObj["name"]?.jsonPrimitive?.contentOrNull
                    val subHeaders = (subObj["headers"] as? JsonObject)
                        ?.mapNotNull { (key, value) ->
                            value.jsonPrimitive.contentOrNull?.let { key to it }
                        }
                        ?.toMap()
                        ?.takeIf { it.isNotEmpty() }
                    com.nuvio.app.features.plugins.PluginSubtitleResult(
                        url = subUrl,
                        language = subLang,
                        name = subName,
                        headers = subHeaders
                    )
                }?.takeIf { it.isNotEmpty() }

                PluginRuntimeResult(
                    title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: runBlocking { getString(Res.string.generic_unknown) },
                    name = item.stringOrNull("name"),
                    url = url,
                    quality = item.stringOrNull("quality"),
                    size = item.stringOrNull("size"),
                    language = item.stringOrNull("language"),
                    provider = item.stringOrNull("provider"),
                    type = item.stringOrNull("type"),
                    seeders = item["seeders"]?.jsonPrimitive?.intOrNull,
                    peers = item["peers"]?.jsonPrimitive?.intOrNull,
                    infoHash = item.stringOrNull("infoHash"),
                    headers = headers,
                    subtitles = subtitles,
                )
            }.filter { it.url.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }

    private suspend fun <T> runWhenSearchActive(block: suspend () -> T): T {
        while (true) {
            searchPaused.first { !it }
            try {
                return coroutineScope {
                    val watcher = launch {
                        searchPaused.first { it }
                        throw CancellationException(PAUSED_MESSAGE)
                    }
                    try {
                        block()
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (searchPaused.value || cancelled.message == PAUSED_MESSAGE) {
                    continue
                }
                throw cancelled
            }
        }
    }

    private const val PAUSED_MESSAGE = "plugin-search-paused"
}
