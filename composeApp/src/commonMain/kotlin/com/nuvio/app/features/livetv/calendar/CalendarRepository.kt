package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Calendar: upcoming episodes of the shows in your Library and watch history, and release
 * dates of the movies in your Library. Dates come from [CalendarSources]; what you've seen comes
 * from your watch history, so watched episodes are marked and episodes ahead of you can be hidden.
 */
internal object CalendarRepository {
    private const val MAX_SHOWS = 400
    private const val MAX_MOVIES = 200
    private const val DAY = 24 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    /** Shows the saved dates at once, then looks up whatever is due again. [force] looks everything up again. */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true && !force) return
        job = scope.launch {
            build(fetch = false, force = false)
            build(fetch = true, force = force)
        }
    }

    private class Candidate(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val imdb: String?,
        val tmdb: Int?,
        val addonBaseUrl: String?,
        val reasons: MutableSet<CalendarReason>,
        val year: Int?,
        /** When you last watched it (for which shows come first). */
        val lastWatched: Long = 0L,
    )

    /** Episodes you've seen of each show: the furthest one, and every one marked watched. */
    private class Seen(val furthest: Pair<Int, Int>?, val watched: Set<Pair<Int, Int>>)

    private fun isSeries(type: String) = type.equals("series", true) || type.equals("tv", true) ||
        type.equals("show", true) || type.equals("anime", true)

    private fun tmdbOf(id: String): Int? =
        if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() else null

    private fun yearOf(s: String?): Int? = s?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    private fun compare(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

    /** Further than the next episode after [furthest] (the next season's first counts as next). */
    private fun isAhead(pos: Pair<Int, Int>, furthest: Pair<Int, Int>): Boolean {
        if (compare(pos, furthest) <= 0) return false
        if (pos.first == furthest.first && pos.second == furthest.second + 1) return false
        if (pos.first == furthest.first + 1 && pos.second == 1) return false
        return true
    }

    private fun candidates(): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        fun keyOf(id: String, imdb: String?) = imdb?.takeIf { it.startsWith("tt") } ?: id
        fun add(c: Candidate) {
            val k = keyOf(c.id, c.imdb)
            val old = out[k]
            if (old == null) out[k] = c
            else {
                old.reasons += c.reasons
                if (old.poster == null && c.poster != null) {
                    out[k] = Candidate(old.id, old.type, old.name, c.poster, old.background, old.imdb, old.tmdb, old.addonBaseUrl,
                        old.reasons, old.year, old.lastWatched)
                }
            }
        }
        for (e in LibraryRepository.uiState.value.items) {
            val type = if (e.type.equals("movie", true)) "movie" else if (isSeries(e.type)) "series" else continue
            add(Candidate(e.id, type, e.name, e.poster, e.banner ?: e.landscapePoster, e.imdbId ?: e.id.takeIf { it.startsWith("tt") },
                e.tmdbId ?: tmdbOf(e.id), e.addonBaseUrl, mutableSetOf(CalendarReason.LIBRARY), yearOf(e.releaseInfo)))
        }
        // Every show in your watch history, however long ago: the ones between seasons are the
        // ones you most want to hear about. Ended shows simply have nothing coming.
        val progress = WatchProgressRepository.uiState.value
        progress.entries.filter { isSeries(it.parentMetaType) && it.parentMetaId !in progress.hiddenContentIds }
            .groupBy { it.parentMetaId }
            .forEach { (id, items) ->
                val p = items.maxBy { it.lastUpdatedEpochMs }
                add(Candidate(id, "series", p.title, p.poster, p.background, id.takeIf { it.startsWith("tt") }, tmdbOf(id), null,
                    mutableSetOf(CalendarReason.WATCHING), null, p.lastUpdatedEpochMs))
            }
        WatchedRepository.uiState.value.items.filter { it.season != null && isSeries(it.type) }
            .groupBy { it.id }
            .forEach { (id, items) ->
                val w = items.maxBy { it.markedAtEpochMs }
                add(Candidate(id, "series", w.name, w.poster, null, id.takeIf { it.startsWith("tt") }, tmdbOf(id), null,
                    mutableSetOf(CalendarReason.WATCHING), null, w.markedAtEpochMs))
            }
        return out.values.toList()
    }

    private fun seenEpisodes(): Map<String, Seen> {
        val furthest = HashMap<String, Pair<Int, Int>>()
        val marks = HashMap<String, MutableSet<Pair<Int, Int>>>()
        fun note(id: String, s: Int?, e: Int?, done: Boolean) {
            if (s == null || e == null) return
            val p = s to e
            val f = furthest[id]
            if (f == null || compare(p, f) > 0) furthest[id] = p
            if (done) marks.getOrPut(id) { HashSet() } += p
        }
        for (p in WatchProgressRepository.uiState.value.entries) {
            note(p.parentMetaId, p.seasonNumber, p.episodeNumber, p.isCompleted)
            // Movies watched: (0, 0).
            if (p.contentType.equals("movie", true) && p.isCompleted) marks.getOrPut(p.parentMetaId) { HashSet() } += (0 to 0)
        }
        for (w in WatchedRepository.uiState.value.items) {
            note(w.id, w.season, w.episode, true)
            if (w.type.equals("movie", true)) marks.getOrPut(w.id) { HashSet() } += (0 to 0)
        }
        return (furthest.keys + marks.keys).associateWith { Seen(furthest[it], marks[it].orEmpty()) }
    }

    private fun stale(d: CalendarSources.ShowData): Boolean {
        val now = LiveTvClock.nowMs()
        val age = now - d.fetchedAt
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // An episode out in the next day or so: checked more often (times and dates move).
        val soon = d.episodes.any { e ->
            val t = e.airMs ?: e.dayMs ?: return@any false
            kotlin.math.abs(t - now) < 36 * CalendarSources.HOUR
        }
        return when {
            ended -> age > 7 * CalendarSources.DAY
            soon -> age > 3 * CalendarSources.HOUR
            // Shows between seasons: once a day.
            else -> age > 20 * CalendarSources.HOUR
        }
    }

    private suspend fun build(fetch: Boolean, force: Boolean) = mutex.withLock {
        runCatching { buildLocked(fetch, force) }.onFailure {
            _state.value = _state.value.copy(loading = false, progress = null, problem = it.message)
        }
    }

    private suspend fun buildLocked(fetch: Boolean, force: Boolean) {
        val s = LiveTvPreferences.settings.value
        val all = candidates()
        // What you're watching first, then your Library (newest first): a Library of hundreds of
        // titles doesn't mean hundreds of look-ups every time.
        val shows = all.filter { it.type == "series" }
            .sortedWith(compareByDescending<Candidate> { CalendarReason.WATCHING in it.reasons }.thenByDescending { it.lastWatched })
            .take(MAX_SHOWS)
        val thisYear = SportsDates.localYearMonth().first
        val movies = all.filter { it.type == "movie" && (it.year == null || it.year >= thisYear - 1) }.take(MAX_MOVIES)
        if (fetch) _state.value = _state.value.copy(loading = true, progress = 0 to (shows.size + movies.size), problem = null)
        val seen = seenEpisodes()
        val fromMs = SportsDates.dayStart(-30)
        val toMs = SportsDates.dayStart(121)
        val entries = ArrayList<CalendarEntry>()
        var done = 0
        // The same show can come in twice under different ids: each show is only taken once.
        val usedShows = HashSet<Int>()
        for (c in shows) {
            val tvmId = if (fetch) CalendarSources.tvmazeId(c.imdb, c.tmdb, c.name) else CalendarSources.knownTvmazeId(c.imdb, c.tmdb)
            if (tvmId != null && !usedShows.add(tvmId)) { done++; continue }
            var data = tvmId?.let { CalendarSources.cachedShow(it) }
            if (fetch && tvmId != null && (force || data == null || stale(data))) {
                data = CalendarSources.fetchShow(tvmId) ?: data
            }
            done++
            if (fetch && done % 5 == 0) _state.value = _state.value.copy(progress = done to (shows.size + movies.size))
            if (data == null) continue
            entries += episodesOf(c, data, seen[c.id] ?: c.imdb?.let { seen[it] }, s, fromMs, toMs)
        }
        // Shows with no wide picture (TVmaze only had a poster): TMDB's, looked up once and kept.
        if (fetch) {
            val missing = entries.filter { it.kind == CalendarKind.EPISODE && it.background == null && it.contentId.startsWith("tt") }
                .map { it.contentId }.distinct().filter { !CalendarSources.backdropLooked(it) }.take(150)
            for (id in missing) runCatching { CalendarSources.showBackdrop(id) }
            if (missing.isNotEmpty()) {
                CalendarSources.saveBackdrops()
                for (i in entries.indices) {
                    val e = entries[i]
                    if (e.background == null) CalendarSources.knownBackdrop(e.contentId)?.let { entries[i] = e.copy(background = it) }
                }
            }
        }
        for (m in movies) {
            val dates = runCatching {
                CalendarSources.movieDates(m.imdb, m.tmdb, s.calendarCountry.ifBlank { "US" }, cachedOnly = !fetch)
            }.getOrNull()
            done++
            if (dates == null) continue
            entries += moviesOf(m, dates, seen[m.id] ?: m.imdb?.let { seen[it] }, fromMs, toMs)
        }
        CalendarSources.saveMovies()
        // Never the same entry twice (its reasons are put together).
        val merged = entries.groupBy { it.key }.map { (_, same) ->
            if (same.size == 1) same[0] else same[0].copy(reasons = same.flatMap { it.reasons }.toSet())
        }
        val sorted = merged.sortedWith(compareBy<CalendarEntry> { it.dayMs }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
        _state.value = CalendarState(
            entries = sorted,
            loading = false,
            progress = null,
            updatedAt = if (fetch) LiveTvClock.nowMs() else _state.value.updatedAt.takeIf { it > 0 } ?: LiveTvClock.nowMs(),
            showCount = shows.size + movies.size,
        )
    }

    private fun episodesOf(
        c: Candidate,
        d: CalendarSources.ShowData,
        seen: Seen?,
        s: LiveTvSettings,
        fromMs: Long,
        toMs: Long,
    ): List<CalendarEntry> {
        val now = LiveTvClock.nowMs()
        val eps = d.episodes.filter { e -> e.dayMs != null && e.dayMs >= fromMs && e.dayMs < toMs }
            .sortedWith(compareBy<CalendarSources.Episode> { it.season }.thenBy { it.number })
        if (eps.isEmpty()) return emptyList()
        val lastSeason = d.episodes.maxOfOrNull { it.season }
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // Several episodes out at once (a streaming season) make one entry.
        val groups = ArrayList<MutableList<CalendarSources.Episode>>()
        for (e in eps) {
            val g = groups.lastOrNull()
            val prev = g?.last()
            if (prev != null && prev.season == e.season && prev.dayMs == e.dayMs && prev.number + 1 == e.number &&
                (prev.airMs == e.airMs || d.show.streaming)
            ) g.add(e) else groups += mutableListOf(e)
        }
        val contentId = c.imdb ?: d.show.imdb ?: c.id
        return groups.map { g ->
            val e = g.first()
            val last = g.last()
            val tags = ArrayList<CalendarTag>()
            when {
                e.number == 1 && e.season == 1 -> tags += CalendarTag.SERIES_PREMIERE
                e.number == 1 -> tags += CalendarTag.SEASON_PREMIERE
            }
            val total = last.seasonEpisodes
            if (total != null && last.number >= total && !(e.number == 1 && g.size == 1 && total > 1)) {
                tags += if (ended && last.season == lastSeason) CalendarTag.SERIES_FINALE else CalendarTag.SEASON_FINALE
            }
            // West Coast: the delayed airing's time (see CalendarAirTimes).
            val local = CalendarAirTimes.local(e.airMs, d.show.zone, d.show.streaming)
            val aired = (local ?: e.dayMs!!) <= now
            val pos = e.season to e.number
            val watched = seen != null && (pos in seen.watched || (seen.furthest != null && compare(pos, seen.furthest) < 0))
            val fresh = aired && !watched && e.dayMs!! >= SportsDates.dayStart(-1)
            if (fresh) tags.add(0, CalendarTag.NEW)
            // Ahead of where you are: no title or picture (the next one you'd watch is fine).
            val spoiler = s.calendarHideSpoilers && seen?.furthest != null && !watched && isAhead(pos, seen.furthest)
            CalendarEntry(
                key = "ep|$contentId|${e.season}|${e.number}",
                kind = CalendarKind.EPISODE,
                contentId = contentId,
                type = "series",
                title = c.name.ifBlank { d.show.name },
                poster = c.poster ?: d.show.image,
                background = c.background ?: d.show.backdrop,
                addonBaseUrl = c.addonBaseUrl,
                season = e.season,
                episode = e.number,
                lastEpisode = last.number.takeIf { g.size > 1 },
                episodeTitle = if (g.size > 1) null else e.name,
                episodeImage = e.image,
                description = e.summary.takeIf { g.size == 1 },
                airMs = local,
                dayMs = local?.let { SportsDates.dayOf(it) } ?: e.dayMs!!,
                network = d.show.network,
                streaming = d.show.streaming || e.airMs == null,
                tags = tags,
                reasons = c.reasons.toSet(),
                watched = watched,
                spoiler = spoiler,
                runtimeMin = e.runtime,
            )
        }
    }

    private fun moviesOf(
        m: Candidate,
        dates: CalendarSources.MovieDates,
        seen: Seen?,
        fromMs: Long,
        toMs: Long,
    ): List<CalendarEntry> {
        val out = ArrayList<CalendarEntry>()
        val contentId = m.imdb ?: m.id
        val watched = seen?.watched?.contains(0 to 0) == true
        fun fmt(ms: Long) = SportsDates.shortDayLabel(ms).substringAfter(' ').let { d -> "${SportsDates.monthLabel(ms)} $d" }
        fun inWindow(ms: Long?) = ms != null && ms >= fromMs && ms < toMs
        fun entry(day: Long, tag: CalendarTag, note: String?) = CalendarEntry(
            key = "mv|$contentId|$tag", kind = CalendarKind.MOVIE, contentId = contentId, type = "movie",
            title = m.name.ifBlank { dates.title.orEmpty() }, poster = m.poster ?: dates.poster, background = m.background ?: dates.backdrop,
            description = dates.overview, addonBaseUrl = m.addonBaseUrl, dayMs = day, tags = listOf(tag), note = note,
            reasons = m.reasons.toSet(), watched = watched,
        )
        val home = dates.digitalMs
        if (inWindow(dates.theaterMs)) {
            out += entry(dates.theaterMs!!, CalendarTag.IN_THEATERS, home?.let { "At home ${fmt(it)}" } ?: "Not out to watch at home yet")
        }
        if (inWindow(home)) {
            out += entry(home!!, CalendarTag.AT_HOME, dates.theaterMs?.let { "In theaters ${fmt(it)}" })
        }
        if (home == null && inWindow(dates.discMs)) {
            out += entry(dates.discMs!!, CalendarTag.ON_DISC, dates.theaterMs?.let { "In theaters ${fmt(it)}" })
        }
        return out
    }
}
