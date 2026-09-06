package com.nuvio.app.features.plugins.runtime

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.plugins.runtime.crypto.CryptoBridge
import com.nuvio.app.features.plugins.runtime.dom.DomBridge
import com.nuvio.app.features.plugins.runtime.host.HostApiRegistry
import com.nuvio.app.features.plugins.runtime.host.HostFunctions
import com.nuvio.app.features.plugins.runtime.js.JsBindings
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.dokar.quickjs.binding.function
import com.nuvio.app.features.plugins.runtime.network.FetchBridge
import com.nuvio.app.features.plugins.runtime.network.UrlBridge
import com.nuvio.app.features.plugins.runtime.wasm.WasmBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

private const val PLUGIN_TIMEOUT_MS = 60_000L

internal object PluginRuntime {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
    ): List<PluginRuntimeResult> = withContext(Dispatchers.Default) {
        val scraperSettingsJson = PluginStorage.loadScraperSettings(scraperId) ?: "{}"
        val scraperSettingsMap = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(scraperSettingsJson)
        }.getOrElse { emptyMap() }

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

    suspend fun getPluginSettingsLayout(
        code: String,
        scraperId: String,
    ): String? = withContext(Dispatchers.Default) {
        withTimeout(PLUGIN_TIMEOUT_MS) {
            val jsRuntime = JsRuntime()
            val deferred = CompletableDeferred<String?>()

            try {
                jsRuntime.use {
                    val polyfillCode = JsBindings.buildPolyfillCode(
                        scraperIdJson = JsonPrimitive(scraperId).toString(),
                        settingsJson = "{}"
                    )
                    evaluate<Any?>(polyfillCode)

                    val wrappedCode = """
                        var module = { exports: {} };
                        var exports = module.exports;
                        (function() {
                            $code
                        })();
                    """.trimIndent()
                    evaluate<Any?>(wrappedCode)

                    val callCode = """
                        (async function() {
                            try {
                                var onSettings = (typeof module !== 'undefined' && module.exports && module.exports.onSettings) || globalThis.onSettings;
                                if (typeof onSettings === 'function') {
                                    var layout = await onSettings();
                                    __capture_settings_result(JSON.stringify(layout || []));
                                } else {
                                    __capture_settings_result("[]");
                                }
                            } catch (e) {
                                console.error("onSettings error:", e);
                                __capture_settings_result("[]");
                            }
                        })();
                    """.trimIndent()
                    
                    function("__capture_settings_result") { args: Array<Any?> ->
                        deferred.complete(args.getOrNull(0)?.toString())
                        null
                    }
                    
                    evaluate<Any?>(callCode)
                    deferred.await()
                }
            } catch (e: Exception) {
                null
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

        val domBridge = DomBridge()
        val hostRegistry = HostApiRegistry().apply {
            addModule(HostFunctions(scraperId) { deferred.complete(it) })
            addModule(FetchBridge())
            addModule(UrlBridge())
            addModule(CryptoBridge())
            addModule(WasmBridge())
            addModule(domBridge)
        }

        try {
            jsRuntime.use {
                hostRegistry.registerAll(this)

                val settingsJson = JsonObject(scraperSettings).toString()
                val polyfillCode = JsBindings.buildPolyfillCode(
                    scraperIdJson = JsonPrimitive(scraperId).toString(),
                    settingsJson = settingsJson,
                )
                evaluate<Any?>(polyfillCode)

                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedCode)

                val tmdbIdArg = JsonPrimitive(tmdbId).toString()
                val mediaTypeArg = JsonPrimitive(mediaType).toString()
                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"
                val callCode = """
                    (async function() {
                        try {
                            var getStreams = module.exports.getStreams || globalThis.getStreams;
                            if (!getStreams) {
                                console.error("getStreams function not found on module.exports or globalThis");
                                __capture_result(JSON.stringify([]));
                                return;
                            }
                            var result = await getStreams($tmdbIdArg, $mediaTypeArg, $seasonArg, $episodeArg);
                            __capture_result(JSON.stringify(result || []));
                        } catch (e) {
                            console.error("getStreams error:", e && e.message ? e.message : e, e && e.stack ? e.stack : "");
                            __capture_result(JSON.stringify([]));
                        }
                    })();
                """.trimIndent()
                evaluate<Any?>(callCode)
                
                deferred.await()
            }
            
            // Result is captured inside use block, but returned outside to satisfy compiler
            return parseJsonResults(deferred.await())
        } finally {
            domBridge.clear()
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

                val clientResolve = (item["clientResolve"] as? JsonObject)?.let { resolveObj ->
                    com.nuvio.app.features.streams.StreamClientResolve(
                        type = resolveObj["type"]?.jsonPrimitive?.contentOrNull,
                        infoHash = resolveObj["infoHash"]?.jsonPrimitive?.contentOrNull,
                        service = resolveObj["service"]?.jsonPrimitive?.contentOrNull,
                        isCached = resolveObj["isCached"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: resolveObj["isCached"]?.jsonPrimitive?.booleanOrNull
                    )
                }

                PluginRuntimeResult(
                    title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: runBlocking { getString(Res.string.generic_unknown) },
                    name = item.stringOrNull("name"),
                    url = url ?: "", // Default to empty if missing but clientResolve is present
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
                    clientResolve = clientResolve,
                )
            }.filter { it.url.isNotBlank() || it.clientResolve != null }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { it.key is String }
                .associate { (it.key as String) to toJsonElement(it.value) },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
    }
}
