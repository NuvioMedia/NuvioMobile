package com.nuvio.app.features.plugins.runtime.network

import co.touchlab.kermit.Logger
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.plugins.runtime.host.HostModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"
private val CF_BLOCK_CODES = setOf(403, 503)
private val CF_SERVER_MARKERS = setOf("cloudflare", "cloudflare-nginx")

internal class FetchBridge(private val pluginId: String) : HostModule {
    private val log = Logger.withTag("PluginRuntime")
    private val json = Json { ignoreUnknownKeys = true }
    private val cfSolver: WebViewSolver by lazy { createPlatformWebViewSolver() }

    override fun register(runtime: QuickJs) {
        runtime.function("__native_fetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            val useCfKiller = args.getOrNull(5) as? Boolean ?: false
            try {
                performNativeFetch(url, method, headersJson, body, followRedirects, useCfKiller)
            } catch (t: Throwable) {
                log.e(t) { "Fetch bridge error for $method $url" }
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(false),
                        "status" to JsonPrimitive(0),
                        "statusText" to JsonPrimitive(t.message ?: "Fetch failed"),
                        "url" to JsonPrimitive(url),
                        "body" to JsonPrimitive(""),
                        "headers" to JsonObject(emptyMap()),
                    ),
                ).toString()
            }
        }
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
        useCfKiller: Boolean,
    ): String {
        val headers = parseHeaders(headersJson).toMutableMap()
        if (headers.getIgnoreCase("User-Agent") == null) {
            headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        }

        val host = extractHost(url)
        var usedCachedSession: CfSolveResult? = null
        if (useCfKiller) {
            CfSessionCache.get(host, pluginId)?.let { cached ->
                headers.setIgnoreCase("User-Agent", cached.userAgent)
                headers.setIgnoreCase(
                    "Cookie",
                    mergeCookies(headers.getIgnoreCase("Cookie").orEmpty(), cached.cookies.toCookieHeader()),
                )
                usedCachedSession = cached
                log.d { "CF: using cached session for $host" }
            }
        }

        val response = runBlocking {
            httpRequestRaw(
                method = method,
                url = url,
                headers = headers,
                body = body,
                followRedirects = followRedirects,
            )
        }

        if (useCfKiller && isCloudflareBlocked(response.status, response.headers)) {
            log.i { "CF: blocked (${response.status}) at $url; launching WebView solver" }
            val solveResult = runBlocking {
                CfSessionCache.getMutex(host).withLock {
                    usedCachedSession?.let { CfSessionCache.evictIfSame(host, it) }

                    CfSessionCache.get(host, pluginId) ?: cfSolver
                        .solve(
                            url = url,
                            headers = webViewHeaders(headers),
                            forceFresh = true,
                        )
                        ?.also { solved ->
                            CfSessionCache.put(host, pluginId, solved)
                        }
                }
            }

            if (solveResult != null) {
                solveResult.redirectUrl?.let { redirectUrl ->
                    val redirectHost = extractHost(redirectUrl)
                    if (redirectHost != host) {
                        CfSessionCache.put(redirectHost, pluginId, solveResult)
                    }
                }

                val retryHeaders = parseHeaders(headersJson).toMutableMap()
                retryHeaders.setIgnoreCase("User-Agent", solveResult.userAgent)
                retryHeaders.setIgnoreCase(
                    "Cookie",
                    mergeCookies(retryHeaders.getIgnoreCase("Cookie").orEmpty(), solveResult.cookies.toCookieHeader()),
                )

                val retryUrl = solveResult.redirectUrl ?: url
                var retryResponse = runBlocking {
                    httpRequestRaw(
                        method = method,
                        url = retryUrl,
                        headers = retryHeaders,
                        body = body,
                        followRedirects = followRedirects,
                    )
                }

                if (isCloudflareBlocked(retryResponse.status, retryResponse.headers)) {
                    runBlocking { delay(500L) }
                    retryResponse = runBlocking {
                        httpRequestRaw(
                            method = method,
                            url = retryUrl,
                            headers = retryHeaders,
                            body = body,
                            followRedirects = followRedirects,
                        )
                    }
                }

                if (isCloudflareBlocked(retryResponse.status, retryResponse.headers)) {
                    log.w { "CF: retry still blocked (${retryResponse.status}) at $retryUrl; trying WebView fetch fallback" }
                    val renderedResponse = runBlocking {
                        cfSolver.fetchRenderedPage(
                            url = retryUrl,
                            headers = webViewHeaders(retryHeaders),
                        )
                    }

                    if (renderedResponse != null && !isRenderedCloudflareBlocked(renderedResponse)) {
                        log.i { "CF: WebView fetch fallback success for ${renderedResponse.url}" }
                        return buildResponseJson(renderedResponse)
                    }

                    log.w { "CF: WebView fetch fallback failed for $retryUrl; evicting session" }
                    CfSessionCache.evict(host, pluginId)
                    solveResult.redirectUrl?.let { CfSessionCache.evict(extractHost(it), pluginId) }
                } else {
                    log.i { "CF: solve success (${retryResponse.status}) for $retryUrl" }
                }

                return buildResponseJson(retryResponse)
            }

            log.w { "CF: WebView solver timed out for $url" }
        }

        return buildResponseJson(response)
    }

    private fun buildResponseJson(response: com.nuvio.app.features.addons.RawHttpResponse): String {
        val responseHeaders = response.headers.mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS) }
        val result = JsonObject(
            mapOf(
                "ok" to JsonPrimitive(response.status in 200..299),
                "status" to JsonPrimitive(response.status),
                "statusText" to JsonPrimitive(response.statusText),
                "url" to JsonPrimitive(response.url),
                "body" to JsonPrimitive(response.body),
                "headers" to JsonObject(responseHeaders.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        return result.toString()
    }

    private fun buildResponseJson(response: WebViewFetchResult): String {
        val responseHeaders = response.headers.mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS) }
        val result = JsonObject(
            mapOf(
                "ok" to JsonPrimitive(response.status in 200..299),
                "status" to JsonPrimitive(response.status),
                "statusText" to JsonPrimitive(response.statusText),
                "url" to JsonPrimitive(response.url),
                "body" to JsonPrimitive(response.body),
                "headers" to JsonObject(responseHeaders.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        return result.toString()
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        return runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject ?: JsonObject(emptyMap())
            obj.entries
                .mapNotNull { (key, value) ->
                    value.jsonPrimitive.contentOrNull?.let { key to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun isCloudflareBlocked(status: Int, headers: Map<String, String>): Boolean {
        if (status !in CF_BLOCK_CODES) return false
        val server = headers.getIgnoreCase("Server").orEmpty().lowercase()
        val hasCfServer = CF_SERVER_MARKERS.any { marker -> server.contains(marker) }
        val hasCfHeaders = headers.keys.any { key -> key.lowercase().startsWith("cf-") }
        return hasCfServer || hasCfHeaders
    }

    private fun isRenderedCloudflareBlocked(response: WebViewFetchResult): Boolean {
        if (isCloudflareBlocked(response.status, response.headers)) return true
        val body = response.body.lowercase()
        return body.contains("cf-browser-verification") ||
            body.contains("challenge-platform") ||
            body.contains("cf-challenge-running") ||
            body.contains("turnstile-wrapper") ||
            body.contains("just a moment")
    }

    private fun mergeCookies(existing: String, extra: String): String {
        if (existing.isBlank()) return extra
        if (extra.isBlank()) return existing
        return (parseCookieString(existing) + parseCookieString(extra)).toCookieHeader()
    }

    private fun webViewHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { key ->
            !key.equals("User-Agent", ignoreCase = true) &&
                !key.equals("Cookie", ignoreCase = true) &&
                !key.equals("X-Requested-With", ignoreCase = true)
        }

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    private fun MutableMap<String, String>.setIgnoreCase(name: String, value: String) {
        keys.filter { it.equals(name, ignoreCase = true) }.forEach { remove(it) }
        put(name, value)
    }

    private fun Map<String, String>.getIgnoreCase(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
