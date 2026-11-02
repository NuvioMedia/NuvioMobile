package com.nuvio.app.features.updater

internal object UpdateChecksum {
    private val hex64 = Regex("^[0-9a-f]{64}$")

    /** GitHub reports an uploaded file as "sha256:<64 hex>"; anything else (or nothing) means no check. */
    fun fromDigest(digest: String?): String? {
        val value = digest?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
        return value.takeIf { hex64.matches(it) }
    }

    fun matches(expected: String?, actual: String): Boolean =
        expected == null || expected.equals(actual, ignoreCase = true)
}
