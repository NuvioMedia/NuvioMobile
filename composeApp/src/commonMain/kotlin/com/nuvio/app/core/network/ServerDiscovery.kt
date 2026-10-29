package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ServerDiscoveryFailure {
    InvalidUrl,
    OfficialServer,
    ConnectionFailed,
    HttpError,
    ResponseTooLarge,
    InvalidDocument,
    UnsupportedVersion,
    WrongService,
    NotSelfHosted,
    MissingConfiguration,
    UnsupportedAuthentication,
}

class ServerDiscoveryException(
    val failure: ServerDiscoveryFailure,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

@Serializable
private data class DiscoveryDocument(
    val version: Int,
    val service: String,
    @SerialName("self_hosted") val selfHosted: Boolean,
    @SerialName("backend_url") val backendUrl: String,
    @SerialName("publishable_key") val publishableKey: String,
    val capabilities: DiscoveryCapabilities,
)

@Serializable
private data class DiscoveryCapabilities(
    @SerialName("email_password_auth") val emailPasswordAuth: Boolean = false,
    @SerialName("tv_login") val tvLogin: Boolean = false,
    @SerialName("watch_party") val watchParty: Boolean = false,
)

internal object ServerDiscoveryPolicy {
    private const val discoveryPath = "/.well-known/nuvio"
    private const val canonicalOfficialBackend = "https://api.nuvio.tv"
    private val json = Json { ignoreUnknownKeys = true }

    fun discoveryUrl(input: String): String {
        val normalized = input.trim()
        if (normalized.isBlank()) throw ServerDiscoveryException(ServerDiscoveryFailure.InvalidUrl)
        val withScheme = if (normalized.contains("://")) normalized else "https://$normalized"
        val parsed = parseHttpUrl(withScheme, ServerDiscoveryFailure.InvalidUrl)
        if (authorityOf(withScheme).contains('@')) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.InvalidUrl)
        }
        val basePath = parsed.encodedPath.removeSuffix(discoveryPath).trimEnd('/')
        val resolvedPath = if (basePath.isBlank()) discoveryPath else "$basePath$discoveryPath"
        return originOf(parsed) + resolvedPath
    }

    fun parse(discoveryUrl: String, document: String): ServerConfiguration {
        val payload = runCatching { json.decodeFromString<DiscoveryDocument>(document) }
            .getOrElse {
                throw ServerDiscoveryException(ServerDiscoveryFailure.InvalidDocument, cause = it)
            }
        if (payload.version != 1) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.UnsupportedVersion)
        }
        if (!payload.service.equals("nuvio", ignoreCase = true)) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.WrongService)
        }
        if (!payload.selfHosted) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.NotSelfHosted)
        }
        val backendUrl = normalizeBackendUrl(payload.backendUrl)
        val key = payload.publishableKey.trim()
        if (key.isBlank()) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.MissingConfiguration)
        }
        if (!payload.capabilities.emailPasswordAuth) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.UnsupportedAuthentication)
        }
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = key,
            capabilities = ServerCapabilities(
                emailPasswordAuth = payload.capabilities.emailPasswordAuth,
                tvLogin = payload.capabilities.tvLogin,
                watchParty = payload.capabilities.watchParty,
            ),
            isCustom = true,
            discoveryUrl = discoveryUrl,
        )
    }

    fun isOfficial(candidateUrl: String): Boolean =
        matchesBackend(candidateUrl, SupabaseConfig.URL) ||
            matchesBackend(candidateUrl, canonicalOfficialBackend)

    private fun matchesBackend(candidateUrl: String, backendUrl: String): Boolean {
        val candidate = runCatching { Url(candidateUrl) }.getOrNull() ?: return false
        val backend = runCatching { Url(backendUrl) }.getOrNull() ?: return false
        return candidate.host.equals(backend.host, ignoreCase = true) && candidate.port == backend.port
    }

    private fun normalizeBackendUrl(value: String): String {
        val normalized = value.trim()
        val parsed = parseHttpUrl(normalized, ServerDiscoveryFailure.MissingConfiguration)
        if (
            authorityOf(normalized).contains('@') ||
            parsed.parameters.entries().isNotEmpty() ||
            parsed.fragment.isNotBlank()
        ) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.MissingConfiguration)
        }
        return (originOf(parsed) + parsed.encodedPath).trimEnd('/')
    }

    private fun parseHttpUrl(value: String, failure: ServerDiscoveryFailure): Url {
        val parsed = runCatching { Url(value) }.getOrElse {
            throw ServerDiscoveryException(failure, cause = it)
        }
        if (parsed.protocol.name != "http" && parsed.protocol.name != "https") {
            throw ServerDiscoveryException(failure)
        }
        if (parsed.host.isBlank()) throw ServerDiscoveryException(failure)
        return parsed
    }

    private fun originOf(url: Url): String {
        val defaultPort = if (url.protocol.name == "https") 443 else 80
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        return "${url.protocol.name}://$host$port"
    }

    private fun authorityOf(value: String): String =
        value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
}

object ServerDiscoveryService {
    private const val requestTimeoutMs = 15_000L
    private const val maxDocumentBytes = 64 * 1024

    suspend fun discover(input: String): Result<ServerConfiguration> = runCatching {
        val discoveryUrl = ServerDiscoveryPolicy.discoveryUrl(input)
        if (ServerDiscoveryPolicy.isOfficial(discoveryUrl)) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.OfficialServer)
        }
        val response = try {
            withTimeout(requestTimeoutMs) {
                httpRequestRaw(
                    method = "GET",
                    url = discoveryUrl,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "User-Agent" to "Mobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}",
                    ),
                    body = "",
                    maxResponseBodyBytes = maxDocumentBytes + 1,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.ConnectionFailed, cause = error)
        }
        if (response.status !in 200..299) {
            throw ServerDiscoveryException(
                failure = ServerDiscoveryFailure.HttpError,
                statusCode = response.status,
            )
        }
        if (
            discoveryUrl.startsWith("https://", ignoreCase = true) &&
            !response.url.startsWith("https://", ignoreCase = true)
        ) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.ConnectionFailed)
        }
        if (response.body.encodeToByteArray().size > maxDocumentBytes) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.ResponseTooLarge)
        }
        ServerDiscoveryPolicy.parse(discoveryUrl, response.body)
    }.onFailure { if (it is CancellationException) throw it }
}
