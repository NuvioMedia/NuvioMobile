package com.nuvio.app.features.usenet

import com.nuvio.app.core.build.AppVersionConfig
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * How Nuvio names itself to indexers and NZB servers. Built from the installed version, so it
 * follows every update.
 */
internal val NUVIO_USER_AGENT: String
    get() = "Nuvio/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"

internal const val USER_AGENT = "User-Agent"

/** An HTTP header value: printable ASCII on one line. */
internal fun isValidUserAgent(value: String) = value.length <= 256 && value.all { it in ' '..'~' }

@OptIn(ExperimentalUuidApi::class)
internal fun newUsenetSourceId(): String = Uuid.random().toString()

@Serializable
data class UsenetProvider(
    val id: String = newUsenetSourceId(),
    val name: String = "",
    val host: String = "",
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String = "",
    val connections: Int = 20,
    val enabled: Boolean = true,
    /** Equal priorities share article traffic; lower priorities only fill missing articles. */
    val priority: Int = 1,
) {
    /** The provider as the engine is given it: credentials, connection allowance and priority in one URL. */
    fun serverUrl(): String {
        val authority = if (host.contains(':')) "[${host.removeSurrounding("[", "]")}]" else host
        val credentials = if (username.isNotEmpty() || password.isNotEmpty()) {
            "${encodeUrlComponent(username)}:${encodeUrlComponent(password)}@"
        } else {
            ""
        }
        return "${if (tls) "nntps" else "nntp"}://$credentials$authority:$port/$connections?priority=$priority"
    }

    override fun toString() = "UsenetProvider(id=$id, enabled=$enabled)"
}

@Serializable
data class UsenetIndexer(
    val id: String = newUsenetSourceId(),
    val name: String = "",
    /** The indexer's URL; [apiPath] is appended to it. */
    val apiUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    /** Equal priorities are searched together; lower priorities only when higher ones find nothing. */
    val priority: Int = 1,
    /**
     * Path of the Newznab API under [apiUrl], usually /api, which new indexers start with. A URL that
     * already ends with it is used as is; an empty path uses the URL as the complete endpoint.
     */
    val apiPath: String = "",
    /** Null uses the global fetch setting; zero reads all pages. Independent of the display limit. */
    val fetchResultsLimit: Int? = null,
) {
    override fun toString() = "UsenetIndexer(id=$id, enabled=$enabled)"
}

@Serializable
enum class UsenetSort { QUALITY, LARGEST, SMALLEST, NEWEST, INDEXER }

@Serializable
data class UsenetSourceConfiguration(
    val enabled: Boolean = false,
    /** Set once the notice shown before the first switch-on in this profile was accepted. */
    val firstUseAccepted: Boolean = false,
    val providers: List<UsenetProvider> = emptyList(),
    val indexers: List<UsenetIndexer> = emptyList(),
    val sort: UsenetSort = UsenetSort.QUALITY,
    /** Missing in existing settings: retain their exact ordering until a profile is chosen. */
    val ranking: StreamRankingSettings = StreamRankingSettings(),
    val minResolution: Int = 0,
    val maxSizeGb: Int = 0,
    val maxAgeDays: Int = 0,
    /** [UNLIMITED_RESULTS] lists every result. */
    val maxResults: Int = 50,
    /**
     * Default fetch allowance for indexers without an override. [AUTO_INDEXER_RESULTS] follows
     * [maxResults]; a number caps the pages read, each an API hit on most indexers.
     */
    val indexerResultsLimit: Int = DEFAULT_INDEXER_RESULTS,
    val excludeLowQuality: Boolean = true,
    /** On: whole Blu-ray and DVD releases are hidden. Off by default: a Blu-ray plays from its feature clip. */
    val excludeFullDisc: Boolean = false,
    /** On: lower indexer priorities are only searched when higher ones find nothing. */
    val indexerFallback: Boolean = false,
    /** On: a release found by several indexers is shown once, from the highest priority. */
    val hideDuplicates: Boolean = false,
    /** On: each result shows its release name above its size, age and indexer. */
    val showReleaseNames: Boolean = true,
    /** On: releases that played recently are listed first, whatever the sort; fallback reaches them first too. */
    val availableFirst: Boolean = false,
    /** On: results show as each indexer answers. Off: the list shows once all answered, or after a short wait. */
    val progressiveResults: Boolean = false,
) {
    val ready: Boolean get() = enabled && providers.any { it.enabled } && indexers.any { it.enabled }

    /** Has a provider and an indexer, so it can be switched on. */
    val configured: Boolean get() = providers.isNotEmpty() && indexers.isNotEmpty()

    /** How many results are listed. */
    val resultLimit: Int get() = if (maxResults == UNLIMITED_RESULTS) Int.MAX_VALUE else maxResults

    /**
     * Resolves the global fallback. Auto requests at least 200, increasing with the display limit;
     * with an unlimited display it requests [MAX_INDEXER_RESULTS].
     */
    val indexerResults: Int get() = when {
        indexerResultsLimit in 1..MAX_INDEXER_RESULTS -> indexerResultsLimit
        maxResults == UNLIMITED_RESULTS -> MAX_INDEXER_RESULTS
        else -> maxOf(DEFAULT_INDEXER_RESULTS, maxResults)
    }

    /** Zero means unlimited fetching, independently of how many results are listed. */
    fun indexerResults(indexer: UsenetIndexer): Int = indexer.fetchResultsLimit ?: indexerResults

    /** The enabled providers, as the engine is given them. */
    fun servers(): List<String> = providers.filter { it.enabled }.map { it.serverUrl() }

    /** List order is the hierarchy: by priority, then by the user's order within a priority. */
    fun normalized() = copy(providers = providers.sortedBy { it.priority }, indexers = indexers.sortedBy { it.priority })

    companion object {
        const val UNLIMITED_RESULTS = 0
        const val MAX_LISTED_RESULTS = 500
        const val DEFAULT_INDEXER_RESULTS = 200
        const val MAX_INDEXER_RESULTS = 1000
        const val AUTO_INDEXER_RESULTS = 0

        /** The choices offered for [indexerResultsLimit]; indexers send at most 100 per page. */
        val INDEXER_RESULT_CHOICES = listOf(100, 200, 500, MAX_INDEXER_RESULTS)
    }
}

/** The usual Newznab API path, filled in for new indexers. */
const val DEFAULT_API_PATH = "/api"

/** 1 is the highest priority. */
val USENET_PRIORITIES = 1..5

/** Replaces the element with the same id as [value], or adds it at the end. */
internal fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value

/** Percent-encodes [value] for a URL's user info, as form encoding does but with %20 for spaces. */
internal fun encodeUrlComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (code < 0x80 && (char.isLetterOrDigit() || char in ".-*_")) {
            append(char)
        } else {
            append('%')
            append(HEX_UPPER[code shr 4])
            append(HEX_UPPER[code and 0x0F])
        }
    }
}

private const val HEX_UPPER = "0123456789ABCDEF"
