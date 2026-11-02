package com.nuvio.app.features.simkl

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal const val SIMKL_AUTHORIZATION_TIMEOUT_MS = 5L * 60L * 1_000L

internal fun parseSimklAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): SimklAuthCallback {
    if (callbackUrl != redirectUri && !callbackUrl.startsWith("$redirectUri?")) {
        return SimklAuthCallback.NotSimkl
    }
    val parsed = runCatching { Url(callbackUrl) }.getOrNull() ?: return SimklAuthCallback.Invalid
    val code = parsed.parameters["code"].orEmpty().trim()
    val state = parsed.parameters["state"].orEmpty().trim()
    if (code.isBlank() || state.isBlank()) return SimklAuthCallback.Invalid
    return SimklAuthCallback.AuthorizationCode(code = code, state = state)
}

internal fun isSimklAuthorizationExpired(
    startedAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = startedAtEpochMs == null ||
    nowEpochMs < startedAtEpochMs ||
    nowEpochMs - startedAtEpochMs > SIMKL_AUTHORIZATION_TIMEOUT_MS

internal fun constantTimeEquals(left: String, right: String): Boolean {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val length = maxOf(leftBytes.size, rightBytes.size)
    var difference = leftBytes.size xor rightBytes.size
    for (index in 0 until length) {
        val leftByte = leftBytes.getOrElse(index) { 0 }.toInt()
        val rightByte = rightBytes.getOrElse(index) { 0 }.toInt()
        difference = difference or (leftByte xor rightByte)
    }
    return difference == 0
}

internal fun buildSimklAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    material: SimklPkceMaterial,
    scope: String = SIMKL_OAUTH_SCOPE,
): String = buildString {
    append(SIMKL_AUTHORIZE_URL)
    append("?response_type=code")
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&scope=")
    append(scope.encodeURLParameter())
    append("&state=")
    append(material.state.encodeURLParameter())
    append("&code_challenge=")
    append(material.challenge.encodeURLParameter())
    append("&code_challenge_method=S256")
}
