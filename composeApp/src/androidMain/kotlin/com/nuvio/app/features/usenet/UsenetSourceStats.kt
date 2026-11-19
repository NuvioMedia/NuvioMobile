package com.nuvio.app.features.usenet

import android.content.Context
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where [UsenetSourceStats] persists; the app uses private SharedPreferences. */
internal interface UsenetStatsStorage {
    fun load(): String?
    fun save(value: String)
    /** Removes the saved counts at once; the chosen period and content stay. */
    fun clear()
    fun loadPreference(name: String): String?
    fun savePreference(name: String, value: String)
    /** Changes when the stored counts are cleared from outside, so a loaded copy is dropped. */
    val generation: Int get() = 0
}

internal class SharedPreferencesUsenetStatsStorage(context: Context) : UsenetStatsStorage {
    private val preferences = context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    override fun clear() { preferences.edit().remove(KEY).commit() }
    override fun loadPreference(name: String): String? = preferences.getString(name, null)
    override fun savePreference(name: String, value: String) { preferences.edit().putString(name, value).apply() }
    @Volatile override var generation = 0
        private set

    /** An account reset: the counts and the chosen filters go. They hold no credential and are shared by profiles. */
    fun clearAll() {
        preferences.edit().clear().commit()
        generation += 1
    }

    private companion object { const val KEY = "stats" }
}

/** Counts split by what they were for; only the parts with events exist. */
@Serializable
internal data class Split<T>(val movie: T? = null, val show: T? = null, val other: T? = null) {
    fun update(kind: ContentKind?, empty: T, change: (T) -> T): Split<T> = when (kind) {
        ContentKind.MOVIE -> copy(movie = change(movie ?: empty))
        ContentKind.SHOW -> copy(show = change(show ?: empty))
        null -> copy(other = change(other ?: empty))
    }

    fun select(content: StatsContent, plus: (T, T) -> T): T? = when (content) {
        StatsContent.ALL -> listOfNotNull(movie, show, other).reduceOrNull(plus)
        StatsContent.MOVIES -> movie
        StatsContent.SHOWS -> show
    }
}

/** One source's counts: per local day for the last [UsenetSourceStats.DAYS] days, and since it was first counted. */
@Serializable
internal data class SourceRecord<T>(
    val since: Long,
    val touchedAt: Long,
    val total: Split<T> = Split(),
    /** By local epoch day. */
    val days: Map<Long, Split<T>> = emptyMap(),
)

@Serializable
internal data class StoredUsenetStats(
    val indexers: Map<String, SourceRecord<IndexerCounts>> = emptyMap(),
    val providers: Map<String, SourceRecord<ProviderCounts>> = emptyMap(),
)

/** What the engine says each provider did for a session; a provider is named by its index in the session's servers. */
@Serializable
private data class ProviderReports(val providers: List<ProviderReport> = emptyList())

@Serializable
private data class ProviderReport(
    val server: Int = -1,
    val articles: Long = 0,
    val bytes: Long = 0,
    val missing: Long = 0,
    val errors: Long = 0,
    val ttfbMs: Double = 0.0,
)

/**
 * Which account a server is: its address and username. Its password, connections and priority are
 * left out, so a server listed with another password is still the same account.
 */
internal fun providerAccount(serverUrl: String?): String? {
    val uri = runCatching { URI(serverUrl ?: return null) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("nntps", ignoreCase = true)) 563 else 119
    val username = uri.rawUserInfo?.substringBefore(':')
        ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }.orEmpty()
    return "$host:$port\n$username"
}

/**
 * How each indexer and provider did: searches, requests, NZB downloads and how releases played for
 * an indexer; articles served and missing for a provider. Kept per day for a month and in total,
 * each split by movies and shows, so settings can show the last week, the last month or everything.
 * Sources are kept under their ids, so an edited source, such as one with a new API key, keeps its
 * counts; nothing names a source or holds a key or credential. The same counts as NuvioTV's.
 *
 * A search sends several requests to every indexer within a second, so counts are saved at most
 * every [SAVE_INTERVAL_MS]; [flush] saves the rest when the app leaves the foreground.
 */
internal class UsenetSourceStats(
    private val storage: UsenetStatsStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Runs saves in order, off the calling thread. */
    private val background: (Runnable) -> Unit = WRITER::execute,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0)

    /** Increments on every change, so settings can show counts that searches and playback update. */
    val revision: StateFlow<Int> = changes.asStateFlow()
    private val periodState = MutableStateFlow(StatsPeriod.LAST_30_DAYS)
    private val contentState = MutableStateFlow(StatsContent.ALL)

    /** The chosen period and content; read from storage on first use. */
    val period: StateFlow<StatsPeriod> = periodState.asStateFlow()
    val content: StateFlow<StatsContent> = contentState.asStateFlow()

    private class Records(
        val indexers: MutableMap<String, SourceRecord<IndexerCounts>>,
        val providers: MutableMap<String, SourceRecord<ProviderCounts>>,
    )

    private var loaded: Records? = null
    private var loadedGeneration = 0
    private var dirty = false
    private var savedAt = Long.MIN_VALUE / 2

    /**
     * Which indexer listed a release, and what for. Memory only: release URLs carry API keys.
     * [Attribution.outcomeAt] keeps a replayed first frame from counting twice.
     */
    private class Attribution(val indexer: String, val kind: ContentKind?, var outcomeAt: Long = Long.MIN_VALUE / 2)

    private val attributions = object : LinkedHashMap<String, Attribution>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attribution>) = size > MAX_ATTRIBUTIONS
    }

    /**
     * Configured providers' ids by server URL and by account, from [knowProviders]. The engine
     * names a session's providers by the URLs it was opened with, which may predate an edit; those
     * stay here, so the session still counts for the edited provider. Memory only: URLs hold passwords.
     */
    private val providerIds = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PROVIDER_LINKS
    }
    private val accountIds = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PROVIDER_LINKS
    }

    /** Loaded on first use, and again after an account reset cleared the storage. */
    private val records: Records
        get() {
            loaded?.takeIf { loadedGeneration == storage.generation }?.let { return it }
            loadedGeneration = storage.generation
            dirty = false
            periodState.value = preference(PERIOD, StatsPeriod.LAST_30_DAYS)
            contentState.value = preference(CONTENT, StatsContent.ALL)
            val stored = runCatching { json.decodeFromString<StoredUsenetStats>(storage.load() ?: "{}") }
                .getOrDefault(StoredUsenetStats())
            return Records(stored.indexers.toMutableMap(), stored.providers.toMutableMap()).also { loaded = it }
        }

    private inline fun <reified E : Enum<E>> preference(name: String, default: E): E =
        storage.loadPreference(name)?.let { value -> enumValues<E>().firstOrNull { it.name == value } } ?: default

    /** Loads the counts and the chosen filters; settings call it off the main thread before reading. */
    @Synchronized
    fun load() {
        records
    }

    @Synchronized
    fun indexerRequest(key: String, kind: ContentKind?, outcome: RequestOutcome, elapsedMs: Long = 0) = countIndexer(key, kind) {
        when (outcome) {
            RequestOutcome.ANSWERED -> it.copy(
                requests = it.requests + 1, answered = it.answered + 1,
                responseMs = it.responseMs + elapsedMs.coerceAtLeast(0),
            )
            RequestOutcome.FAILED -> it.copy(requests = it.requests + 1, failedRequests = it.failedRequests + 1)
            RequestOutcome.LIMITED -> it.copy(requests = it.requests + 1, limitedRequests = it.limitedRequests + 1)
        }
    }

    @Synchronized
    fun indexerSearch(key: String, kind: ContentKind, outcome: SearchOutcome, releases: Int = 0) = countIndexer(key, kind) {
        when (outcome) {
            SearchOutcome.ANSWERED -> it.copy(
                searches = it.searches + 1, releases = it.releases + releases.coerceAtLeast(0),
                emptySearches = it.emptySearches + if (releases <= 0) 1 else 0,
            )
            SearchOutcome.FAILED -> it.copy(searches = it.searches + 1, failedSearches = it.failedSearches + 1)
            SearchOutcome.PAUSED -> it.copy(searches = it.searches + 1, pausedSearches = it.pausedSearches + 1)
        }
    }

    /** The providers as configured, so engine reports, which name servers by URL, count for them. */
    @Synchronized
    fun knowProviders(providers: Collection<UsenetProvider>) {
        providers.forEach { provider ->
            val url = provider.serverUrl()
            providerIds[url] = provider.id
            providerAccount(url)?.let { accountIds[it] = provider.id }
        }
    }

    /** A configured provider's id: by its exact URL, else by account, such as for an addon's release. */
    private fun providerId(server: String?): String? =
        server?.let { providerIds[it] ?: providerAccount(it)?.let(accountIds::get) }

    /** Remembers that [indexerKey] listed these releases, so their downloads and playback count for it. */
    @Synchronized
    fun remember(nzbUrls: Collection<String>, indexerKey: String, kind: ContentKind?) {
        nzbUrls.forEach { url ->
            if (url.isBlank()) return@forEach
            val known = attributions[url]
            // A release listed again keeps when its outcome last counted.
            attributions[url] = Attribution(indexerKey, kind, known?.outcomeAt ?: (Long.MIN_VALUE / 2))
        }
    }

    /** An NZB was downloaded from the release's indexer. Ignored for a release no search listed. */
    @Synchronized
    fun grabbed(nzbUrl: String?) {
        val attribution = nzbUrl?.let { attributions[it] } ?: return
        countIndexer(attribution.indexer, attribution.kind) { it.copy(grabs = it.grabs + 1) }
    }

    /** The release showed its first frame. Counted once per release every [OUTCOME_INTERVAL_MS]. */
    @Synchronized
    fun played(nzbUrl: String?) = outcome(nzbUrl) { it.copy(played = it.played + 1) }

    /** The release failed for good. Counted once per release every [OUTCOME_INTERVAL_MS]. */
    @Synchronized
    fun unavailable(nzbUrl: String?) = outcome(nzbUrl) { it.copy(unavailable = it.unavailable + 1) }

    private fun outcome(nzbUrl: String?, change: (IndexerCounts) -> IndexerCounts) {
        val attribution = nzbUrl?.let { attributions[it] } ?: return
        // Player restarts and decoder switches show the first frame again.
        if (now() - attribution.outcomeAt < OUTCOME_INTERVAL_MS) return
        attribution.outcomeAt = now()
        countIndexer(attribution.indexer, attribution.kind, change)
    }

    /**
     * Adds what the engine reported when a session closed, or with an open it [refused] for
     * [failure], its X-Usenet-Failure kind: [body] is its report, whose providers are indexes in
     * [servers], the session's list. An empty or unreadable report adds nothing, and so does a
     * server that is no configured provider's.
     *
     * A refused open counts as one that failed to open only for the providers it failed for: a
     * [PROVIDER_FAILURES] kind, and a provider that lacked or broke an article. A password, a
     * compressed archive or a blank release is no provider's failure; a provider that served
     * articles for it still counts them and the session.
     */
    @Synchronized
    fun providerSession(servers: List<String>, kind: ContentKind?, body: String, refused: Boolean = false, failure: String? = null) {
        if (body.isBlank()) return
        val providerFailure = refused && failure in PROVIDER_FAILURES
        val reports = runCatching { json.decodeFromString<ProviderReports>(body) }.getOrNull()?.providers ?: return
        // Two listings of one provider (an addon's, with another password, say) are one here.
        val sessions = linkedMapOf<String, ProviderCounts>()
        reports.forEach { report ->
            val key = providerId(servers.getOrNull(report.server)) ?: return@forEach
            val ttfb = report.ttfbMs.takeIf { it > 0 && it.isFinite() }?.roundToLong()
            val counts = ProviderCounts(
                articles = report.articles.coerceAtLeast(0), bytes = report.bytes.coerceAtLeast(0),
                missing = report.missing.coerceAtLeast(0), errors = report.errors.coerceAtLeast(0),
                ttfbMs = ttfb ?: 0, ttfbSessions = if (ttfb != null) 1 else 0,
            )
            // An open refused before any article was asked of the provider, such as for an NZB too
            // large to read, did not use it.
            if (refused && counts.articles + counts.missing + counts.errors == 0L) return@forEach
            val known = sessions[key]
            sessions[key] = when {
                known == null -> counts
                // A session measures one first byte per provider: the first listing's.
                known.ttfbSessions > 0 -> known + counts.copy(ttfbMs = 0, ttfbSessions = 0)
                else -> known + counts
            }
        }
        if (sessions.isEmpty()) return
        sessions.forEach { (key, counts) ->
            val failedHere = providerFailure && counts.missing + counts.errors > 0
            records.providers.add(key, kind, ProviderCounts()) {
                it + counts.copy(sessions = 1, failedOpens = if (failedHere) 1 else 0)
            }
        }
        changed()
    }

    @Synchronized
    fun indexer(key: String, period: StatsPeriod, content: StatsContent): IndexerCounts? =
        records.indexers[key]?.counts(period, content, IndexerCounts::plus)

    @Synchronized
    fun indexerToday(key: String, content: StatsContent): IndexerCounts? =
        records.indexers[key]?.days?.get(today())?.select(content, IndexerCounts::plus)

    /** When the indexer was first counted, or since the last reset. */
    @Synchronized
    fun indexerSince(key: String): Long? = records.indexers[key]?.since

    @Synchronized
    fun provider(key: String, period: StatsPeriod, content: StatsContent): ProviderCounts? =
        records.providers[key]?.counts(period, content, ProviderCounts::plus)

    @Synchronized
    fun providerSince(key: String): Long? = records.providers[key]?.since

    private fun <T> SourceRecord<T>.counts(period: StatsPeriod, content: StatsContent, plus: (T, T) -> T): T? {
        if (period == StatsPeriod.ALL_TIME) return total.select(content, plus)
        val first = today() - (if (period == StatsPeriod.LAST_7_DAYS) 7 else DAYS) + 1
        return days.filterKeys { it >= first }.values.mapNotNull { it.select(content, plus) }.reduceOrNull(plus)
    }

    @Synchronized
    fun setPeriod(period: StatsPeriod) {
        records
        if (periodState.value == period) return
        periodState.value = period
        storage.savePreference(PERIOD, period.name)
        changes.value += 1
    }

    @Synchronized
    fun setContent(content: StatsContent) {
        records
        if (contentState.value == content) return
        contentState.value = content
        storage.savePreference(CONTENT, content.name)
        changes.value += 1
    }

    /** Forgets every count at once; the chosen period and content stay. */
    @Synchronized
    fun reset() {
        records.indexers.clear()
        records.providers.clear()
        dirty = false
        // After any save already queued, so an older copy cannot come back.
        background(Runnable { storage.clear() })
        changes.value += 1
    }

    /** Saves counts that were held back. */
    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    private fun countIndexer(key: String, kind: ContentKind?, change: (IndexerCounts) -> IndexerCounts) {
        records.indexers.add(key, kind, IndexerCounts(), change)
        changed()
    }

    private fun <T> MutableMap<String, SourceRecord<T>>.add(key: String, kind: ContentKind?, empty: T, change: (T) -> T) {
        val at = now()
        val today = today()
        val old = this[key] ?: SourceRecord(since = at, touchedAt = at)
        val days = old.days.toMutableMap()
        days[today] = (days[today] ?: Split()).update(kind, empty, change)
        this[key] = old.copy(touchedAt = at, total = old.total.update(kind, empty, change), days = days)
    }

    private fun changed() {
        changes.value += 1
        dirty = true
        if (now() - savedAt >= SAVE_INTERVAL_MS) save()
    }

    private fun save() {
        val at = now()
        val first = today() - DAYS + 1
        val records = records
        records.indexers.prune(at, first)
        records.providers.prune(at, first)
        val snapshot = StoredUsenetStats(records.indexers.toMap(), records.providers.toMap())
        dirty = false
        savedAt = at
        background(Runnable { storage.save(json.encodeToString(StoredUsenetStats.serializer(), snapshot)) })
    }

    /** Deleted sources stop being counted: forget them, bound the size, and drop days past the month. */
    private fun <T> MutableMap<String, SourceRecord<T>>.prune(at: Long, firstDay: Long) {
        entries.removeAll { at - it.value.touchedAt > RETENTION_MS }
        while (size > MAX_SOURCES) remove(minBy { it.value.touchedAt }.key)
        replaceAll { _, record ->
            if (record.days.keys.all { it >= firstDay }) record else record.copy(days = record.days.filterKeys { it >= firstDay })
        }
    }

    private fun today(): Long = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()

    companion object {
        /** How many days are kept per day. */
        const val DAYS = 30
        internal const val SAVE_INTERVAL_MS = 5_000L
        internal const val OUTCOME_INTERVAL_MS = 12L * 3600 * 1000
        internal const val RETENTION_MS = 180L * 24 * 3600 * 1000

        /** Per kind of source. */
        internal const val MAX_SOURCES = 32
        private const val MAX_ATTRIBUTIONS = 4000
        private const val MAX_PROVIDER_LINKS = 64

        /** Engine failure kinds a provider causes: articles it lacks or serves damaged. */
        internal val PROVIDER_FAILURES = setOf("missing-article", "hole-limit", "invalid-article")
        private const val PERIOD = "period"
        private const val CONTENT = "content"
        private val WRITER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "usenet-stats").apply { isDaemon = true }
        }
    }
}
