package com.nuvio.app.features.servers.plex

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.mediabrowser.buildUrl
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodedPath

/**
 * Plex artwork needs the server token. Links the app keeps (rows, Continue watching, watch history) only carry a mark
 * in their fragment naming the connection, so no token is saved or synced; it is added when the picture is downloaded.
 */
internal object PlexArtworkLink {
    private const val MARK = "nuvio-plex="
    internal const val PHOTO_PATH = "/photo/:/transcode"

    /** A resized copy of [path] (a server path such as /library/metadata/1/thumb/2) from the server's picture service. */
    fun picture(address: String, connectionId: String, path: String, width: Int, height: Int): String? {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
        if (!path.startsWith("/")) return null
        val url = runCatching {
            buildUrl(
                address,
                PHOTO_PATH,
                mapOf(
                    "width" to width.toString(),
                    "height" to height.toString(),
                    "minSize" to "1",
                    "upscale" to "1",
                    "url" to path,
                ),
            )
        }.getOrNull() ?: return null
        return "$url#$MARK${connectionId.encodeURLParameter()}"
    }

    fun connectionId(url: String): String? {
        val value = url.substringAfter('#', "").split('&').firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK)
            ?: return null
        return runCatching { value.decodeURLQueryComponent() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The link to download: pointed at the connection's current address (the server may have moved since the link was
     * saved) with the token added. The token goes in the query, so a redirect to another host never carries it.
     */
    fun signed(url: String, address: String, token: String): String? {
        val link = runCatching { Url(url.substringBefore('#')) }.getOrNull() ?: return null
        if (!link.encodedPath.endsWith(PHOTO_PATH)) return null
        val server = runCatching { Url(address) }.getOrNull() ?: return null
        return URLBuilder(server).apply {
            encodedPath = server.encodedPath.trimEnd('/') + PHOTO_PATH
            parameters.clear()
            link.parameters.entries().forEach { (name, values) ->
                if (!name.equals(PlexClient.TOKEN_HEADER, ignoreCase = true)) parameters.appendAll(name, values)
            }
            parameters.append(PlexClient.TOKEN_HEADER, token)
            fragment = ""
        }.buildString()
    }
}

/** Signs marked Plex artwork links as they are downloaded. The disk cache keeps the unsigned link as its key. */
internal class PlexArtworkInterceptor(
    private val access: (String) -> PlexArtworkAccess? = ::plexArtworkAccess,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val connectionId = PlexArtworkLink.connectionId(url) ?: return chain.proceed()
        val server = access(connectionId) ?: return chain.proceed()
        val signed = PlexArtworkLink.signed(url, server.address, server.token) ?: return chain.proceed()
        val signedRequest = request.newBuilder()
            .data(signed)
            .diskCacheKey(request.diskCacheKey ?: url)
            .build()
        return chain.withRequest(signedRequest).proceed()
    }
}

/** The address and token a marked Plex link is fetched with. */
internal class PlexArtworkAccess(val address: String, val token: String) {
    override fun toString(): String = "PlexArtworkAccess(address=$address)"
}

private fun plexArtworkAccess(connectionId: String): PlexArtworkAccess? =
    ServerRepository.session(connectionId)
        ?.takeIf { it.connection.providerId == PlexProvider.ID }
        ?.let { PlexArtworkAccess(it.connection.address, it.token) }
