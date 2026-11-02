package com.nuvio.app.features.trakt

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/**
 * How the app signs a user in to Trakt.
 *
 * [LOOPBACK_PKCE] is used when the configured redirect URI points at this device
 * (http://localhost or http://127.0.0.1) and the platform can listen for it: the
 * browser is sent to Trakt's authorize page with a PKCE challenge, and a one-shot
 * local listener receives the redirect.
 *
 * [DEVICE_CODE] is used otherwise: the app shows a short code that the user enters
 * at trakt.tv/activate while the app polls for the token.
 */
enum class TraktAuthFlow {
    DEVICE_CODE,
    LOOPBACK_PKCE,
}

internal const val TRAKT_AUTHORIZE_URL = "https://trakt.tv/oauth/authorize"
internal const val TRAKT_MAX_POLL_INTERVAL_SECONDS = 60
internal const val TRAKT_SLOW_DOWN_STEP_SECONDS = 5
internal const val TRAKT_LOOPBACK_TIMEOUT_MS = 10L * 60L * 1_000L

internal data class TraktLoopbackTarget(
    val port: Int,
    val path: String,
)

/** Returns the port and path to listen on when [redirectUri] is a plain-HTTP loopback address. */
internal fun parseTraktLoopbackRedirect(redirectUri: String): TraktLoopbackTarget? {
    val trimmed = redirectUri.trim()
    if (!trimmed.startsWith("http://localhost", ignoreCase = true) &&
        !trimmed.startsWith("http://127.0.0.1")
    ) {
        return null
    }
    val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
    val host = parsed.host.lowercase()
    if (host != "localhost" && host != "127.0.0.1") return null
    val port = parsed.port.takeIf { it in 1..65_535 } ?: return null
    val path = parsed.encodedPath.ifBlank { "/" }
    return TraktLoopbackTarget(port = port, path = path)
}

internal fun selectTraktAuthFlow(
    redirectUri: String,
    loopbackSupported: Boolean,
): TraktAuthFlow =
    if (loopbackSupported && parseTraktLoopbackRedirect(redirectUri) != null) {
        TraktAuthFlow.LOOPBACK_PKCE
    } else {
        TraktAuthFlow.DEVICE_CODE
    }

internal fun buildTraktAuthorizeUrl(
    clientId: String,
    redirectUri: String,
    state: String,
    codeChallenge: String,
): String = buildString {
    append(TRAKT_AUTHORIZE_URL)
    append("?response_type=code")
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&state=")
    append(state.encodeURLParameter())
    append("&code_challenge=")
    append(codeChallenge.encodeURLParameter())
    append("&code_challenge_method=S256")
}

internal sealed interface TraktLoopbackCallback {
    data class AuthorizationCode(val code: String, val state: String) : TraktLoopbackCallback
    data class Error(val error: String, val description: String?) : TraktLoopbackCallback
    data object Invalid : TraktLoopbackCallback
}

/** Parses the request target (path and query) the browser sent to the loopback listener. */
internal fun parseTraktLoopbackCallback(requestTarget: String): TraktLoopbackCallback {
    val parsed = runCatching { Url("http://localhost$requestTarget") }.getOrNull()
        ?: return TraktLoopbackCallback.Invalid
    val error = parsed.parameters["error"]?.trim().orEmpty()
    if (error.isNotBlank()) {
        return TraktLoopbackCallback.Error(
            error = error,
            description = parsed.parameters["error_description"]?.trim()?.takeIf(String::isNotBlank),
        )
    }
    val code = parsed.parameters["code"]?.trim().orEmpty()
    val state = parsed.parameters["state"]?.trim().orEmpty()
    if (code.isBlank() || state.isBlank()) return TraktLoopbackCallback.Invalid
    return TraktLoopbackCallback.AuthorizationCode(code = code, state = state)
}

/** What a response from POST /oauth/device/token means for the polling loop. */
internal sealed interface TraktDeviceTokenPollOutcome {
    data object Approved : TraktDeviceTokenPollOutcome
    data object Pending : TraktDeviceTokenPollOutcome
    data class SlowDown(val pollIntervalSeconds: Int) : TraktDeviceTokenPollOutcome
    data object InvalidCode : TraktDeviceTokenPollOutcome
    data object AlreadyUsed : TraktDeviceTokenPollOutcome
    data object Expired : TraktDeviceTokenPollOutcome
    data object Denied : TraktDeviceTokenPollOutcome
    data class Failed(val status: Int) : TraktDeviceTokenPollOutcome
}

/**
 * Maps a device-token response status to the next polling step, following Trakt's
 * device authentication contract: 400 pending, 404 invalid code, 409 already used,
 * 410 expired, 418 denied, 429 slow down.
 */
internal fun traktDeviceTokenPollOutcome(
    status: Int,
    currentIntervalSeconds: Int,
): TraktDeviceTokenPollOutcome = when {
    status in 200..299 -> TraktDeviceTokenPollOutcome.Approved
    status == 400 -> TraktDeviceTokenPollOutcome.Pending
    status == 404 -> TraktDeviceTokenPollOutcome.InvalidCode
    status == 409 -> TraktDeviceTokenPollOutcome.AlreadyUsed
    status == 410 -> TraktDeviceTokenPollOutcome.Expired
    status == 418 -> TraktDeviceTokenPollOutcome.Denied
    status == 429 -> TraktDeviceTokenPollOutcome.SlowDown(
        (currentIntervalSeconds.coerceAtLeast(1) + TRAKT_SLOW_DOWN_STEP_SECONDS)
            .coerceAtMost(TRAKT_MAX_POLL_INTERVAL_SECONDS),
    )
    else -> TraktDeviceTokenPollOutcome.Failed(status)
}

/** Whole minutes to wait after Trakt rate-limited a device-code request; five minutes when no Retry-After was sent. */
internal fun traktRateLimitedMinutes(retryAfterSeconds: Long?): Long =
    ((retryAfterSeconds?.coerceAtLeast(0L) ?: 300L) + 59L) / 60L

internal fun traktHeaderValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
