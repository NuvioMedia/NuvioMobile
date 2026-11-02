package com.nuvio.app.features.simkl

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Simkl AUTH V2 token endpoint (always on the API host; the consent page is on simkl.com). */
internal const val SIMKL_TOKEN_URL = "$SIMKL_API_BASE_URL/oauth2/token"

/** Omitting scope yields a read-only token; writes (scrobble, sync, any request with a body) need media:write. */
internal const val SIMKL_OAUTH_SCOPE = "media:read media:write"

/** Refresh this long before the stored expiry so in-flight requests never carry a dead token. */
internal const val SIMKL_TOKEN_REFRESH_SKEW_MS = 5L * 60L * 1_000L

/** Access token, refresh token and expiry are always swapped together as one immutable value. */
internal data class SimklTokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
) {
    val canRefresh: Boolean
        get() = !refreshToken.isNullOrBlank()
}

internal fun simklAuthorizationCodeForm(
    clientId: String,
    code: String,
    codeVerifier: String,
    redirectUri: String,
): Map<String, String> = linkedMapOf(
    "grant_type" to "authorization_code",
    "client_id" to clientId,
    "code" to code,
    "code_verifier" to codeVerifier,
    "redirect_uri" to redirectUri,
)

internal fun simklRefreshTokenForm(
    clientId: String,
    refreshToken: String,
): Map<String, String> = linkedMapOf(
    "grant_type" to "refresh_token",
    "client_id" to clientId,
    "refresh_token" to refreshToken,
)

internal fun encodeSimklForm(parameters: Map<String, String>): String =
    parameters.entries.joinToString("&") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }

internal fun simklTokenRequestHeaders(): Map<String, String> =
    simklRequestHeaders() + ("Content-Type" to "application/x-www-form-urlencoded")

internal enum class SimklTokenRefreshDecision {
    NO_TOKEN,
    USE_CURRENT,
    REFRESH,
}

/**
 * Tokens stored before the AUTH V2 migration have no refresh token; they keep being used
 * until the API rejects them, at which point the user is asked to reconnect.
 */
internal fun simklTokenRefreshDecision(
    tokens: SimklTokenSet?,
    nowEpochMs: Long,
    skewMs: Long = SIMKL_TOKEN_REFRESH_SKEW_MS,
): SimklTokenRefreshDecision {
    if (tokens == null || tokens.accessToken.isBlank()) return SimklTokenRefreshDecision.NO_TOKEN
    if (!tokens.canRefresh) return SimklTokenRefreshDecision.USE_CURRENT
    val expiresAt = tokens.expiresAtEpochMs ?: return SimklTokenRefreshDecision.USE_CURRENT
    return if (nowEpochMs >= expiresAt - skewMs) {
        SimklTokenRefreshDecision.REFRESH
    } else {
        SimklTokenRefreshDecision.USE_CURRENT
    }
}

internal fun resolveSimklStoredTokens(
    accessToken: String?,
    refreshToken: String?,
    state: SimklStoredAuthState,
): SimklTokenSet? {
    val access = accessToken?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return SimklTokenSet(
        accessToken = access,
        refreshToken = refreshToken?.trim()?.takeIf(String::isNotEmpty),
        expiresAtEpochMs = state.tokenExpiresAtEpochMs,
    )
}

@Serializable
internal data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
internal data class SimklOAuthError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val message: String? = null,
)

internal sealed interface SimklTokenResult {
    data class Success(val token: SimklTokenResponse) : SimklTokenResult

    /** The grant (code or refresh token) is dead; the user must authorize again. */
    data class InvalidGrant(val error: String?) : SimklTokenResult

    /** Network failure, 5xx, malformed body or any other error worth retrying later. */
    data class Failed(val status: Int?, val error: String?) : SimklTokenResult
}

internal fun parseSimklTokenResult(status: Int, body: String, json: Json): SimklTokenResult {
    if (status in 200..299) {
        val token = runCatching { json.decodeFromString<SimklTokenResponse>(body) }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() }
        return token?.let(SimklTokenResult::Success) ?: SimklTokenResult.Failed(status, "invalid_token_response")
    }
    val error = runCatching { json.decodeFromString<SimklOAuthError>(body) }.getOrNull()?.error
    return if (status == 400 && error == "invalid_grant") {
        SimklTokenResult.InvalidGrant(error)
    } else {
        SimklTokenResult.Failed(status, error)
    }
}

internal fun SimklTokenResponse.toTokenSet(
    nowEpochMs: Long,
    previousRefreshToken: String? = null,
): SimklTokenSet = SimklTokenSet(
    accessToken = accessToken,
    refreshToken = refreshToken?.takeIf(String::isNotBlank) ?: previousRefreshToken,
    expiresAtEpochMs = expiresIn
        ?.takeIf { seconds -> seconds > 0L }
        ?.let { seconds -> nowEpochMs + seconds * 1_000L },
)
