package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where the Calendar's dates come from:
 *  - TVmaze (free, no key) for TV episodes: each episode's exact air time, so the day is right in
 *    your own time zone, plus the network.
 *  - TMDB (the key the app already has) for movie release dates, for shows' wide pictures, and for
 *    finding a show TVmaze only knows through other ids.
 * Everything is kept on the phone, so a show is only looked up again when it's due.
 */
internal object CalendarSources {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    data class Show(
        val id: Int,
        val name: String,
        val status: String,
        val network: String?,
        val streaming: Boolean,
        val image: String?,
        val imdb: String?,
        /** A wide picture of the show (TVmaze's background image). */
        val backdrop: String? = null,
        /** The network's time zone ("America/New_York"); null when not known. */
        val zone: String? = null,
        /** TVmaze's kind of show ("Scripted", "Talk Show", "News"…). */
        val type: String = "",
    )

    data class Episode(
        val season: Int,
        val number: Int,
        val name: String?,
        /** Exact time (null when TVmaze only has the day). */
        val airMs: Long?,
        /** Midnight (device time) of the day it airs, null when not known. */
        val dayMs: Long?,
        val runtime: Int?,
        val image: String?,
        /** The season's episode count, when known (for "Season finale"). */
        val seasonEpisodes: Int?,
        val summary: String? = null,
    )

    data class ShowData(val show: Show, val episodes: List<Episode>, val fetchedAt: Long)

    data class MovieDates(
        val theaterMs: Long?, val digitalMs: Long?, val discMs: Long?, val title: String?, val poster: String?,
        val backdrop: String? = null, val overview: String? = null,
    )

    private val client by lazy { createApiHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }
    private val tvmLock = Mutex()
    private var lastTvmCall = 0L
    private val fileLock = Mutex()

    // ---- small helpers

    private fun JsonObject.str(key: String): String = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
    private fun JsonObject.int(key: String): Int = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun String.https() = replace("http://", "https://")

    /** Midnight (device time) of a "yyyy-MM-dd" date, or null. */
    fun dayOf(date: String?): Long? = SportsDates.localDateOf(date?.takeIf { it.length >= 10 })

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.Default) {
        runCatching {
            val r = client.get(url) { header("User-Agent", "NuvioPlusIPTV"); header("Accept", "application/json") }
            if (r.status.value in 200..299) r.bodyAsText() else null
        }.getOrNull()
    }

    /** One TVmaze request, at most about two a second (TVmaze allows 20 every 10 seconds). */
    private suspend fun tvm(path: String): String? = tvmLock.withLock {
        repeat(2) { attempt ->
            val wait = 520L - (LiveTvClock.nowMs() - lastTvmCall)
            if (wait > 0) delay(wait)
            lastTvmCall = LiveTvClock.nowMs()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val r = client.get("https://api.tvmaze.com$path") { header("User-Agent", "NuvioPlusIPTV") }
                    r.status.value to (if (r.status.value in 200..299) r.bodyAsText() else null)
                }.getOrNull()
            } ?: return@withLock null
            if (result.first == 429 && attempt == 0) delay(10_000) else return@withLock result.second
        }
        null
    }

    private suspend fun tmdb(path: String): JsonObject? {
        val key = TmdbSettingsRepository.effectiveApiKey()
        if (key.isBlank()) return null
        val sep = if ('?' in path) '&' else '?'
        val raw = fetch("https://api.themoviedb.org/3$path${sep}api_key=$key") ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    // ---- saved files

    private suspend fun readJson(name: String): JsonObject? = fileLock.withLock {
        runCatching { LiveTvFiles.readSmall(LiveTvDirs.CALENDAR, name)?.let { json.parseToJsonElement(it).jsonObject } }.getOrNull()
    }

    private suspend fun writeJson(name: String, o: JsonObject) = fileLock.withLock {
        runCatching { LiveTvFiles.writeSmall(LiveTvDirs.CALENDAR, name, o.toString()) }
        Unit
    }

    // ---- TVmaze ids

    private var ids: MutableMap<String, Pair<Int, Long>>? = null

    private suspend fun idMap(): MutableMap<String, Pair<Int, Long>> {
        ids?.let { return it }
        val m = HashMap<String, Pair<Int, Long>>()
        readJson("tvmaze_ids.json")?.forEach { (k, v) ->
            val o = v as? JsonObject ?: return@forEach
            m[k] = o.int("id") to (o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        ids = m
        return m
    }

    private suspend fun saveIds() {
        val m = ids ?: return
        writeJson("tvmaze_ids.json", buildJsonObject {
            for ((k, v) in m) put(k, buildJsonObject { put("id", v.first); put("at", v.second) })
        })
    }

    /** TVmaze's id if it's been looked up before (no network). */
    suspend fun knownTvmazeId(imdb: String?, tmdbId: Int?): Int? {
        val key = imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null
        return idMap()[key]?.first?.takeIf { it > 0 }
    }

    /**
     * TVmaze's id for a show, from its IMDb id (or TMDB id). Shows TVmaze doesn't know are asked
     * about again after a week (new shows get added).
     */
    suspend fun tvmazeId(imdb: String?, tmdbId: Int?, name: String?): Int? {
        val key = imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null
        val map = idMap()
        map[key]?.let { (id, at) ->
            if (id > 0) return id
            if (LiveTvClock.nowMs() - at < 7 * DAY) return null
        }
        var found: Int? = null
        if (imdb != null) found = lookup("imdb=$imdb")
        if (found == null) {
            val tvdb = tmdbTvdbId(imdb, tmdbId)
            if (tvdb != null) found = lookup("thetvdb=$tvdb")
        }
        // The lookup answers with a redirect; when that can't be followed, ask by name and
        // check the answer's IMDb id.
        if (found == null && imdb != null && !name.isNullOrBlank()) {
            val raw = tvm("/singlesearch/shows?q=" + name.encodeURLParameter())
            val o = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            if (o != null && o.obj("externals")?.str("imdb") == imdb) found = o.int("id").takeIf { it > 0 }
        }
        map[key] = (found ?: 0) to LiveTvClock.nowMs()
        saveIds()
        return found
    }

    private suspend fun lookup(query: String): Int? {
        val raw = tvm("/lookup/shows?$query") ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject.int("id") }.getOrNull()?.takeIf { it > 0 }
    }

    /** The show a guide listing is about, by name (null when TVmaze has none, or no IMDb id for it). */
    suspend fun findShowByName(name: String): Show? {
        val q = name.trim().takeIf { it.length >= 2 } ?: return null
        val raw = tvm("/singlesearch/shows?q=" + q.encodeURLParameter()) ?: return null
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return parseShow(o)?.takeIf { it.imdb != null }
    }

    // ---- TVmaze shows

    private val shows = HashMap<Int, ShowData>()

    /** The show and its episodes from a month ago to four months ahead, kept on the phone. */
    suspend fun cachedShow(id: Int): ShowData? {
        shows[id]?.let { return it }
        val o = readJson("show_$id.json") ?: return null
        return runCatching { parseShowData(o) }.getOrNull()?.also { shows[id] = it }
    }

    /** Looks the show up again (the current and coming seasons only). */
    suspend fun fetchShow(id: Int): ShowData? {
        val showRaw = tvm("/shows/$id?embed[]=images") ?: return null
        val show = runCatching { parseShow(json.parseToJsonElement(showRaw).jsonObject) }.getOrNull() ?: return null
        val now = LiveTvClock.nowMs()
        class Season(val id: Int, val order: Int?, val premiereMs: Long?, val endMs: Long?)
        val seasons = tvm("/shows/$id/seasons")?.let { raw ->
            runCatching {
                json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    Season(o.int("id"), o.int("episodeOrder").takeIf { it > 0 }, dayOf(o.str("premiereDate")), dayOf(o.str("endDate")))
                }
            }.getOrNull()
        }.orEmpty()
        // Only seasons that overlap the Calendar's window (a month back, four months ahead).
        val wanted = seasons.filter { s ->
            val endOk = s.endMs == null || s.endMs >= now - 40 * DAY
            val startOk = s.premiereMs == null || s.premiereMs <= now + 130 * DAY
            endOk && startOk && (s.premiereMs != null || s.endMs == null)
        }.takeLast(2)
        val episodes = ArrayList<Episode>()
        for (s in wanted) {
            val raw = tvm("/seasons/${s.id}/episodes") ?: continue
            runCatching {
                for (el in json.parseToJsonElement(raw).jsonArray) {
                    val o = el as? JsonObject ?: continue
                    if (o["number"] is JsonNull || o["number"] == null) continue // specials
                    parseEpisode(o, s.order)?.let { episodes += it }
                }
            }
        }
        val data = ShowData(show, episodes, now)
        shows[id] = data
        writeJson("show_$id.json", showDataJson(data))
        return data
    }

    private class AiringRaw(val at: Long, val tv: String, val web: String)
    private val airingCache = HashMap<String, AiringRaw>()

    /** Airing Today: every show with an episode on the day starting at [dayMs] (TV in [country], and streaming). */
    suspend fun airingOn(dayMs: Long, country: String): List<Pair<Show, Episode>>? {
        val t = com.nuvio.app.features.livetv.data.CivilTime.at(dayMs + 12 * HOUR, com.nuvio.app.features.livetv.data.LiveTvTimeZones.SYSTEM)
        val date = "${t.year}-${t.month.toString().padStart(2, '0')}-${t.day.toString().padStart(2, '0')}"
        val key = "$country|$date"
        val now = LiveTvClock.nowMs()
        var raw = airingCache[key]?.takeIf { now - it.at < 2 * HOUR }
        if (raw == null) {
            val tv = tvm("/schedule?country=$country&date=$date")
            val web = tvm("/schedule/web?date=$date")
            if (tv == null && web == null) return null
            raw = AiringRaw(now, tv ?: "[]", web ?: "[]")
            airingCache[key] = raw
        }
        val out = ArrayList<Pair<Show, Episode>>()
        listOf(raw.tv, raw.web).forEachIndexed { which, text ->
            runCatching {
                for (el in json.parseToJsonElement(text).jsonArray) {
                    val o = el as? JsonObject ?: continue
                    val showJson = o.obj("show") ?: o.obj("_embedded")?.obj("show") ?: continue
                    if (which == 1) {
                        // Streaming: services in this country, or everywhere.
                        val c = showJson.obj("webChannel")?.obj("country")?.str("code")
                        if (!c.isNullOrBlank() && !c.equals(country, ignoreCase = true)) continue
                    }
                    if (o["number"] == null || o["number"] is JsonNull) continue
                    val show = parseShow(showJson) ?: continue
                    val ep = parseEpisode(o, null) ?: continue
                    out += show to ep
                }
            }
        }
        return out
    }

    private fun parseShow(o: JsonObject): Show? {
        val id = o.int("id").takeIf { it > 0 } ?: return null
        val network = o.obj("network")
        val web = o.obj("webChannel")
        return Show(
            id = id,
            name = o.str("name"),
            status = o.str("status"),
            type = o.str("type"),
            network = network?.str("name")?.ifBlank { null } ?: web?.str("name")?.ifBlank { null },
            streaming = network == null && web != null,
            image = o.obj("image")?.let { it.str("original").ifBlank { it.str("medium") } }?.ifBlank { null }?.https(),
            imdb = o.obj("externals")?.str("imdb")?.ifBlank { null },
            zone = network?.obj("country")?.str("timezone")?.ifBlank { null },
            backdrop = o.obj("_embedded")?.arr("images")?.let { arr ->
                val bgs = arr.mapNotNull { it as? JsonObject }.filter { it.str("type") == "background" }
                val best = bgs.firstOrNull { it["main"]?.jsonPrimitive?.contentOrNull == "true" } ?: bgs.firstOrNull()
                best?.obj("resolutions")?.let { r -> (r.obj("original") ?: r.obj("medium"))?.str("url") }
            }?.ifBlank { null }?.https(),
        )
    }

    private val EPISODE_N = Regex("(?i)episode \\d+")
    private val TAGS = Regex("<[^>]+>")

    private fun parseEpisode(o: JsonObject, seasonEpisodes: Int?): Episode? {
        val season = o["season"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return null
        val number = o["number"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        val stamp = SportsDates.parseIso(o.str("airstamp").ifBlank { null })
        val airDay = dayOf(o.str("airdate"))
        // No time from TVmaze (empty airtime): the day only.
        val timed = o.str("airtime").isNotBlank()
        return Episode(
            season = season,
            number = number,
            name = o.str("name").ifBlank { null }?.takeUnless { it.matches(EPISODE_N) },
            airMs = if (timed) stamp else null,
            dayMs = if (timed && stamp != null) SportsDates.dayOf(stamp) else airDay ?: stamp?.let { SportsDates.dayOf(it) },
            runtime = o.int("runtime").takeIf { it > 0 },
            image = o.obj("image")?.let { it.str("original").ifBlank { it.str("medium") } }?.ifBlank { null }?.https(),
            seasonEpisodes = seasonEpisodes,
            summary = o.str("summary").ifBlank { null }?.let { plain(it) },
        )
    }

    /** TVmaze's summaries are HTML ("<p>…</p>"): just the words. */
    private fun plain(html: String): String? = html.replace(TAGS, "").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").trim().ifBlank { null }

    private fun showDataJson(d: ShowData): JsonObject = buildJsonObject {
        put("at", d.fetchedAt)
        put("show", buildJsonObject {
            put("id", d.show.id); put("name", d.show.name); put("status", d.show.status)
            put("network", d.show.network.orEmpty()); put("streaming", d.show.streaming); put("image", d.show.image.orEmpty())
            put("imdb", d.show.imdb.orEmpty()); put("bg", d.show.backdrop.orEmpty()); put("tz", d.show.zone.orEmpty()); put("type", d.show.type)
        })
        put("episodes", buildJsonArray {
            for (e in d.episodes) add(buildJsonObject {
                put("s", e.season); put("e", e.number); put("name", e.name.orEmpty())
                put("ms", e.airMs ?: 0L); put("day", e.dayMs ?: 0L)
                put("rt", e.runtime ?: 0); put("img", e.image.orEmpty()); put("of", e.seasonEpisodes ?: 0); put("sum", e.summary.orEmpty())
            })
        })
    }

    private fun parseShowData(o: JsonObject): ShowData {
        val s = o.obj("show") ?: error("no show")
        val show = Show(
            id = s.int("id"), name = s.str("name"), status = s.str("status"),
            network = s.str("network").ifBlank { null }, streaming = s["streaming"]?.jsonPrimitive?.contentOrNull == "true",
            image = s.str("image").ifBlank { null }, imdb = s.str("imdb").ifBlank { null },
            backdrop = s.str("bg").ifBlank { null }, zone = s.str("tz").ifBlank { null }, type = s.str("type"),
        )
        val eps = (o.arr("episodes") ?: JsonArray(emptyList())).mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val ms = e["ms"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            Episode(
                season = e.int("s"), number = e.int("e"), name = e.str("name").ifBlank { null },
                airMs = ms,
                // Worked out again here, so a move to another time zone puts it on the right day.
                dayMs = ms?.let { SportsDates.dayOf(it) } ?: e["day"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
                runtime = e.int("rt").takeIf { it > 0 }, image = e.str("img").ifBlank { null },
                seasonEpisodes = e.int("of").takeIf { it > 0 }, summary = e.str("sum").ifBlank { null },
            )
        }
        return ShowData(show, eps, o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    // ---- TMDB

    private var backdrops: MutableMap<String, String>? = null
    private var backdropsDirty = false

    private suspend fun backdropMap(): MutableMap<String, String> {
        backdrops?.let { return it }
        val m = HashMap<String, String>()
        readJson("backdrops.json")?.forEach { (k, v) -> m[k] = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }
        backdrops = m
        return m
    }

    /** The show's backdrop already looked up (null: not looked up yet, or TMDB had none). */
    suspend fun knownBackdrop(imdb: String?): String? = imdb?.let { backdropMap()[it]?.ifBlank { null } }

    suspend fun backdropLooked(imdb: String?): Boolean = imdb == null || backdropMap().containsKey(imdb)

    /** The show's backdrop from TMDB (w1280), looked up once and kept. */
    suspend fun showBackdrop(imdb: String?): String? {
        if (imdb == null || !imdb.startsWith("tt")) return null
        if (backdropLooked(imdb)) return knownBackdrop(imdb)
        val found = tmdb("/find/$imdb?external_source=imdb_id") ?: return null
        val r = (found.arr("tv_results")?.firstOrNull() ?: found.arr("movie_results")?.firstOrNull()) as? JsonObject
        val url = r?.str("backdrop_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" }
        backdropMap()[imdb] = url.orEmpty()
        backdropsDirty = true
        return url
    }

    suspend fun saveBackdrops() {
        if (!backdropsDirty) return
        backdropsDirty = false
        val m = backdropMap()
        writeJson("backdrops.json", buildJsonObject { for ((k, v) in m) put(k, v) })
    }

    /** TheTVDB's id for a show, from TMDB (TVmaze knows shows by it too). */
    private suspend fun tmdbTvdbId(imdb: String?, tmdbId: Int?): Int? {
        val id = tmdbId ?: imdb?.let { (tmdb("/find/$it?external_source=imdb_id")?.arr("tv_results")?.firstOrNull() as? JsonObject)?.int("id") }
        if (id == null || id <= 0) return null
        return tmdb("/tv/$id/external_ids")?.int("tvdb_id")?.takeIf { it > 0 }
    }

    private var movies: MutableMap<String, JsonObject>? = null
    private var moviesDirty = false

    private suspend fun movieMap(): MutableMap<String, JsonObject> {
        movies?.let { return it }
        val m = HashMap<String, JsonObject>()
        readJson("movies.json")?.forEach { (k, v) -> (v as? JsonObject)?.let { m[k] = it } }
        movies = m
        return m
    }

    suspend fun saveMovies() {
        if (!moviesDirty) return
        moviesDirty = false
        val m = movieMap()
        writeJson("movies.json", JsonObject(m.toMap()))
    }

    /** A movie's release dates in [country]: theatrical, digital (at home) and disc. */
    suspend fun movieDates(imdb: String?, tmdbId: Int?, country: String, cachedOnly: Boolean = false): MovieDates? {
        val key = (imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null) + "|" + country
        val map = movieMap()
        val now = LiveTvClock.nowMs()
        map[key]?.let { o ->
            val at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L
            fun ms(k: String) = o["_$k"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val dates = MovieDates(ms("t"), ms("d"), ms("b"), o.str("title").ifBlank { null }, o.str("poster").ifBlank { null },
                o.str("bg").ifBlank { null }, o.str("about").ifBlank { null })
            // Out at home long ago: nothing will change. Otherwise checked once a day.
            val settled = dates.digitalMs != null && dates.digitalMs < now - 60 * DAY
            if (cachedOnly || now - at < (if (settled) 30 * DAY else DAY)) return dates.takeIf { o.str("ok") != "false" }
        }
        if (cachedOnly) return null
        val id = tmdbId ?: imdb?.let { (tmdb("/find/$it?external_source=imdb_id")?.arr("movie_results")?.firstOrNull() as? JsonObject)?.int("id") }
        val result: MovieDates? = if (id == null || id <= 0) null else {
            val rd = tmdb("/movie/$id/release_dates")
            val detail = tmdb("/movie/$id")
            var theater: Long? = null; var digital: Long? = null; var disc: Long? = null
            val results = rd?.arr("results")
            fun read(code: String) {
                for (el in results.orEmpty()) {
                    val r = el as? JsonObject ?: continue
                    if (!r.str("iso_3166_1").equals(code, ignoreCase = true)) continue
                    for (d in r.arr("release_dates").orEmpty()) {
                        val dobj = d as? JsonObject ?: continue
                        val day = dayOf(dobj.str("release_date").take(10)) ?: continue
                        when (dobj.int("type")) {
                            2, 3 -> if (theater == null || day < theater!!) theater = day
                            4 -> if (digital == null || day < digital!!) digital = day
                            5 -> if (disc == null || day < disc!!) disc = day
                        }
                    }
                }
            }
            read(country)
            if (theater == null && digital == null && country != "US") read("US")
            if (theater == null) theater = dayOf(detail?.str("release_date"))
            MovieDates(
                theater, digital, disc,
                detail?.str("title")?.ifBlank { null },
                detail?.str("poster_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
                detail?.str("backdrop_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" },
                detail?.str("overview")?.ifBlank { null },
            )
        }
        map[key] = buildJsonObject {
            put("at", now); put("ok", (result != null).toString())
            put("_t", result?.theaterMs ?: 0L); put("_d", result?.digitalMs ?: 0L); put("_b", result?.discMs ?: 0L)
            put("title", result?.title.orEmpty()); put("poster", result?.poster.orEmpty())
            put("bg", result?.backdrop.orEmpty()); put("about", result?.overview.orEmpty())
        }
        moviesDirty = true
        return result
    }
}
