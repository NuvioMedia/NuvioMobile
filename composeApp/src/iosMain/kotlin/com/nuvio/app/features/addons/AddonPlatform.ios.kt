@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.addons

import com.nuvio.app.core.build.AppVersionConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.DISPATCH_QUEUE_SERIAL_WITH_AUTORELEASE_POOL
import platform.darwin.dispatch_queue_create

actual object AddonStorage {
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(
            urls.joinToString(separator = "\n"),
            forKey = "${addonUrlsKey}_$profileId",
        )
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonEnabledStatesKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${addonEnabledStatesKey}_$profileId",
        )
    }
}

private fun parseEnabledStateLine(line: String): Pair<String, Boolean>? {
    val url = line.substringBefore("\t").trim().takeIf { it.isNotEmpty() } ?: return null
    val rawEnabled = line.substringAfter("\t", "true").trim().lowercase()
    val enabled = when (rawEnabled) {
        "false" -> false
        else -> true
    }
    return url to enabled
}

/**
 * Keeps the last known transport (`cellular`, `wifi`, `other` or `none`)
 * updated by an NWPathMonitor. The object is initialized lazily on first
 * request (Kotlin/Native object init is thread-safe, so the monitor is
 * created exactly once per process). Reading [current] never blocks; before
 * the first callback fires it reports `unknown`.
 */
private object NetworkTransportMonitor {
    @Volatile
    var current: String = "unknown"
        private set

    private val monitor = nw_path_monitor_create()
    private val queue = dispatch_queue_create(
        label = "com.nuvio.addons.network-monitor",
        attr = DISPATCH_QUEUE_SERIAL_WITH_AUTORELEASE_POOL,
    )

    init {
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            val transport = when {
                nw_path_get_status(path) != nw_path_status_satisfied -> "none"
                nw_path_uses_interface_type(path, nw_interface_type_cellular) -> "cellular"
                nw_path_uses_interface_type(path, nw_interface_type_wifi) -> "wifi"
                else -> "other"
            }
            current = transport
        }
        nw_path_monitor_start(monitor)
        // The monitor is intentionally never cancelled: it lives for the whole
        // process and is cheap. The field keeps the native handle alive.
    }
}

/** Canonical User-Agent sent on addon requests that do not already carry one. */
private val ADDON_USER_AGENT: String
    get() = "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }} (iOS)"

/**
 * Headers applied to Stremio-style addon requests (not to plugin/raw traffic):
 * a canonical User-Agent unless the caller already set one, and the current
 * transport so addon hosts such as AIOStreams can serve device/network-
 * appropriate results via conditional variants.
 */
private fun addAddonIdentityHeaders(builder: HttpRequestBuilder): HttpRequestBuilder {
    if (builder.headers[HttpHeaders.UserAgent] == null) {
        builder.header(HttpHeaders.UserAgent, ADDON_USER_AGENT)
    }
    if (builder.headers["X-Nuvio-Network"] == null) {
        builder.header("X-Nuvio-Network", NetworkTransportMonitor.current)
    }
    return builder
}

private val addonHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    expectSuccess = false
}

actual suspend fun httpGetText(url: String): String =
    addonHttpClient
        .get(url) {
            addAddonIdentityHeaders(this)
            accept(ContentType.Application.Json)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpPostJson(url: String, body: String): String =
    addonHttpClient
        .post(url) {
            addAddonIdentityHeaders(this)
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    addonHttpClient
        .get(url) {
            accept(ContentType.Application.Json)
            headers.forEach { (key, value) ->
                header(key, value)
            }
            // Apply identity headers last so a caller-supplied User-Agent is
            // never duplicated (Ktor's header() appends rather than replaces).
            addAddonIdentityHeaders(this)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            headers.forEach { (key, value) ->
                header(key, value)
            }
            // Apply identity headers last so a caller-supplied User-Agent is
            // never duplicated (Ktor's header() appends rather than replaces).
            addAddonIdentityHeaders(this)
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error(runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
): RawHttpResponse =
    addonHttpClient
        .request {
            url(url)
            this.method = HttpMethod.parse(method.uppercase())
            headers.forEach { (key, value) ->
                header(key, value)
            }
            if (this.method == HttpMethod.Post || this.method == HttpMethod.Put || this.method == HttpMethod.Patch) {
                setBody(body)
            }
        }
        .let { response ->
            RawHttpResponse(
                status = response.status.value,
                statusText = response.status.description,
                url = response.call.request.url.toString(),
                body = response.bodyAsText(),
                headers = response.headers.entries().associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                },
            )
        }
