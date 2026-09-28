package com.nuvio.app.core.deeplink

import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.player.infusePlaybackCallbacks
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
    ) : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String,
    ) : AppDeepLink

    data object Downloads : AppDeepLink
}

internal object AppDeepLinkRepository {
    private val _pendingDeepLink = MutableStateFlow<AppDeepLink?>(null)
    val pendingDeepLink: StateFlow<AppDeepLink?> = _pendingDeepLink.asStateFlow()

    fun handleUrl(url: String) {
        parseAppDeepLink(url)?.let { deepLink ->
            _pendingDeepLink.value = deepLink
        }
    }

    fun markConsumed(deepLink: AppDeepLink) {
        if (_pendingDeepLink.value == deepLink) {
            _pendingDeepLink.value = null
        }
    }
}

fun handleAppUrl(url: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return
    if (infusePlaybackCallbacks.handleUrl(normalizedUrl)) return

    ensureTrackingProvidersRegistered()
    TrackingProviderRegistry.handleAuthCallback(normalizedUrl)
    AppDeepLinkRepository.handleUrl(normalizedUrl)
}

fun buildMetaDeepLinkUrl(
    type: String,
    id: String,
): String = buildString {
    append("nuvio://meta?type=")
    append(type.trim().encodeURLParameter())
    append("&id=")
    append(id.trim().encodeURLParameter())
}

fun buildDownloadsDeepLinkUrl(): String = "nuvio://downloads"

internal fun parseAppDeepLink(url: String): AppDeepLink? {
    val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return null
    val scheme = parsedUrl.protocol.name.lowercase()
    if (scheme == "stremio") {
        return if (looksLikeAddonHost(parsedUrl.host.lowercase())) {
            customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
        } else {
            null
        }
    }
    if (scheme != "nuvio") return null

    val host = parsedUrl.host.lowercase()
    val pathSegments = parsedUrl.pathSegments.map(String::trim).filter(String::isNotBlank)
    return when (host) {
        "meta" -> {
            parseMetaFromParameters(parsedUrl)
                ?: parseMetaFromPath(pathSegments)
        }

        "detail", "details", "open", "watch" -> parseMetaFromPath(pathSegments)

        "movie", "movies", "series", "show", "shows", "tv" -> {
            val type = normalizeDeepLinkMediaType(host)
            val id = pathSegments.firstOrNull()?.let(::normalizeDeepLinkId).orEmpty()
            if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
        }

        "imdb", "tmdb" -> parseProviderMetaDeepLink(host, pathSegments, parsedUrl)

        "downloads" -> AppDeepLink.Downloads

        "auth" -> null

        else -> {
            if (looksLikeAddonHost(host)) {
                customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
            } else {
                null
            }
        }
    }
}

private fun parseMetaFromParameters(parsedUrl: Url): AppDeepLink.Meta? {
    val type = firstParameter(parsedUrl, "type", "mediaType", "media_type")
        ?.let(::normalizeDeepLinkMediaType)
        .orEmpty()
    val id = firstParameter(parsedUrl, "id", "imdb", "imdbId", "imdb_id")
        ?.let(::normalizeDeepLinkId)
        ?: firstParameter(parsedUrl, "tmdb", "tmdbId", "tmdb_id")
            ?.let { "tmdb:${it.removePrefix("tmdb:").trim()}" }
        ?: ""
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun parseMetaFromPath(pathSegments: List<String>): AppDeepLink.Meta? {
    if (pathSegments.size < 2) return null
    val type = normalizeDeepLinkMediaType(pathSegments[0])
    val id = normalizeDeepLinkId(pathSegments[1])
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun parseProviderMetaDeepLink(
    provider: String,
    pathSegments: List<String>,
    parsedUrl: Url,
): AppDeepLink.Meta? {
    val first = pathSegments.firstOrNull().orEmpty()
    val second = pathSegments.getOrNull(1).orEmpty()
    val firstAsType = normalizeDeepLinkMediaType(first)
    val queryType = firstParameter(parsedUrl, "type", "mediaType", "media_type")
        ?.let(::normalizeDeepLinkMediaType)
        .orEmpty()
    val type = firstAsType.ifBlank { queryType }
    val rawId = if (firstAsType.isNotBlank()) second else first
    val id = when (provider) {
        "tmdb" -> rawId.removePrefix("tmdb:").trim().takeIf(String::isNotBlank)?.let { "tmdb:$it" }
        else -> normalizeDeepLinkId(rawId).takeIf(String::isNotBlank)
    }.orEmpty()
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun firstParameter(parsedUrl: Url, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        parsedUrl.parameters[key]?.trim()?.takeIf(String::isNotBlank)
    }

private fun normalizeDeepLinkMediaType(value: String): String =
    when (value.trim().lowercase()) {
        "movie", "movies", "film", "films" -> "movie"
        "series", "show", "shows", "tv", "tvshow", "tvshows" -> "series"
        else -> ""
    }

private fun normalizeDeepLinkId(value: String): String =
    value.trim()
        .removePrefix("imdb:")
        .takeIf(String::isNotBlank)
        .orEmpty()

private fun looksLikeAddonHost(host: String): Boolean =
    host.contains('.') ||
        host.equals("localhost", ignoreCase = true) ||
        host.any(Char::isDigit)

private fun customSchemeToHttpsUrl(url: String, scheme: String): String? {
    val prefix = "$scheme://"
    val rest = url.trim()
        .takeIf { it.startsWith(prefix, ignoreCase = true) }
        ?.substring(prefix.length)
        ?.takeIf { it.isNotBlank() && !it.startsWith("/") }
        ?: return null
    return "https://$rest"
}
