package com.nuvio.app.features.plugins.runtime.network

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class CfSolveResult(
    val cookies: Map<String, String>,
    val userAgent: String,
    val redirectUrl: String? = null,
)

internal data class WebViewFetchResult(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

internal interface WebViewSolver {
    suspend fun solve(
        url: String,
        headers: Map<String, String> = emptyMap(),
        forceFresh: Boolean = false,
        timeoutMs: Long = 60_000L,
    ): CfSolveResult?

    suspend fun fetchRenderedPage(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000L,
    ): WebViewFetchResult? = null
}

internal fun createPlatformWebViewSolver(): WebViewSolver = platformWebViewSolverImpl()

internal object CfSessionCache {
    private val sessions = mutableMapOf<String, CfSolveResult>()
    private val hostLocks = mutableMapOf<String, Mutex>()
    private val lock = SynchronizedObject()

    fun getMutex(host: String): Mutex = synchronized(lock) {
        hostLocks.getOrPut(host) { Mutex() }
    }

    fun get(host: String, pluginId: String): CfSolveResult? = synchronized(lock) {
        sessions[host]?.let { return it }
        val persisted = runCatching {
            com.nuvio.app.features.plugins.PluginStorage.loadCfSession(host)
        }.getOrNull()
        val parsed = persisted?.let(::jsonStringToCfSolveResult) ?: return null
        sessions[host] = parsed
        parsed
    }

    fun put(host: String, pluginId: String, result: CfSolveResult) = synchronized(lock) {
        sessions[host] = result
        runCatching {
            com.nuvio.app.features.plugins.PluginStorage.saveCfSession(host, result.toJsonString())
        }
    }

    fun evict(host: String, pluginId: String) = synchronized(lock) {
        sessions.remove(host)
        runCatching {
            com.nuvio.app.features.plugins.PluginStorage.removeCfSession(host)
        }
    }

    fun evictIfSame(host: String, result: CfSolveResult) = synchronized(lock) {
        if (sessions[host] == result) {
            sessions.remove(host)
            runCatching {
                com.nuvio.app.features.plugins.PluginStorage.removeCfSession(host)
            }
        }
    }

    fun clear() = synchronized(lock) {
        sessions.clear()
    }
}

private val COOKIE_DIRECTIVES = setOf(
    "path",
    "domain",
    "expires",
    "max-age",
    "secure",
    "httponly",
    "samesite",
    "priority",
)

internal fun parseCookieString(raw: String): Map<String, String> =
    raw.split(";").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) return@mapNotNull null
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        if (name.isBlank() || value.isBlank() || name.lowercase() in COOKIE_DIRECTIVES) {
            null
        } else {
            name to value
        }
    }.toMap()

internal fun Map<String, String>.toCookieHeader(): String =
    entries.joinToString("; ") { (key, value) -> "$key=$value" }

internal fun extractHost(url: String): String = runCatching {
    val authority = url
        .substringAfter("://", url)
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .substringAfterLast("@")
    if (authority.startsWith("[")) {
        authority.substringBefore("]").removePrefix("[")
    } else {
        authority.substringBefore(":")
    }.lowercase()
}.getOrDefault(url)

private fun CfSolveResult.toJsonString(): String = buildJsonObject {
    put("cookies", buildJsonObject {
        cookies.forEach { (key, value) -> put(key, value) }
    })
    put("userAgent", userAgent)
    redirectUrl?.let { put("redirectUrl", it) }
}.toString()

private fun jsonStringToCfSolveResult(raw: String): CfSolveResult? = runCatching {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val cookies = obj["cookies"]?.jsonObject?.entries?.associate { (key, value) ->
        key to value.jsonPrimitive.content
    }.orEmpty()
    val userAgent = obj["userAgent"]?.jsonPrimitive?.content.orEmpty()
    val redirectUrl = obj["redirectUrl"]?.jsonPrimitive?.content
    if (cookies.isEmpty() || userAgent.isBlank()) {
        null
    } else {
        CfSolveResult(cookies = cookies, userAgent = userAgent, redirectUrl = redirectUrl)
    }
}.getOrNull()
