package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerCapabilities(
    val emailPasswordAuth: Boolean,
    val tvLogin: Boolean,
    val watchParty: Boolean = false,
)

data class ServerConfiguration(
    val backendUrl: String,
    val publishableKey: String,
    val capabilities: ServerCapabilities,
    val isCustom: Boolean,
    val discoveryUrl: String? = null,
    val fallbackBackendUrl: String? = null,
) {
    val isSecure: Boolean
        get() = backendUrl.startsWith("https://", ignoreCase = true)

    val isPublicHost: Boolean
        get() = isPublicServerHost(backendUrl)
}

object ServerConfigurationRepository {
    private val _active = MutableStateFlow(loadActiveConfiguration())
    val active: StateFlow<ServerConfiguration> = _active.asStateFlow()

    fun saveCustom(configuration: ServerConfiguration): Boolean {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return false
        if (!ServerConfigurationStorage.saveCustom(configuration)) return false
        _active.value = configuration
        return true
    }

    fun useOfficial(): Boolean {
        if (!ServerConfigurationStorage.useOfficial()) return false
        _active.value = officialConfiguration()
        return true
    }

    private fun loadActiveConfiguration(): ServerConfiguration {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return officialConfiguration()
        return ServerConfigurationStorage.loadCustom() ?: officialConfiguration()
    }
}

internal fun officialConfiguration() = ServerConfiguration(
    backendUrl = SupabaseConfig.URL.trim().trimEnd('/'),
    publishableKey = SupabaseConfig.ANON_KEY.trim(),
    capabilities = ServerCapabilities(
        emailPasswordAuth = true,
        tvLogin = true,
        watchParty = true,
    ),
    isCustom = false,
    fallbackBackendUrl = SupabaseConfig.FALLBACK_URL.trim().trimEnd('/').takeIf { it.isNotBlank() },
)

internal fun isPublicServerHost(url: String): Boolean {
    val host = runCatching { io.ktor.http.Url(url).host.lowercase() }.getOrNull() ?: return true
    if (host == "localhost" || host.endsWith(".local") || host == "::1") return false
    if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return false
    val parts = host.split('.')
    if (parts.size == 4) {
        val first = parts[0].toIntOrNull()
        val second = parts[1].toIntOrNull()
        if (first == 172 && second != null && second in 16..31) return false
        if (first == 169 && second == 254) return false
    }
    return true
}
