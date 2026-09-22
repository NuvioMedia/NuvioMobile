package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

class MdbListAuthRepository(
    private val http: MdbListHttpClient,
    private val configuration: MdbListConfiguration,
    private val store: MdbListAuthStore,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startDeviceAuthorization(scope: MdbListAuthScope = store.scope()): MdbListDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        if (!hasRequiredCredentials()) throw MdbListAuthException(MdbListAuthError.MISSING_CLIENT_ID)
        check(!state.value.isAuthenticated)
        val response = oauth("/oauth/device-authorization/", mapOf("scope" to "write"), scope)
        if (response.status !in 200..299) throw MdbListApiException(response.status, response.errorCode())
        val payload = decode<MdbListDeviceResponse>(response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val uri = verificationUrl(payload.verificationUri)
        val complete = payload.verificationUriComplete?.let(::verificationUrl)
            ?: URLBuilder(uri).apply { parameters.append("user_code", userCode) }.build()
        val interval = payload.interval.takeIf { it in 1..3_600 } ?: invalidResponse()
        val now = nowEpochMs()
        val session = MdbListDeviceSession(
            userCode = userCode,
            verificationUri = uri.toString(),
            verificationUriComplete = complete.toString(),
            expiresAtEpochMs = expiresAt(payload.expiresIn, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: MdbListAuthScope = store.scope()): MdbListDevicePollResult = deviceMutex.withLock {
        store.checkScope(scope)
        val current = state.value
        val session = current.session ?: scopeChanged()
        val now = nowEpochMs()
        if (now >= session.expiresAtEpochMs) {
            store.cancelSession(MdbListAuthError.CODE_EXPIRED, scope)
            return@withLock MdbListDevicePollResult.Expired
        }
        if (now < session.nextPollAtEpochMs) return@withLock MdbListDevicePollResult.Pending
        val deviceCode = store.deviceCode(scope) ?: scopeChanged()
        if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) {
            scopeChanged()
        }
        val response = oauth(
            "/oauth/token/",
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to deviceCode, "scope" to "write"),
            scope
        )
        if (response.status in 200..299) {
            val tokens = tokensFrom(response)
            if (!store.authorize(tokens, scope)) scopeChanged()
            return@withLock MdbListDevicePollResult.Authorized
        }
        when (response.errorCode()) {
            "authorization_pending" -> MdbListDevicePollResult.Pending
            "slow_down" -> {
                val interval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                store.updateSession(
                    session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L),
                    scope
                )
                MdbListDevicePollResult.Pending
            }
            "access_denied" -> {
                store.cancelSession(MdbListAuthError.ACCESS_DENIED, scope)
                MdbListDevicePollResult.Denied
            }
            "expired_token" -> {
                store.cancelSession(MdbListAuthError.CODE_EXPIRED, scope)
                MdbListDevicePollResult.Expired
            }
            else -> throw MdbListApiException(response.status, response.errorCode())
        }
    }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    suspend fun authorization(
        scope: MdbListAuthScope,
        rejectedAccessToken: String? = null
    ): MdbListAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
        if (rejectedAccessToken != null && current.tokens.accessToken != rejectedAccessToken) return@withLock current
        if (rejectedAccessToken == null && current.tokens.expiresAtEpochMs - nowEpochMs() > 60_000L) {
            return@withLock current
        }
        val response = oauth(
            "/oauth/token/",
            mapOf("grant_type" to "refresh_token", "refresh_token" to current.tokens.refreshToken),
            scope
        )
        if (response.status !in 200..299) {
            if (response.errorCode() in setOf("invalid_grant", "invalid_token")) {
                store.clearAuth(scope, MdbListAuthError.AUTHORIZATION_REVOKED, current.tokens.accessToken)
                throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
            }
            throw MdbListApiException(response.status, response.errorCode())
        }
        val tokens = tokensFrom(response, current.tokens.refreshToken)
        if (!store.refreshTokens(tokens, current)) scopeChanged()
        MdbListAuthorization(scope, tokens)
    }

    suspend fun disconnect(scope: MdbListAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (current != null && current.scope != scope) scopeChanged()
        if (!store.clearAuth(scope)) scopeChanged()
        if (current == null) return true
        return try {
            val response = http.execute(
                MdbListHttpRequest(
                    method = MdbListHttpMethod.POST,
                    path = "/oauth/revoke_token/",
                    form = mapOf(
                        "client_id" to configuration.clientId,
                        "token" to current.tokens.refreshToken,
                        "token_type_hint" to "refresh_token"
                    )
                )
            )
            response.status in 200..299
        } catch (error: CancellationException) {
            throw error
        } catch (_: MdbListApiException) {
            false
        }
    }

    private suspend fun oauth(path: String, fields: Map<String, String>, scope: MdbListAuthScope) =
        http.execute(
            MdbListHttpRequest(
                method = MdbListHttpMethod.POST,
                path = path,
                form = fields + ("client_id" to configuration.clientId),
                limitKey = "oauth:${scope.profileId}"
            ),
            checkScope = { store.checkScope(scope) }
        )

    private fun tokensFrom(response: MdbListHttpResponse, previousRefreshToken: String? = null): MdbListTokens {
        val payload = decode<MdbListTokenResponse>(response.body)
        val accessToken = payload.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refreshToken = payload.refreshToken?.takeIf(String::isNotBlank) ?: previousRefreshToken ?: invalidResponse()
        if (!payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        if (payload.scope != null && "write" !in payload.scope.split(Regex("\\s+"))) {
            throw MdbListAuthException(MdbListAuthError.INSUFFICIENT_SCOPE)
        }
        return MdbListTokens(accessToken, refreshToken, expiresAt(payload.expiresIn, nowEpochMs()))
    }

    private fun verificationUrl(value: String?): Url = value?.let { runCatching { Url(it) }.getOrNull() }
        ?.takeIf { it.protocol == URLProtocol.HTTPS && it.host == "mdblist.com" &&
            it.port == 443 && it.user.isNullOrEmpty() && it.password.isNullOrEmpty() }
        ?: invalidResponse()

    private fun expiresAt(seconds: Long?, now: Long): Long = seconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw MdbListAuthException(MdbListAuthError.INVALID_RESPONSE)
    private fun scopeChanged(): Nothing = throw CancellationException("MDBList account changed")
}
