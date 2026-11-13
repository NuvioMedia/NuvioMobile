package com.nuvio.app.features.servers.silo

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
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Requests to Silo's own API (`/api/v2`). Failures keep Silo's problem code, which the sign-in and playback act on. */
internal class SiloClient(
    private val http: HttpClient,
    private val deviceIdProvider: () -> String = SyncClientIdentity::currentClientId,
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val deviceId: String
        get() = deviceIdProvider()

    /** "android" or "ios", as Silo's own apps name their platform. */
    val platform: String
        get() = if (isIos) "ios" else "android"

    val appVersion: String
        get() = AppVersionConfig.VERSION_NAME

    /** The name the server shows for this device in its list of signed-in devices. */
    val deviceName: String
        get() = "Nuvio on ${if (isIos) "iOS" else "Android"}"

    val deviceHeaders: Map<String, String>
        get() = mapOf(
            "X-Device-ID" to deviceId,
            "X-Silo-Device-Id" to deviceId,
            "X-Client-Name" to "Nuvio",
            "X-Client-Version" to appVersion.take(MAX_HEADER),
        )

    suspend fun execute(
        method: HttpMethod,
        baseUrl: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        allowRedirect: Boolean = false,
    ): SiloResponse {
        val url = try {
            buildUrl(baseUrl, path, query)
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val extra = headers
        val response = try {
            http.prepareRequest(url) {
                this.method = method
                header("Accept", "application/json")
                header("User-Agent", deviceName.headerValue())
                extra.forEach { (name, value) -> header(name, value.headerValue()) }
                if (body != null) {
                    header("Content-Type", "application/json")
                    setBody(body)
                }
            }.execute { response ->
                SiloResponse(
                    status = response.status.value,
                    body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                    location = response.headers["Location"],
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        if (response.status in 200..299 || allowRedirect && response.status in 300..399) return response
        throw SiloProblem.from(response, json).toServerException()
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(deserializer, body)
    } catch (_: SerializationException) {
        throw ServerException(ServerFailure.FAILED)
    } catch (_: IllegalArgumentException) {
        throw ServerException(ServerFailure.FAILED)
    }

    private companion object {
        const val MAX_HEADER = 64
    }
}

internal class SiloResponse(val status: Int, val body: String, val location: String?) {
    override fun toString(): String = "SiloResponse(status=$status)"
}

/** A refused request: the HTTP status and Silo's problem code, when it sent one. */
internal class SiloProblem(val status: Int, val code: String?) {
    fun toServerException(): ServerException {
        val failure = when {
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        return ServerException(failure, "$PREFIX$status ${code.orEmpty()}".trim())
    }

    companion object {
        private const val PREFIX = "Silo "

        fun from(response: SiloResponse, json: Json): SiloProblem {
            val code = try {
                json.decodeFromString(ProblemWire.serializer(), response.body).type
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            return SiloProblem(response.status, code)
        }

        /** The status and code a [ServerException] thrown for a Silo reply carries; null for any other failure. */
        fun of(error: ServerException): SiloProblem? {
            val text = error.message?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val status = text.substringBefore(' ').toIntOrNull() ?: return null
            return SiloProblem(status, text.substringAfter(' ', "").takeIf { it.isNotBlank() })
        }
    }
}

/** An RFC 9457 problem reply; Silo names the error in the last part of its type. */
@Serializable
private data class ProblemWire(val type: String? = null)

internal val ServerException.siloStatus: Int?
    get() = SiloProblem.of(this)?.status

internal val ServerException.siloCode: String?
    get() = SiloProblem.of(this)?.code

private fun String.headerValue(): String = filter { it in ' '..'~' }
