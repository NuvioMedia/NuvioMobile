package com.nuvio.app.features.streams

private val credentialQueryKeys = setOf(
    "accesskey",
    "accesssignature",
    "accesssig",
    "access_token",
    "accesstoken",
    "apikey",
    "api_key",
    "auth",
    "authkey",
    "authsig",
    "authsignature",
    "auth_token",
    "authtoken",
    "e",
    "exp",
    "expiration",
    "expire",
    "expires",
    "expiresat",
    "expiresin",
    "expires_in",
    "expiry",
    "hmac",
    "jwt",
    "keypairid",
    "policy",
    "sig",
    "signature",
    "signed",
    "st",
    "t",
    "token",
)

private val credentialKeyFragments = listOf(
    "token",
    "signature",
    "expires",
    "expiry",
)

internal fun String.hasLikelyExpiringPlaybackCredentials(): Boolean {
    val query = substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
        .takeIf { it.isNotBlank() }
        ?: return false

    return query
        .split('&', ';')
        .any { rawParameter ->
            val rawKey = rawParameter
                .substringBefore('=', missingDelimiterValue = "")
                .trim()
                .lowercase()
            if (rawKey.isBlank()) return@any false

            val compactKey = rawKey
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")

            rawKey in credentialQueryKeys ||
                compactKey in credentialQueryKeys ||
                credentialKeyFragments.any { fragment ->
                    rawKey.contains(fragment) || compactKey.contains(fragment)
                }
        }
}
