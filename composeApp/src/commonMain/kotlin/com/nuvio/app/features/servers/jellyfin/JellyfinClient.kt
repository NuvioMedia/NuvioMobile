package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.getPlatform
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

internal class JellyfinClient(
    private val http: HttpClient = createApiHttpClient(),
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val deviceId: String
        get() = SyncClientIdentity.currentClientId()

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        token: String? = null,
        query: Map<String, String?> = emptyMap(),
    ): T = json.decodeFromString(deserializer, execute(HttpMethod.Get, baseUrl, path, token, query).body)

    suspend fun execute(
        method: HttpMethod,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        allowRedirect: Boolean = false,
    ): JellyfinResponse {
        val response = try {
            http.prepareRequest(buildUrl(baseUrl, path, query)) {
                this.method = method
                header("Accept", "application/json")
                header("Authorization", authorizationHeader(token))
                if (body != null) {
                    header("Content-Type", "application/json")
                    setBody(body)
                }
            }.execute { response ->
                JellyfinResponse(
                    status = response.status.value,
                    body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                    location = response.headers["Location"],
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        if (allowRedirect && response.status in 300..399) return response
        throwForStatus(response.status)
        return response
    }

    fun authorizationHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"Nuvio\", Device=\"")
        append(getPlatform().name.headerValue())
        append("\", DeviceId=\"")
        append(deviceId.headerValue())
        append("\", Version=\"")
        append(AppVersionConfig.VERSION_NAME.headerValue())
        append('"')
        if (token != null) {
            append(", Token=\"")
            append(token.headerValue())
            append('"')
        }
    }

    private fun throwForStatus(status: Int) {
        val failure = when {
            status in 200..299 -> return
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP $status")
    }
}

internal class JellyfinResponse(
    val status: Int,
    val body: String,
    val location: String?,
) {
    override fun toString(): String = "JellyfinResponse(status=$status)"
}

internal fun buildUrl(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): String {
    require(path.startsWith('/'))
    val base = Url(baseUrl)
    require(base.protocol == URLProtocol.HTTP || base.protocol == URLProtocol.HTTPS)
    return URLBuilder(base).apply {
        encodedPath = base.encodedPath.trimEnd('/') + path
        parameters.clear()
        fragment = ""
        query.forEach { (key, value) -> if (value != null) parameters.append(key, value) }
    }.buildString()
}

internal fun pathSegment(value: String): String = value.encodeURLPathPart()

internal fun normalizeServerAddress(input: String): String? {
    var value = input.trim().trimEnd('/')
    if (value.isEmpty()) return null
    if (!value.contains("://")) value = "http://$value"
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
    if (url.host.isBlank() || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
    val path = url.encodedPath.trimEnd('/')
        .removeSuffix("/web/index.html")
        .removeSuffix("/web")
        .trimEnd('/')
    return URLBuilder(url).apply {
        encodedPath = path
        parameters.clear()
        fragment = ""
    }.buildString().trimEnd('/')
}

private fun String.headerValue(): String = filter { it != '"' && it != '\r' && it != '\n' }
