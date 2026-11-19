package com.nuvio.app.features.servers.plex

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.mediabrowser.buildUrl
import com.nuvio.app.isIos
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Requests to plex.tv and to Plex Media Server, with the X-Plex-* headers both expect. */
internal class PlexClient(
    private val http: HttpClient,
    private val clientIdProvider: () -> String = SyncClientIdentity::currentClientId,
) {
    /** Plex replies differ between versions: a tag id is a number in some and a string in others. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    val clientId: String
        get() = clientIdProvider()

    val headers: Map<String, String>
        get() {
            val platform = if (isIos) "iOS" else "Android"
            return mapOf(
                "X-Plex-Product" to PRODUCT,
                "X-Plex-Version" to AppVersionConfig.VERSION_NAME.take(MAX_HEADER),
                "X-Plex-Client-Identifier" to clientId,
                "X-Plex-Platform" to platform,
                "X-Plex-Device" to platform,
                "X-Plex-Device-Name" to "$PRODUCT on $platform",
                "X-Plex-Provides" to "player",
            )
        }

    suspend fun execute(
        method: HttpMethod,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        range: LongRange? = null,
        accepted: Set<Int> = emptySet(),
    ): PlexResponse {
        val url = try {
            buildUrl(baseUrl, path, query)
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val response = try {
            http.prepareRequest(url) {
                this.method = method
                header("Accept", "application/json")
                (this@PlexClient.headers + extraHeaders).forEach { (name, value) -> header(name, value.headerValue()) }
                token?.let { header(TOKEN_HEADER, it.headerValue()) }
                range?.let { header("Range", "bytes=${it.first}-${it.last}") }
            }.execute { response ->
                val body = if (range == null) {
                    readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
                } else {
                    ""
                }
                PlexResponse(response.status.value, body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        val failure = when {
            response.status in 200..299 || response.status in accepted -> return response
            response.status == 401 -> ServerFailure.AUTH_REQUIRED
            response.status == 403 -> ServerFailure.FORBIDDEN
            response.status == 404 -> ServerFailure.NOT_FOUND
            response.status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP ${response.status}")
    }

    companion object {
        const val PRODUCT = "Nuvio"
        const val TOKEN_HEADER = "X-Plex-Token"
        private const val MAX_HEADER = 64
    }
}

internal class PlexResponse(val status: Int, val body: String) {
    override fun toString(): String = "PlexResponse(status=$status)"
}

private fun String.headerValue(): String = filter { it in ' '..'~' }
