package com.nuvio.app.features.usenet

import kotlinx.serialization.Serializable

/** Device-local engine tuning; never contains provider credentials or NZB URLs. */
@Serializable
data class UsenetConfiguration(
    val profile: String = PROFILE_BALANCED,
    val readAhead: Int = 0,
    val maxConnections: Int = 0,
    val prewarmOnLaunch: Boolean = false,
    val fastMkvStartup: Boolean = true,
    val fastNzbFetch: Boolean = true,
    val prefetchResults: Boolean = false,
    val cacheNzb: Boolean = true,
    val allowPrivateNetwork: Boolean = false,
    val holeFilling: Boolean = false,
    val maxMissingArticles: Int = 5,
    val maxConsecutiveMissing: Int = 2,
    /** Blank means [NUVIO_USER_AGENT]; only a User-Agent the user chose is stored. */
    val searchUserAgent: String = "",
    /** Blank means [NUVIO_USER_AGENT]; independent of the search User-Agent. */
    val downloadUserAgent: String = "",
    /** When a Usenet release fails to open or play, try the following Usenet releases. */
    val fallbackEnabled: Boolean = false,
    /** How many following releases automatic fallback tries for one selection. */
    val fallbackMaxAttempts: Int = 5,
) {
    /** Sent by built-in indexer searches, capability checks and connection tests. */
    val searchAgent: String get() = searchUserAgent.trim().ifEmpty { NUVIO_USER_AGENT }

    /** Sent by NZB downloads, unless an addon supplies its own User-Agent. */
    val downloadAgent: String get() = downloadUserAgent.trim().ifEmpty { NUVIO_USER_AGENT }

    /** Within the ranges the engine accepts; saving Nuvio's own User-Agent keeps the automatic one. */
    fun sanitized(): UsenetConfiguration {
        fun customAgent(agent: String) = agent.trim().takeUnless { it == NUVIO_USER_AGENT }
            ?.takeIf(::isValidUserAgent).orEmpty()
        val missing = maxMissingArticles.coerceIn(1, 50)
        return copy(
            profile = profile.takeIf { it in PROFILES } ?: PROFILE_BALANCED,
            readAhead = readAhead.coerceIn(0, 512),
            maxConnections = maxConnections.coerceIn(0, 4096),
            maxMissingArticles = missing,
            maxConsecutiveMissing = maxConsecutiveMissing.coerceIn(1, minOf(10, missing)),
            searchUserAgent = customAgent(searchUserAgent),
            downloadUserAgent = customAgent(downloadUserAgent),
            fallbackMaxAttempts = fallbackMaxAttempts.coerceIn(1, 50),
        )
    }

    companion object {
        const val PROFILE_LOW_MEMORY = "low-memory"
        const val PROFILE_BALANCED = "balanced"
        const val PROFILE_THROUGHPUT = "throughput"
        val PROFILES = listOf(PROFILE_LOW_MEMORY, PROFILE_BALANCED, PROFILE_THROUGHPUT)
    }
}
