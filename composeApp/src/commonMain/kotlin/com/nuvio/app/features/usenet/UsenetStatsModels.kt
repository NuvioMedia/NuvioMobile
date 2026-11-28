package com.nuvio.app.features.usenet

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/** The days the statistics page counts. */
enum class StatsPeriod { LAST_7_DAYS, LAST_30_DAYS, ALL_TIME }

/** The searches and playback the statistics page counts. */
enum class StatsContent { ALL, MOVIES, SHOWS }

/** What an event was for; null where it belongs to neither, such as a capabilities request. */
enum class ContentKind { MOVIE, SHOW }

/** The same rule as a built-in search: every type but a movie is searched as a series. */
internal fun contentKindOf(type: String?): ContentKind? = when {
    type.isNullOrBlank() -> null
    type.equals("movie", ignoreCase = true) -> ContentKind.MOVIE
    else -> ContentKind.SHOW
}

enum class RequestOutcome { ANSWERED, FAILED, LIMITED }

enum class SearchOutcome { ANSWERED, FAILED, PAUSED }

/** What an indexer did: searches, requests, NZB downloads, and how its releases played. As NuvioTV counts it. */
@Serializable
data class IndexerCounts(
    /** Title searches that asked this indexer. */
    val searches: Int = 0,
    /** Searches it answered without a matching release. */
    val emptySearches: Int = 0,
    val failedSearches: Int = 0,
    /** Searches skipped because the indexer was paused by a limit. */
    val pausedSearches: Int = 0,
    /** Matching releases it returned. */
    val releases: Long = 0,
    /** HTTP requests sent to it: searches, further pages, capabilities and key checks. */
    val requests: Int = 0,
    val failedRequests: Int = 0,
    /** Requests refused for a limit: HTTP 429 or a spent quota. */
    val limitedRequests: Int = 0,
    /** Requests it answered, and their summed response time: the average is [responseMs] / [answered]. */
    val answered: Int = 0,
    val responseMs: Long = 0,
    /**
     * NZBs downloaded from it for an open that succeeded. One served from the NZB cache is not a
     * download, and neither is a failed open: a release that failed for good counts as [unavailable].
     */
    val grabs: Int = 0,
    /** Its releases that showed their first frame, and that failed for good. */
    val played: Int = 0,
    val unavailable: Int = 0,
) {
    operator fun plus(other: IndexerCounts) = IndexerCounts(
        searches + other.searches, emptySearches + other.emptySearches, failedSearches + other.failedSearches,
        pausedSearches + other.pausedSearches, releases + other.releases, requests + other.requests,
        failedRequests + other.failedRequests, limitedRequests + other.limitedRequests, answered + other.answered,
        responseMs + other.responseMs, grabs + other.grabs, played + other.played, unavailable + other.unavailable,
    )
}

/** What a provider did for the engine's sessions. As NuvioTV counts it. */
@Serializable
data class ProviderCounts(
    /** Engine sessions that used it, [failedOpens] included: opens the engine refused, such as for missing articles. */
    val sessions: Int = 0,
    val failedOpens: Int = 0,
    /** Articles it served, and their decoded size. */
    val articles: Long = 0,
    val bytes: Long = 0,
    /** Articles it was asked for and did not have. */
    val missing: Long = 0,
    /** Attempts that failed otherwise: connection, sign-in, quota or a broken-off transfer. */
    val errors: Long = 0,
    /** Summed time to first byte of the sessions that measured one: the average is [ttfbMs] / [ttfbSessions]. */
    val ttfbMs: Long = 0,
    val ttfbSessions: Int = 0,
) {
    operator fun plus(other: ProviderCounts) = ProviderCounts(
        sessions + other.sessions, failedOpens + other.failedOpens, articles + other.articles, bytes + other.bytes,
        missing + other.missing, errors + other.errors, ttfbMs + other.ttfbMs, ttfbSessions + other.ttfbSessions,
    )
}

/** An indexer's counts in the chosen period, today's, and since when it is counted. */
data class IndexerStatsUi(val counts: IndexerCounts? = null, val today: IndexerCounts? = null, val since: Long? = null)

data class ProviderStatsUi(val counts: ProviderCounts? = null, val since: Long? = null)

/** What the statistics page shows, by source id, for the chosen filters. */
data class UsenetStatsSnapshot(
    val period: StatsPeriod = StatsPeriod.LAST_30_DAYS,
    val content: StatsContent = StatsContent.ALL,
    val indexers: Map<String, IndexerStatsUi> = emptyMap(),
    val providers: Map<String, ProviderStatsUi> = emptyMap(),
)

/** How often an indexer's answered searches found something, and how much on average when they did. */
data class SearchYield(val percent: Int, val averageReleases: Int)

/** Null without an answered search: failed and paused searches say nothing about what it finds. */
fun IndexerCounts.searchYield(): SearchYield? {
    val answeredSearches = searches - failedSearches - pausedSearches
    if (answeredSearches <= 0) return null
    val withResults = (answeredSearches - emptySearches).coerceIn(0, answeredSearches)
    return SearchYield(
        (withResults * 100.0 / answeredSearches).roundToInt(),
        if (withResults == 0) 0 else (releases.toDouble() / withResults).roundToInt(),
    )
}

/** Null when no request was answered. */
fun IndexerCounts.averageResponseMs(): Long? =
    if (answered <= 0) null else (responseMs.toDouble() / answered).roundToInt().toLong()

/**
 * The share of the articles asked of a provider that it had, to one decimal; null before it was
 * asked for any. Errors are left out: they say nothing about what it holds.
 */
fun ProviderCounts.completion(): Double? {
    val asked = articles + missing
    if (asked <= 0) return null
    return (articles * 1000.0 / asked).roundToLong() / 10.0
}

/** Null when no session measured one. */
fun ProviderCounts.averageFirstByteMs(): Long? =
    if (ttfbSessions <= 0) null else (ttfbMs.toDouble() / ttfbSessions).roundToLong()

data class StatsBarSource(val id: String, val name: String, val value: Long)

data class StatsBar(val id: String, val name: String, val value: Long, val fraction: Float)

/**
 * One bar per source, largest first, as a share of the largest. Empty when there is nothing to
 * compare: fewer than two sources, or no source with a count.
 */
fun statsBars(sources: List<StatsBarSource>): List<StatsBar> {
    val max = sources.maxOfOrNull { it.value } ?: 0L
    if (sources.size < 2 || max <= 0) return emptyList()
    return sources.sortedWith(compareByDescending<StatsBarSource> { it.value }.thenBy { it.name.lowercase() })
        .map { StatsBar(it.id, it.name, it.value, (it.value.coerceAtLeast(0).toDouble() / max).toFloat()) }
}
