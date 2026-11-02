package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong

/** A goal, touchdown or basket worth listing on the game page. */
data class ScoringPlay(val clock: String, val teamAbbr: String?, val text: String, val awayScore: String?, val homeScore: String?)

/**
 * The free data sources: ESPN's public scoreboard feed (scores, clocks, logos, team colors and
 * US networks; no key) and sporting-events.org (dates of events ESPN doesn't cover).
 */
internal class SportsFeeds {

    private suspend fun get(url: String): String = SportsPlatform.get(url)

    // ------------------------------------------------------------------ ESPN

    /** Raw scoreboard for [league] over the days [fromDay]..[toDay] ("yyyyMMdd", US Eastern days). */
    suspend fun scoreboardRaw(league: League, fromDay: String, toDay: String): String =
        if (OtherFeeds.handles(league.espnPath)) OtherFeeds.scoreboard(league.espnPath!!, fromDay, toDay) { get(it) }
        else get("$ESPN${league.espnPath}/scoreboard?dates=$fromDay-$toDay&limit=1000${league.espnQuery}")

    /** One day only (for leagues whose feed won't take a range of days). */
    suspend fun scoreboardDayRaw(league: League, day: String): String =
        if (OtherFeeds.handles(league.espnPath)) OtherFeeds.scoreboard(league.espnPath!!, day, day) { get(it) }
        else get("$ESPN${league.espnPath}/scoreboard?dates=$day&limit=1000${league.espnQuery}")

    /** The feed's own pick of days (no date asked for): the last resort. */
    suspend fun scoreboardDefaultRaw(league: League): String =
        if (OtherFeeds.handles(league.espnPath)) OtherFeeds.scoreboardNow(league.espnPath!!) { get(it) }
        else get("$ESPN${league.espnPath}/scoreboard?limit=1000${league.espnQuery}")

    suspend fun teamsRaw(league: League): String =
        if (OtherFeeds.handles(league.espnPath)) OtherFeeds.teams(league.espnPath!!) { get(it) }
        else get("$ESPN${league.espnPath}/teams?limit=1000")

    /**
     * A team's season from ESPN: results and games to come (US sports), or, for football with
     * [fixtures], the games still to play (without it, the results so far).
     */
    suspend fun teamScheduleRaw(leaguePath: String, teamId: String, fixtures: Boolean): String =
        if (OtherFeeds.handles(leaguePath)) { if (fixtures) "{}" else OtherFeeds.teamSchedule(leaguePath, teamId) { get(it) } }
        else get("$ESPN$leaguePath/teams/$teamId/schedule" + if (fixtures) "?fixture=true" else "")

    suspend fun summary(league: League, espnId: String): List<ScoringPlay> = parseSummary(summaryJson(league, espnId))

    /** A game's whole summary (scoring, stats, leaders, standings, venue…). */
    suspend fun summaryJson(league: League, espnId: String): JSONObject {
        if (!OtherFeeds.espnExtras(league.espnPath)) throw FeedError("no game page for this league")
        if (OtherFeeds.handles(league.espnPath)) return OtherFeeds.summary(league.espnPath!!, espnId) { get(it) }
        return JSONObject(get("$ESPN${league.espnPath}/summary?event=$espnId"))
    }

    /** A league table (ESPN's standings address, see [Standings.espnUrl]). */
    suspend fun standingsRaw(url: String): String {
        // HockeyTech and Minor League Baseball: their own tables, in ESPN's shape.
        val path = Regex("""/sports/(.+?)/standings""").find(url)?.groupValues?.get(1)
        if (OtherFeeds.handles(path)) return OtherFeeds.standings(path!!) { get(it) } ?: throw FeedError("no table")
        return get(url)
    }

    // ------------------------------------------------------------------ sporting-events.org

    suspend fun fixturesRaw(slug: String): String = get("https://sporting-events.org/data/$slug.json")

    /**
     * The wrestling schedule (WWE, AEW…): a file a daily GitHub job makes from TheSportsDB, so
     * the app never asks TheSportsDB itself.
     */
    suspend fun wrestlingRaw(): String = get(WRESTLING_URL)

    /**
     * Every sport from TheSportsDB (the same GitHub job, from its leagues.txt): the leagues and
     * their teams. The sportsdb folder; the older football folder while that's all there is.
     */
    suspend fun footballIndexRaw(): String =
        runCatching { get(DATA_URL + "sportsdb/index.json").also { tsdbFolder = "sportsdb/" } }.getOrElse {
            get(DATA_URL + "football/index.json").also { tsdbFolder = "football/" }
        }

    /** One league's games or events (a few weeks back to the end of the season). */
    suspend fun footballLeagueRaw(tsdbId: String): String {
        val first = tsdbFolder
        return runCatching { get(DATA_URL + first + "$tsdbId.json") }.getOrElse { e ->
            if (e.message?.startsWith("HTTP 404") != true) throw e
            val other = if (first == "sportsdb/") "football/" else "sportsdb/"
            get(DATA_URL + other + "$tsdbId.json").also { tsdbFolder = other }
        }
    }

    @Volatile private var tsdbFolder = "sportsdb/"

    /** A TheSportsDB game's goals, cards, stats and line-ups (the nuvio-data job writes them). */
    suspend fun tsdbGameRaw(tsdbEventId: String): String = get(DATA_URL + "sportsdb/games/$tsdbEventId.json")

    // ------------------------------------------------------------------ the shared ESPN schedule cache

    /**
     * One league's whole shared-cache file: a daily GitHub job asks ESPN so every TV doesn't have
     * to. Null when the job hasn't reached this league yet, or nuvio-data can't be reached.
     */
    suspend fun espnSharedRaw(leagueId: String): String? = runCatching { get(DATA_URL + "espn/" + safeFileId(leagueId) + ".json") }.getOrNull()

    /**
     * One day's ESPN scoreboard JSON out of a shared-cache file (as [espnSharedRaw] returns), if
     * the shared job has reached that day and it isn't older than [maxAgeMs]. Null otherwise
     * (the day isn't in the file yet, or it's gone stale) — the caller's cue to ask ESPN itself.
     */
    fun espnSharedDay(sharedRaw: String?, day: String, maxAgeMs: Long, now: Long): String? {
        if (sharedRaw == null) return null
        return runCatching {
            val d = JSONObject(sharedRaw).optJSONObject("days")?.optJSONObject(day) ?: return null
            if (now - d.optLong("fetchedAt", 0L) > maxAgeMs) null else d.optString("raw").ifBlank { null }
        }.getOrNull()
    }

    companion object {
        private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/"
        const val DATA_URL = "https://raw.githubusercontent.com/homelessbrian/nuvio-data/main/"
        const val WRESTLING_URL = DATA_URL + "wrestling.json"

        /** A league id as the shared ESPN cache file is named ("football/nfl" -> "football_nfl"). */
        fun safeFileId(id: String): String = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

        /**
         * A league from the nuvio-data files, with its teams (for Pick Your Teams). [tsdbId] is
         * its TheSportsDB id; for one that fills one of the app's own leagues (WWE, MotoGP...)
         * [league] is that league and [alias] is true.
         */
        class TsdbLeague(val league: League, val teams: List<TeamInfo>, val tsdbId: String = league.id.removePrefix("tsdb/"), val alias: Boolean = false)

        /** Club colours aren't in the data: a steady colour per club, from its name. */
        private val CLUB_COLORS = longArrayOf(
            0xFFB71C1C, 0xFF0D47A1, 0xFF1B5E20, 0xFF4A148C, 0xFFE65100, 0xFF006064,
            0xFF880E4F, 0xFF263238, 0xFF33691E, 0xFF1A237E, 0xFFBF360C, 0xFF3E2723
        )
        private fun clubColor(name: String): Long = CLUB_COLORS[floorMod(name.lowercase().hashCode(), CLUB_COLORS.size)]

        private fun clubAbbr(name: String): String {
            val words = name.split(' ', '-').filter { it.isNotBlank() && it.lowercase() !in setOf("fc", "afc", "town", "city", "united", "the", "and", "&") }
            return when {
                words.isEmpty() -> name.take(3).uppercase()
                words.size == 1 -> words[0].take(3).uppercase()
                else -> words.take(3).joinToString("") { it.take(1) }.uppercase().padEnd(3, words[0].getOrElse(1) { 'X' }.uppercaseChar())
            }
        }

        fun parseFootballIndex(raw: String): List<TsdbLeague> {
            val arr = JSONObject(raw).optJSONArray("leagues") ?: return emptyList()
            val out = ArrayList<TsdbLeague>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tid = o.str("id") ?: continue
                val name = o.str("name") ?: continue
                // One that fills one of the app's own leagues ("app": "guide/wwe"): that league,
                // with its schedule, logo and show logos from here.
                val own = o.str("app")?.let { SportsCatalog.leagues.firstOrNull { l -> l.id == it } }
                val brandsFor = own?.id ?: "tsdb/$tid"
                o.optJSONArray("brands")?.let { b ->
                    for (j in 0 until b.length()) {
                        val br = b.optJSONObject(j) ?: continue
                        val word = br.str("word")?.let { SportsCatalog.norm(it).trim() }?.takeIf { it.isNotEmpty() } ?: continue
                        val url = br.str("badge")?.takeIf { it.startsWith("https://") } ?: continue
                        brandLogos["$brandsFor|$word"] = url
                    }
                }
                if (own != null) {
                    o.str("badge")?.takeIf { it.startsWith("https://") }?.let { leagueLogos[own.id] = it }
                    out += TsdbLeague(own, emptyList(), tid, alias = true)
                    continue
                }
                // Any sport (rugby league, cricket, racing…): the sport, where it's watched, how
                // guides name it and how long a game runs come with the league.
                val sport = o.str("sport")?.let { s -> Sport.entries.firstOrNull { it.name == s } ?: Sport.OTHER } ?: Sport.FOOTBALL
                val words = o.optJSONArray("words")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
                val regions = o.optJSONArray("regions")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }.toSet() }.orEmpty()
                val league = League(
                    id = "tsdb/$tid", sport = sport, name = name, short = o.str("short") ?: name.take(6).uppercase(),
                    kind = if (o.str("kind") == "event") LeagueKind.EVENT else LeagueKind.TEAMS,
                    guideWords = (listOf(name) + words).distinct(), regions = regions,
                    minutes = o.optInt("minutes", 115).takeIf { it > 0 } ?: 115, color = 0xFF2E3B4E
                )
                o.str("badge")?.takeIf { it.startsWith("https://") }?.let { leagueLogos[league.id] = it }
                val teams = o.optJSONArray("teams")?.let { ta ->
                    (0 until ta.length()).mapNotNull { k ->
                        val t = ta.optJSONObject(k) ?: return@mapNotNull null
                        val id = t.str("id") ?: return@mapNotNull null
                        val n = t.str("name") ?: return@mapNotNull null
                        clubTeam(league.id, id, n, t.str("badge")?.takeIf { it.startsWith("https://") })
                    }
                }.orEmpty()
                out += TsdbLeague(league, teams)
            }
            return out
        }

        private fun clubTeam(leagueId: String, id: String, name: String, badge: String?) = TeamInfo(
            leagueId = leagueId, id = id, name = name, fullName = name, abbr = clubAbbr(name),
            location = null, color = clubColor(name), altColor = null, logo = badge
        )

        /** A league's games or events (races, fight cards, wrestling shows), as the Sports page shows them. */
        fun parseFootballLeague(league: League, raw: String, teams: Map<String, TeamInfo>, now: Long = LiveTvClock.nowMs()): List<SportsEvent> {
            val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.str("id") ?: continue
                val hasTime = e.optBoolean("hasTime", true)
                val start = if (hasTime) parseIso(e.str("start")) ?: continue else {
                    val p = e.str("start")?.take(10)?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                    if (p.size != 3) continue
                    SportsDates.localDate(p[0], p[1], p[2])
                }
                // An event with a name instead of two teams (a race, a fight card, a wrestling show).
                if (e.str("home") == null) {
                    val raw0 = e.str("name") ?: continue
                    // "NXT - October 20th 2026" -> "NXT" (the card shows the date).
                    val name = if (league.sport == Sport.WRESTLING) withoutAirDate(raw0) else raw0
                    val end = start + league.minutes * 60_000L
                    out += SportsEvent(
                        id = "tsdb:${league.id}:$id",
                        leagueId = league.id,
                        startMs = start,
                        hasTime = hasTime,
                        state = when {
                            !hasTime -> GameState.PRE
                            now >= end -> GameState.POST
                            now >= start -> GameState.LIVE
                            else -> GameState.PRE
                        },
                        title = name,
                        subtitle = listOfNotNull(e.str("venue"), e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                        status = null,
                        away = null, home = null,
                        source = EventSource.FIXTURES,
                        endMs = end,
                        note = if (!hasTime) "Time to be confirmed" else listOfNotNull(e.str("venue"), e.str("city")).joinToString(" · ").ifBlank { null },
                        about = e.str("about"),
                        // A race's finishing order (or a fight card's results), once it's in.
                        standings = e.optJSONArray("results")?.let { r ->
                            (0 until r.length()).mapNotNull { r.optJSONObject(it) }.mapNotNull { x ->
                                val n = x.str("name") ?: return@mapNotNull null
                                Standing(x.optInt("pos", 0).takeIf { it > 0 }?.toString() ?: "", n, x.str("detail"))
                            }
                        }.orEmpty()
                    )
                    continue
                }
                val state = when (e.str("state")) {
                    "in" -> GameState.LIVE
                    "post" -> GameState.POST
                    "off" -> GameState.OFF
                    else -> GameState.PRE
                }
                fun side(nameKey: String, idKey: String, scoreKey: String, home: Boolean): Side? {
                    val n = e.str(nameKey) ?: return null
                    val tid = e.str(idKey) ?: n
                    val team = teams[tid] ?: clubTeam(league.id, tid, n, null)
                    val score = if (state == GameState.LIVE || state == GameState.POST) e.opt(scoreKey)?.toString()?.takeIf { it.isNotBlank() && it != "null" } else null
                    return Side(team = team, score = score, record = null, winner = false, home = home)
                }
                val h = side("home", "homeId", "hs", true) ?: continue
                val a = side("away", "awayId", "as", false) ?: continue
                val hs = h.score?.toIntOrNull()
                val aS = a.score?.toIntOrNull()
                val done = state == GameState.POST && hs != null && aS != null
                val round = e.optInt("round", 0).takeIf { it > 0 }
                out += SportsEvent(
                    id = "tsdb:${league.id}:$id",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = hasTime,
                    state = state,
                    title = "${h.team.name} v ${a.team.name}",
                    subtitle = listOfNotNull(round?.let { "Round $it" }, e.str("venue")).joinToString(" · ").ifBlank { null },
                    status = when (state) {
                        GameState.LIVE -> e.str("status") ?: "Live"
                        GameState.POST -> "FT"
                        GameState.OFF -> e.str("status") ?: "Postponed"
                        GameState.PRE -> null
                    },
                    away = if (done) a.copy(winner = aS!! > hs!!) else a,
                    home = if (done) h.copy(winner = hs!! > aS!!) else h,
                    source = EventSource.FIXTURES,
                    endMs = start + league.minutes * 60_000L,
                    note = if (!hasTime) "Time to be confirmed" else null
                )
            }
            return out
        }

        /**
         * A TheSportsDB game file (see [tsdbGameRaw]) in the shape of ESPN's game summary, so the
         * game page shows its goals, team stats and line-ups like any other game's.
         */
        fun tsdbSummary(raw: String, ev: SportsEvent): JSONObject {
            val g = JSONObject(raw)
            val out = JSONObject()
            val homeAbbr = ev.home?.team?.abbr ?: "HOME"
            val awayAbbr = ev.away?.team?.abbr ?: "AWAY"
            var hs = 0
            var aS = 0
            val plays = JSONArray()
            g.optJSONArray("moments")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = a.optJSONObject(i) ?: continue
                    val kind = m.str("kind").orEmpty()
                    val detail = m.str("detail").orEmpty()
                    // Goals and tries (not cards, not misses).
                    if (!Regex("(?i)goal|try|conversion|penalty").containsMatchIn(kind + " " + detail)) continue
                    if (Regex("(?i)miss|disallow|saved|card").containsMatchIn(kind + " " + detail)) continue
                    val home = m.optBoolean("home")
                    if (home) hs++ else aS++
                    val who = m.str("player") ?: kind
                    val text = who + (m.str("assist")?.let { " (assist: $it)" } ?: "") +
                        (detail.takeIf { it.isNotBlank() && !it.equals("Normal Goal", true) }?.let { " · $it" } ?: "")
                    plays.put(JSONObject()
                        .put("clock", JSONObject().put("displayValue", m.str("min")?.let { "$it'" } ?: ""))
                        .put("team", JSONObject().put("abbreviation", if (home) homeAbbr else awayAbbr))
                        .put("text", text).put("awayScore", aS).put("homeScore", hs))
                }
            }
            out.put("scoringPlays", plays)
            val homeId = ev.home?.team?.id.orEmpty()
            val awayId = ev.away?.team?.id.orEmpty()
            g.optJSONArray("stats")?.let { a ->
                fun side(home: Boolean) = JSONArray((0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { st ->
                    val name = st.str("name") ?: return@mapNotNull null
                    val key = name.lowercase().replace(Regex("[^a-z0-9]+"), "")
                    JSONObject().put("name", key).put("label", name).put("displayValue", st.optString(if (home) "home" else "away"))
                })
                out.put("boxscore", JSONObject().put("teams", JSONArray()
                    .put(JSONObject().put("homeAway", "away").put("team", JSONObject().put("id", awayId)).put("statistics", side(false)))
                    .put(JSONObject().put("homeAway", "home").put("team", JSONObject().put("id", homeId)).put("statistics", side(true)))))
            }
            g.optJSONObject("lineups")?.let { l ->
                val rosters = JSONArray()
                for ((side, id) in listOf("away" to awayId, "home" to homeId)) {
                    val ps = l.optJSONArray(side) ?: continue
                    rosters.put(JSONObject().put("team", JSONObject().put("id", id)).put("roster", JSONArray((0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.map { p ->
                        JSONObject().put("starter", !p.optBoolean("sub")).put("jersey", p.optString("num"))
                            .put("athlete", JSONObject().put("displayName", p.optString("name")))
                    })))
                }
                out.put("rosters", rosters)
            }
            return out
        }

        /** The schedule file's league keys -> our leagues. */
        private fun wrestlingLeague(key: String?): String = when (key) {
            "wwe" -> "guide/wwe"
            "aew" -> "guide/aew"
            "boxing" -> "guide/boxing"
            else -> "guide/wrestling"
        }

        /**
         * The wrestling schedule, by league id. League logos in it (addresses only, on
         * TheSportsDB's image server) go to [leagueLogos]. Shows already over are marked ended;
         * ones on now count as live until about two hours after their start.
         */
        fun parseWrestling(raw: String, now: Long = LiveTvClock.nowMs()): Map<String, List<SportsEvent>> {
            val root = JSONObject(raw)
            root.optJSONArray("leagues")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    val lid = wrestlingLeague(l.str("key"))
                    l.optJSONArray("brands")?.let { b ->
                        for (j in 0 until b.length()) {
                            val br = b.optJSONObject(j) ?: continue
                            val word = br.str("word")?.let { SportsCatalog.norm(it).trim() }?.takeIf { it.isNotEmpty() } ?: continue
                            val url = br.str("badge")?.takeIf { it.startsWith("https://") } ?: continue
                            brandLogos["$lid|$word"] = url
                        }
                    }
                    val badge = l.str("badge")?.takeIf { it.startsWith("https://") } ?: continue
                    leagueLogos[lid] = badge
                }
            }
            val out = HashMap<String, MutableList<SportsEvent>>()
            val arr = root.optJSONArray("events") ?: return out
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                // "NXT - October 20th 2026" -> "NXT" (the card shows the date).
                val name = e.str("name")?.let { withoutAirDate(it) } ?: continue
                val leagueId = wrestlingLeague(e.str("league"))
                val league = SportsCatalog.league(leagueId)
                val hasTime = e.optBoolean("hasTime", true)
                // A date only: that day on the TV's own calendar (not midnight in London).
                val start = if (hasTime) parseIso(e.str("start")) ?: continue else {
                    val p = e.str("start")?.take(10)?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                    if (p.size != 3) continue
                    SportsDates.localDate(p[0], p[1], p[2])
                }
                val end = start + (league?.minutes ?: 120) * 60_000L
                out.getOrPut(leagueId) { ArrayList() } += SportsEvent(
                    id = "tsdb:" + (e.str("id") ?: "$leagueId:$name:$start"),
                    leagueId = leagueId,
                    startMs = start,
                    hasTime = hasTime,
                    state = when {
                        !hasTime -> GameState.PRE
                        now >= end -> GameState.POST
                        now >= start -> GameState.LIVE
                        else -> GameState.PRE
                    },
                    title = name,
                    subtitle = listOfNotNull(e.str("venue"), e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                    status = null,
                    away = null, home = null,
                    source = EventSource.FIXTURES,
                    endMs = end,
                    note = if (!hasTime) "Time to be confirmed" else listOfNotNull(e.str("venue"), e.str("city")).joinToString(" · ").ifBlank { null },
                    about = e.str("about")
                )
            }
            return out
        }

        fun parseIso(s: String?): Long? = SportsDates.parseIso(s)

        fun hexColor(s: String?): Long? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { 0xFF000000L or it }
        }

        private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }

        fun team(league: League, t: JSONObject?, athlete: JSONObject? = null): TeamInfo? {
            if (t != null) {
                val logo = t.str("logo") ?: t.optJSONArray("logos")?.let { pickLogo(it) }
                return TeamInfo(
                    leagueId = league.id,
                    id = t.str("id") ?: return null,
                    name = t.str("shortDisplayName") ?: t.str("name") ?: t.str("displayName") ?: return null,
                    fullName = t.str("displayName") ?: t.str("name") ?: "",
                    abbr = t.str("abbreviation") ?: "",
                    location = t.str("location"),
                    // No colours in the feed (college hockey, small clubs): a steady one from the name.
                    color = hexColor(t.str("color")) ?: (if (hexColor(t.str("alternateColor")) == null)
                        clubColor(t.str("displayName") ?: t.str("name") ?: t.str("id").orEmpty()) else null),
                    altColor = hexColor(t.str("alternateColor")),
                    logo = logo,
                    record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.str("summary")
                )
            }
            if (athlete != null) {
                val name = athlete.str("displayName") ?: athlete.str("fullName") ?: return null
                return TeamInfo(
                    leagueId = league.id,
                    id = athlete.str("id") ?: name,
                    name = athlete.str("shortName") ?: name,
                    fullName = name,
                    abbr = name.split(' ').lastOrNull()?.take(3)?.uppercase() ?: "",
                    location = null,
                    color = null,
                    altColor = null,
                    logo = athlete.optJSONObject("headshot")?.str("href") ?: athlete.optJSONObject("flag")?.str("href")
                )
            }
            return null
        }

        /** The normal logo (not the one made for dark backgrounds: ours sit on a light disc). */
        private fun pickLogo(logos: JSONArray): String? {
            var first: String? = null
            for (i in 0 until logos.length()) {
                val l = logos.optJSONObject(i) ?: continue
                val href = l.str("href") ?: continue
                if (first == null) first = href
                val rel = l.optJSONArray("rel").strings()
                if ("default" in rel && "dark" !in rel) return href
            }
            return first
        }

        private val CLOCK_FIRST = Regex("""^(\d{1,2}:\d{2}(?:\.\d)?)\s*-\s*(.+)$""")

        fun parseTeams(league: League, raw: String): List<TeamInfo> {
            val teams = JSONObject(raw).optJSONArray("sports")?.optJSONObject(0)
                ?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
            return (0 until teams.length()).mapNotNull { i -> team(league, teams.optJSONObject(i)?.optJSONObject("team")) }
                .filter { it.fullName.isNotBlank() }
        }

        /**
         * League logos from the feed (the version made for dark backgrounds when there is one),
         * by league id: shown on the Sports page's game cards.
         */
        val leagueLogos = SyncMap<String, String>()

        /**
         * Logos for shows with their own branding inside a league ("guide/wwe|nxt" -> NXT's logo),
         * from TheSportsDB by address only, like [leagueLogos].
         */
        val brandLogos = SyncMap<String, String>()

        /** The logo of the show brand [title] names, if [leagueId] has one ("NXT #860" -> NXT's). */
        fun brandLogo(leagueId: String, title: String): String? {
            if (brandLogos.isEmpty) return null
            val t = SportsCatalog.norm(title)
            val prefix = "$leagueId|"
            for ((k, v) in brandLogos.snapshot()) {
                if (k.startsWith(prefix) && SportsCatalog.hasWord(t, k.substring(prefix.length))) return v
            }
            return null
        }

        /**
         * A team's schedule from ESPN. Each game goes under its own competition when the feed
         * says which (a Champions League game in Arsenal's schedule), otherwise under [league].
         */
        fun parseSchedule(league: League, raw: String): List<SportsEvent> {
            val events = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val prefix = league.espnPath?.substringBefore('/') ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val slug = ev.optJSONObject("league")?.str("slug")
                    ?: ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("league")?.str("slug")
                val own = slug?.let { SportsCatalog.league("$prefix/$it") }?.takeIf { it.espnPath != null } ?: league
                runCatching { parseEvent(own, ev) }.getOrNull()?.let { out += it }
            }
            return out
        }

        fun parseScoreboard(league: League, raw: String): List<SportsEvent> {
            val root = JSONObject(raw)
            if (!leagueLogos.containsKey(league.id)) runCatching {
                val logos = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")
                if (logos != null && logos.length() > 0) {
                    var pick: String? = null
                    for (i in 0 until logos.length()) {
                        val l = logos.optJSONObject(i) ?: continue
                        val href = l.optString("href").takeIf { it.startsWith("http") } ?: continue
                        val rel = l.optJSONArray("rel")?.toString().orEmpty()
                        if (pick == null) pick = href
                        if ("dark" in rel) { pick = href; break }
                    }
                    pick?.let { leagueLogos[league.id] = it }
                }
            }
            val events = root.optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                runCatching { parseEvent(league, ev) }.getOrNull()?.let { out += it }
            }
            return out
        }

        private fun parseEvent(league: League, ev: JSONObject): SportsEvent? {
            val espnId = ev.str("id") ?: return null
            val start = parseIso(ev.str("date")) ?: return null
            val end = parseIso(ev.str("endDate"))
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
            // An event with several sessions (practice, qualifying, the race): the event's own status.
            val st = if (league.kind == LeagueKind.EVENT) ev.optJSONObject("status") ?: comp?.optJSONObject("status")
            else comp?.optJSONObject("status") ?: ev.optJSONObject("status")
            val type = st?.optJSONObject("type")
            val stateRaw = type?.str("state")
            val name = type?.str("name").orEmpty()
            val state = when {
                Regex("POSTPONED|CANCEL|SUSPENDED|FORFEIT|ABANDON").containsMatchIn(name) -> GameState.OFF
                stateRaw == "in" -> GameState.LIVE
                stateRaw == "post" -> GameState.POST
                else -> GameState.PRE
            }
            // Cricket says where the match stands ("India need 45 runs", "Australia won by 5 wickets").
            val short = (if (league.sport == Sport.CRICKET) st?.str("summary") ?: ev.optJSONObject("status")?.str("summary") else null)
                ?: type?.str("shortDetail") ?: type?.str("detail")
            val status = when (state) {
                GameState.LIVE -> short?.let { s -> CLOCK_FIRST.matchEntire(s)?.let { "${it.groupValues[2]} · ${it.groupValues[1]}" } ?: s }
                GameState.POST -> short ?: "Final"
                GameState.OFF -> type?.str("description") ?: "Postponed"
                GameState.PRE -> null
            }
            val period = st?.optInt("period", 0) ?: 0
            val clock = st?.optDouble("clock", Double.NaN) ?: Double.NaN
            val progress = if (state == GameState.LIVE) progressOf(league, period, clock, st?.str("displayClock")) else null

            val broadcasts = LinkedHashSet<String>()
            comp?.optJSONArray("broadcasts")?.let { a ->
                for (b in 0 until a.length()) broadcasts += a.optJSONObject(b)?.optJSONArray("names").strings()
            }
            comp?.optJSONArray("geoBroadcasts")?.let { a ->
                for (b in 0 until a.length()) a.optJSONObject(b)?.optJSONObject("media")?.str("shortName")?.let { broadcasts += it }
            }
            val venue = comp?.optJSONObject("venue")?.str("fullName") ?: ev.optJSONObject("circuit")?.str("fullName")
            val week = ev.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 }?.let { "Week $it" }
            val headline = comp?.optJSONArray("notes")?.optJSONObject(0)?.str("headline")

            if (league.kind == LeagueKind.EVENT) {
                val multiDay = end != null && end - start > 20 * 3_600_000L
                // A tournament that runs for days: the feed says "final" once a day's play is
                // done (tennis does every evening). It isn't over until its last day.
                // Likewise "not started" between a tournament's first day and its last (no match on
                // yet that day): it's under way.
                val nowMs = LiveTvClock.nowMs()
                // (The end date is the start of the last day, so the last day counts too.)
                val underWay = multiDay && end != null && end + 24 * 3_600_000L > nowMs && start <= nowMs
                val evState = if (underWay && (state == GameState.POST || state == GameState.PRE)) GameState.LIVE else state
                return SportsEvent(
                    id = "espn:${league.id}:$espnId",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = !multiDay,
                    state = evState,
                    title = ev.str("name") ?: ev.str("shortName") ?: league.name,
                    subtitle = listOfNotNull(headline, venue).joinToString(" · ").ifBlank { null },
                    status = status,
                    away = null, home = null,
                    broadcasts = broadcasts.toList(),
                    espnId = espnId,
                    endMs = end,
                    note = if (multiDay && end != null) "Until " + SportsDates.dayLabel(end) else null,
                    standings = runCatching {
                        when {
                            // Tennis matches, a fight card's bouts: two at a time.
                            league.sport == Sport.TENNIS || league.sport == Sport.FIGHTING -> matchesOf(ev, mainFirst = league.sport == Sport.FIGHTING)
                            state == GameState.PRE -> emptyList()
                            else -> standingsOf(ev)
                        }
                    }.getOrDefault(emptyList())
                )
            }

            val competitors = comp?.optJSONArray("competitors") ?: return null
            var home: Side? = null
            var away: Side? = null
            for (c in 0 until competitors.length()) {
                val co = competitors.optJSONObject(c) ?: continue
                val team = team(league, co.optJSONObject("team"), co.optJSONObject("athlete")) ?: continue
                val scoreAny = co.opt("score")
                val score = when (scoreAny) {
                    is JSONObject -> scoreAny.str("displayValue")
                    is String -> scoreAny.takeIf { it.isNotBlank() }
                    is Number -> scoreAny.toInt().toString()
                    else -> null
                }?.takeIf { state != GameState.PRE }
                val periods = co.optJSONArray("linescores")?.let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        val l = a.optJSONObject(k) ?: return@mapNotNull null
                        l.str("displayValue") ?: l.optDouble("value", Double.NaN).takeIf { !it.isNaN() }?.toInt()?.toString()
                    }
                }.orEmpty()
                val side = Side(
                    team = team,
                    score = score,
                    record = co.optJSONArray("records")?.optJSONObject(0)?.str("summary"),
                    winner = co.optBoolean("winner", false),
                    home = co.str("homeAway") == "home",
                    periods = periods
                )
                if (side.home && home == null) home = side else if (away == null) away = side else if (home == null) home = side
            }
            val h: Side = home ?: return null
            val a: Side = away ?: return null

            val situation = comp?.optJSONObject("situation")?.let { s ->
                val dd = s.str("downDistanceText") ?: s.str("shortDownDistanceText")
                val pos = s.str("possession")
                val who = listOf(h, a).firstOrNull { it.team.id == pos }?.team?.abbr
                when {
                    dd != null && who != null -> "$who ball · $dd"
                    dd != null -> dd
                    else -> null
                }
            }?.takeIf { state == GameState.LIVE }

            val sep = if (league.sport.homeFirst) " v " else " at "
            val title = if (league.sport.homeFirst) "${h.team.name}$sep${a.team.name}" else "${a.team.name}$sep${h.team.name}"
            return SportsEvent(
                id = "espn:${league.id}:$espnId",
                leagueId = league.id,
                startMs = start,
                hasTime = true,
                state = state,
                title = title,
                subtitle = listOfNotNull(headline, week, venue).joinToString(" · ").ifBlank { null },
                status = status,
                away = a,
                home = h,
                broadcasts = broadcasts.toList(),
                situation = situation,
                progress = progress,
                espnId = espnId
            )
        }

        /**
         * A race's finishing order (or the running order while it's on), a golf leaderboard: from
         * the race itself when the event has practice and qualifying too, otherwise the session
         * with the most drivers or players.
         */
        private fun standingsOf(ev: JSONObject): List<Standing> {
            val comps = ev.optJSONArray("competitions") ?: return emptyList()
            val all = (0 until comps.length()).mapNotNull { comps.optJSONObject(it) }
                .filter { (it.optJSONArray("competitors")?.length() ?: 0) >= 3 }
            if (all.isEmpty()) return emptyList()
            fun isRace(c: JSONObject): Boolean {
                val t = c.optJSONObject("type")
                val words = listOfNotNull(t?.str("abbreviation"), t?.str("text"), t?.str("name")).joinToString(" ").lowercase()
                return Regex("""\brace\b""").containsMatchIn(words) || words.trim() == "r"
            }
            val started = all.filter { c -> c.optJSONObject("status")?.optJSONObject("type")?.str("state") in setOf("in", "post") }
            val pick = started.lastOrNull { isRace(it) } ?: all.lastOrNull { isRace(it) && it in started }
                ?: started.maxByOrNull { it.optJSONArray("competitors")?.length() ?: 0 } ?: return emptyList()
            val list = pick.optJSONArray("competitors") ?: return emptyList()
            val rows = (0 until list.length()).mapNotNull { i ->
                val co = list.optJSONObject(i) ?: return@mapNotNull null
                val name = co.optJSONObject("athlete")?.let { it.str("displayName") ?: it.str("shortName") ?: it.str("fullName") }
                    ?: co.optJSONObject("team")?.let { it.str("displayName") ?: it.str("name") }
                    ?: return@mapNotNull null
                val order = co.optInt("order", 0).takeIf { it > 0 } ?: (i + 1)
                val score = when (val sc = co.opt("score")) {
                    is JSONObject -> sc.str("displayValue")
                    is String -> sc.takeIf { it.isNotBlank() }
                    else -> null
                }?.takeIf { it != "0" && it != "-" }
                order to Standing(order.toString(), name, score)
            }
            return rows.sortedBy { it.first }.map { it.second }.take(20)
        }

        /**
         * A tennis tournament's matches (the feed groups them by draw: men's singles, women's…):
         * the ones on now, then today's results, then the next ones. "Sinner def. Rune", "6-4 6-3".
         */
        private fun matchesOf(ev: JSONObject, mainFirst: Boolean = false): List<Standing> {
            val comps = ArrayList<JSONObject>()
            ev.optJSONArray("groupings")?.let { g ->
                for (i in 0 until g.length()) {
                    val cs = g.optJSONObject(i)?.optJSONArray("competitions") ?: continue
                    for (j in 0 until cs.length()) cs.optJSONObject(j)?.let { comps += it }
                }
            }
            ev.optJSONArray("competitions")?.let { cs -> for (j in 0 until cs.length()) cs.optJSONObject(j)?.let { comps += it } }
            class M(val state: String, val at: Long, val line: Standing)
            val now = LiveTvClock.nowMs()
            val all = comps.mapNotNull { c ->
                val cos = c.optJSONArray("competitors") ?: return@mapNotNull null
                if (cos.length() != 2) return@mapNotNull null
                fun name(co: JSONObject?) = co?.optJSONObject("athlete")?.let { it.str("shortName") ?: it.str("displayName") }
                    ?: co?.optJSONObject("team")?.str("displayName")
                val a = cos.optJSONObject(0)
                val b = cos.optJSONObject(1)
                val an = name(a) ?: return@mapNotNull null
                val bn = name(b) ?: return@mapNotNull null
                val st = c.optJSONObject("status")?.optJSONObject("type")
                val state = st?.str("state") ?: "pre"
                val at = parseIso(c.str("date") ?: c.str("startDate")) ?: 0L
                fun sets(x: JSONObject?) = x?.optJSONArray("linescores")?.let { ls ->
                    (0 until ls.length()).mapNotNull { k -> ls.optJSONObject(k)?.optDouble("value", Double.NaN)?.takeIf { !it.isNaN() }?.toInt() }
                }.orEmpty()
                val aw = a?.optBoolean("winner", false) == true
                val bw = b?.optBoolean("winner", false) == true
                // The winner first: "Sinner def. Rune 6-4 6-3".
                val first = if (bw) bn else an
                val second = if (bw) an else bn
                val fs = if (bw) sets(b) else sets(a)
                val ss = if (bw) sets(a) else sets(b)
                val score = fs.zip(ss).joinToString(" ") { (x, y) -> "$x-$y" }
                val round = (c.optJSONObject("round")?.str("displayName") ?: c.optJSONObject("type")?.str("abbreviation"))?.let { r ->
                    // Short, for the narrow column: "Quarterfinal" -> "QF", "Round of 16" -> "R16".
                    when {
                        r.contains("quarter", true) -> "QF"
                        r.contains("semi", true) -> "SF"
                        r.equals("final", true) || r.equals("finals", true) -> "F"
                        Regex("""(\d+)""").find(r) != null && r.length > 4 -> "R" + Regex("""(\d+)""").find(r)!!.value
                        else -> r.take(4)
                    }
                }
                val line = when (state) {
                    "post" -> Standing(round.orEmpty(), if (aw || bw) "$first def. $second" else "$first v $second", score.ifBlank { st?.str("shortDetail") })
                    "in" -> Standing("LIVE", "$an v $bn", score.ifBlank { st?.str("shortDetail") })
                    else -> Standing(round.orEmpty(), "$an v $bn", if (at > 0) SportsDates.weekdayLabel(at) + " " + clockText(at) else null)
                }
                M(state, at, line)
            }
            val live = all.filter { it.state == "in" }
            val done = all.filter { it.state == "post" && now - it.at < 30 * 3_600_000L }.sortedByDescending { it.at }
            // A fight card: the main event first (it's the last bout in the feed).
            if (mainFirst) return (live + all.filter { it.state != "in" }.reversed()).map { it.line }.take(14)
            val next = all.filter { it.state == "pre" && it.at >= now - 3_600_000L }.sortedBy { it.at }
            return (live + done + next).map { it.line }.take(12)
        }

        /** How far through the game the clock is (0..1), from the period and the clock. */
        private fun progressOf(league: League, period: Int, clockSec: Double, displayClock: String?): Float? {
            if (period <= 0) return null
            fun countdown(periods: Int, len: Double): Float? {
                if (clockSec.isNaN()) return null
                val done = (period - 1).coerceAtMost(periods) * len + (len - clockSec.coerceIn(0.0, len))
                return (done / (periods * len)).toFloat().coerceIn(0f, 1f)
            }
            return when (league.sport) {
                Sport.AMERICAN_FOOTBALL -> countdown(4, 900.0)
                Sport.BASKETBALL -> when (league.id) {
                    "basketball/nba" -> countdown(4, 720.0)
                    "basketball/mens-college-basketball" -> countdown(2, 1200.0)
                    else -> countdown(4, 600.0)
                }
                Sport.HOCKEY -> countdown(3, 1200.0)
                Sport.BASEBALL -> ((period - 0.5f) / 9f).coerceIn(0f, 1f)
                Sport.FOOTBALL -> displayClock?.let { Regex("""^(\d+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                    ?.let { (it / 90f).coerceIn(0f, 1f) }
                else -> null
            }
        }

        fun parseSummary(root: JSONObject): List<ScoringPlay> {
            val out = ArrayList<ScoringPlay>()
            root.optJSONArray("scoringPlays")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    val period = p.optJSONObject("period")?.optInt("number", 0) ?: 0
                    val clock = p.optJSONObject("clock")?.str("displayValue")
                    val type = p.optJSONObject("scoringType")?.str("abbreviation") ?: p.optJSONObject("type")?.str("abbreviation")
                    out += ScoringPlay(
                        clock = listOfNotNull(if (period > 0) periodLabel(period) else null, clock).joinToString(" "),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = listOfNotNull(type, p.str("text")).joinToString(" · "),
                        awayScore = p.opt("awayScore")?.toString(),
                        homeScore = p.opt("homeScore")?.toString()
                    )
                }
            }
            // Hockey, baseball, basketball: no scoring list, but every play, with scoring ones marked.
            if (out.isEmpty()) root.optJSONArray("plays")?.let { a ->
                val abbrs = HashMap<String, String>()
                root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")?.let { cs ->
                    for (i in 0 until cs.length()) {
                        val t = cs.optJSONObject(i)?.optJSONObject("team") ?: continue
                        val id = t.str("id") ?: continue
                        abbrs[id] = t.str("abbreviation") ?: t.str("shortDisplayName") ?: continue
                    }
                }
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    if (!p.optBoolean("scoringPlay", false)) continue
                    val per = p.optJSONObject("period")
                    val periodText = (per?.str("displayValue")?.replace(Regex("""\s*(Period|Quarter|Half)$""", RegexOption.IGNORE_CASE), ""))
                        ?: per?.optInt("number", 0)?.takeIf { it > 0 }?.let { periodLabel(it) }
                    val clock = p.optJSONObject("clock")?.str("displayValue")
                    val team = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("id")?.let { id -> abbrs[id] } }
                    out += ScoringPlay(
                        clock = listOfNotNull(periodText, clock?.takeIf { it != "0:00" || periodText == null }).joinToString(" "),
                        teamAbbr = team,
                        text = p.str("text") ?: p.optJSONObject("type")?.str("text") ?: "Score",
                        awayScore = p.opt("awayScore")?.toString(),
                        homeScore = p.opt("homeScore")?.toString()
                    )
                }
            }
            if (out.isEmpty()) root.optJSONArray("keyEvents")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    // Goals, and rugby's tries, conversions, penalty and drop goals (no "scoring" flag there).
                    val kind = p.optJSONObject("type")?.str("text").orEmpty()
                    val goal = p.optBoolean("scoringPlay", false) ||
                        (SCORE_WORDS.containsMatchIn(kind) && !NOT_SCORED.containsMatchIn(kind))
                    if (!goal) continue
                    out += ScoringPlay(
                        clock = p.optJSONObject("clock")?.str("displayValue").orEmpty(),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = p.str("text") ?: p.optJSONObject("type")?.str("text") ?: "Goal",
                        awayScore = null,
                        homeScore = null
                    )
                }
            }
            // Some summaries (college sports) only list a game's moments under its header.
            if (out.isEmpty()) root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("details")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    if (!p.optBoolean("scoringPlay", false)) continue
                    val who = p.optJSONArray("athletesInvolved")?.optJSONObject(0)?.let { it.str("displayName") ?: it.str("shortName") }
                    out += ScoringPlay(
                        clock = p.optJSONObject("clock")?.str("displayValue").orEmpty(),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = listOfNotNull(p.optJSONObject("type")?.str("text"), who).joinToString(" · ").ifBlank { "Score" },
                        awayScore = null,
                        homeScore = null
                    )
                }
            }
            return out
        }
        private val SCORE_WORDS = Regex("""(?i)\b(goal|try|conversion|scored|touchdown)\b""")
        private val NOT_SCORED = Regex("""(?i)\b(missed|no goal|disallowed|saved|attempt)\b""")

        private fun periodLabel(p: Int): String = when (p) {
            1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${p}th"
        }

        /** sporting-events.org: date-only (or dated and timed) events for a sport ESPN doesn't cover. */
        fun parseFixtures(league: League, raw: String): List<SportsEvent> {
            val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val date = e.str("date_utc") ?: continue
                val time = e.str("time_utc")
                val name = e.str("fixture") ?: continue
                val start: Long = if (time != null) {
                    parseIso("${date}T${time.take(5)}Z") ?: continue
                } else {
                    // A date only: that day on the TV's own calendar.
                    val p = date.split('-').mapNotNull { it.toIntOrNull() }
                    if (p.size != 3) continue
                    SportsDates.localDate(p[0], p[1], p[2])
                }
                val home = e.str("home_team")
                val awayTeam = e.str("away_team")
                out += SportsEvent(
                    id = "fx:${league.id}:${e.optString("match", i.toString())}:$date",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = time != null,
                    state = if (e.str("status")?.contains("finish", true) == true) GameState.POST else GameState.PRE,
                    title = if (home != null && awayTeam != null) "$home v $awayTeam" else name,
                    subtitle = listOfNotNull(e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                    status = null,
                    away = null, home = null,
                    broadcasts = listOfNotNull(e.str("broadcast")),
                    source = EventSource.FIXTURES,
                    note = if (time == null) "Starts today" else null
                )
            }
            return out
        }
    }
}

/**
 * Leagues from feeds other than ESPN's usual scoreboard, given to the rest of the Sports page
 * in ESPN's own shape (so scores, cards, My Teams and Pick Your Teams work the same):
 *  - "cricket/<id>": a franchise league (IPL, Big Bash...). ESPN only gives these a season at
 *    a time ("?season=2026"), not a day.
 *  - "cricket/panel": international cricket (Tests, ODIs, T20s) from ESPN's daily score panel;
 *    each tour has its own short-lived league on ESPN, so they're picked from the day's list.
 *  - "hockeytech/<code>": AHL, ECHL, PWHL, OHL, WHL, QMJHL, USHL, from HockeyTech (the stats
 *    system those leagues' own sites use; the keys are the public ones in those sites).
 *  - "milb/<sportId>": Minor League Baseball, from MLB's own stats service.
 * Whole seasons are kept for a few minutes, so a page of days asks once.
 */
internal object OtherFeeds {
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/"
    private const val CRICKET_PANEL = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates="
    private const val HOCKEYTECH = "https://lscluster.hockeytech.com/feed/index.php?feed=modulekit&fmt=json&lang=en"
    private const val MLB = "https://statsapi.mlb.com/api/v1/"
    private val CRICKET_FORMATS = setOf("Test", "ODI", "T20I")

    /** HockeyTech's league codes and the public keys their own sites use. */
    private val HOCKEYTECH_KEYS = mapOf(
        "ahl" to "50c2cd9b5e18e390", "echl" to "2c2b89ea7345cae8", "pwhl" to "446521baf8c38984",
        "ohl" to "f1aa699db3d81487", "whl" to "f1aa699db3d81487", "lhjmq" to "f1aa699db3d81487",
        "ushl" to "e828f89b243dc43f"
    )

    /** These feeds have no team colours: a steady one per team, from its name. */
    private val COLOURS = listOf("B71C1C", "0D47A1", "1B5E20", "4A148C", "E65100", "006064", "880E4F", "263238", "33691E", "1A237E", "BF360C", "3E2723")
    private fun colourOf(name: String) = COLOURS[floorMod(name.lowercase().hashCode(), COLOURS.size)]

    fun handles(path: String?): Boolean = path != null &&
        (path.startsWith("cricket/") || path.startsWith("hockeytech/") || path.startsWith("milb/"))

    /** Whether a game page can be had (international cricket: ESPN has none behind its score panel). */
    fun espnExtras(path: String?): Boolean = path != null && path != "cricket/panel"

    private class Kept(val at: Long, val value: Any)
    private val kept = SyncMap<String, Kept>()

    private suspend fun <T : Any> keep(key: String, maxAgeMs: Long, load: suspend () -> T): T {
        val k = kept[key]
        @Suppress("UNCHECKED_CAST")
        if (k != null && LiveTvClock.nowMs() - k.at < maxAgeMs) return k.value as T
        return load().also { kept[key] = Kept(LiveTvClock.nowMs(), it) }
    }

    // ------------------------------------------------------------------ days asked for

    /** "20261026" (a day) or "202610" (a month) -> the first and last day, "yyyyMMdd". */
    private fun span(from: String, to: String): Pair<String, String> =
        (if (from.length == 6) from + "01" else from) to (if (to.length == 6) to + "31" else to)

    private fun dayKey(ms: Long): String = SportsDates.dayKey(ms)

    private fun isoMs(s: String?): Long? = SportsDates.parseIso(s)

    private fun isoOut(ms: Long): String = SportsDates.isoOut(ms)

    private fun inSpan(ms: Long?, span: Pair<String, String>): Boolean {
        val d = dayKey(ms ?: return false)
        return d >= span.first && d <= span.second
    }

    private fun eventsJson(list: List<JSONObject>): String = JSONObject().put("events", JSONArray(list)).toString()

    /** The scoreboard for days [from]..[to] ("yyyyMMdd" or "yyyyMM"), in ESPN's shape. */
    suspend fun scoreboard(path: String, from: String, to: String, get: suspend (String) -> String): String {
        val span = span(from, to)
        return when {
            path == "cricket/panel" -> eventsJson(cricketPanel(span, get))
            path.startsWith("cricket/") -> eventsJson(cricketSeason(path.removePrefix("cricket/"), span, get).filter { inSpan(isoMs(it.optString("date")), span) })
            path.startsWith("hockeytech/") -> eventsJson(hockeyTech(path.removePrefix("hockeytech/"), span, get))
            else -> eventsJson(milb(path.removePrefix("milb/"), span, get))
        }
    }

    /** Today and the days either side (the feed's own pick, for ESPN). */
    suspend fun scoreboardNow(path: String, get: suspend (String) -> String): String {
        val now = LiveTvClock.nowMs()
        return scoreboard(path, dayKey(now - 86_400_000L), dayKey(now + 86_400_000L), get)
    }

    /** A league's teams, in the shape of ESPN's teams list. */
    suspend fun teams(path: String, get: suspend (String) -> String): String {
        val teams: List<JSONObject> = when {
            // International cricket: the countries playing in the last and next fortnight.
            path == "cricket/panel" -> panelDays(-14, 14, get)
                .flatMap { ev -> competitorsOf(ev).mapNotNull { it.optJSONObject("team") } }.distinctBy { it.optString("id") }
            path.startsWith("cricket/") -> {
                val now = LiveTvClock.nowMs()
                val y = dayKey(now).take(4).toInt()
                val evs = cricketSeason(path.removePrefix("cricket/"), "${y - 1}0101" to "${y}1231", get)
                evs.flatMap { ev -> competitorsOf(ev).mapNotNull { it.optJSONObject("team") } }.distinctBy { it.optString("id") }
            }
            path.startsWith("hockeytech/") -> hockeyTechTeams(path.removePrefix("hockeytech/"), get)
            else -> milbTeams(path.removePrefix("milb/"), get)
        }
        val wrapped = JSONArray(teams.map { JSONObject().put("team", it) })
        return JSONObject().put("sports", JSONArray().put(JSONObject().put("leagues", JSONArray().put(JSONObject().put("teams", wrapped))))).toString()
    }

    /** A team's games from a few weeks back to a couple of months ahead. */
    suspend fun teamSchedule(path: String, teamId: String, get: suspend (String) -> String): String {
        val now = LiveTvClock.nowMs()
        val span = dayKey(now - 21 * 86_400_000L) to dayKey(now + 75 * 86_400_000L)
        val all: List<JSONObject> = when {
            path == "cricket/panel" -> panelDays(-3, 21, get)
            path.startsWith("cricket/") -> cricketSeason(path.removePrefix("cricket/"), span, get).filter { inSpan(isoMs(it.optString("date")), span) }
            path.startsWith("hockeytech/") -> hockeyTech(path.removePrefix("hockeytech/"), span, get)
            else -> milb(path.removePrefix("milb/"), span, get, teamId)
        }
        return eventsJson(all.filter { ev -> competitorsOf(ev).any { it.optJSONObject("team")?.optString("id") == teamId } })
    }

    private fun competitorsOf(ev: JSONObject): List<JSONObject> {
        val a = ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    // ------------------------------------------------------------------ cricket (ESPN)

    /** Cricket's short team names are codes ("KKR", "MR"): the cards use the full name instead. */
    private fun withFullNames(ev: JSONObject): JSONObject {
        for (c in competitorsOf(ev)) {
            val t = c.optJSONObject("team") ?: continue
            val full = t.optString("displayName").ifBlank { t.optString("name") }
            val short = t.optString("shortDisplayName")
            if (full.isNotBlank() && (short.isBlank() || short.length <= 4 || short == t.optString("abbreviation"))) t.put("shortDisplayName", full)
        }
        return ev
    }

    /** A franchise league's seasons that touch [span] (ESPN labels them by the year they start or end). */
    private suspend fun cricketSeason(id: String, span: Pair<String, String>, get: suspend (String) -> String): List<JSONObject> {
        val default = runCatching { keep("cricket:$id", 30 * 60_000L) { JSONObject(get("${ESPN}cricket/$id/scoreboard")) } }.getOrNull()
        val years = sortedSetOf(span.first.take(4).toInt(), span.second.take(4).toInt())
        default?.optJSONArray("leagues")?.optJSONObject(0)?.let { l ->
            l.optJSONObject("season")?.optInt("year", 0)?.takeIf { it > 2000 }?.let { years += it }
            l.optJSONArray("links")?.let { links ->
                for (i in 0 until links.length()) {
                    Regex("""/season/(\d{4})(?:/|$)""").find(links.optJSONObject(i)?.optString("href").orEmpty())?.let { years += it.groupValues[1].toInt() }
                }
            }
        }
        val out = LinkedHashMap<String, JSONObject>()
        fun add(root: JSONObject?) {
            val evs = root?.optJSONArray("events") ?: return
            for (i in 0 until evs.length()) {
                val e = evs.optJSONObject(i) ?: continue
                val eid = e.optString("id")
                if (eid.isNotBlank()) out[eid] = withFullNames(e)
            }
        }
        add(default)
        for (y in years) {
            add(runCatching { keep("cricket:$id:$y", 4 * 60_000L) { JSONObject(get("${ESPN}cricket/$id/scoreboard?season=$y")) } }.getOrNull())
        }
        return out.values.toList()
    }

    /** International matches from [fromDay] to [toDay] days from today (each day one request, kept a few minutes). */
    private suspend fun panelDays(fromDay: Int, toDay: Int, get: suspend (String) -> String): List<JSONObject> {
        val now = LiveTvClock.nowMs()
        val out = LinkedHashMap<String, JSONObject>()
        for (d in fromDay..toDay) {
            val key = dayKey(now + d * 86_400_000L)
            cricketPanel(key to key, get).forEach { out[it.optString("id")] = it }
        }
        return out.values.toList()
    }

    /** International matches on the days of [span] (a week at most: the panel is one day a request). */
    private suspend fun cricketPanel(span: Pair<String, String>, get: suspend (String) -> String): List<JSONObject> {
        val first = SportsDates.dayKeyStart(span.first) ?: return emptyList()
        val last = SportsDates.dayKeyStart(span.second) ?: return emptyList()
        if (last - first > 7 * 86_400_000L) return emptyList()
        val out = LinkedHashMap<String, JSONObject>()
        var t = first + 12 * 3_600_000L
        while (t <= last + 12 * 3_600_000L) {
            val day = dayKey(t)
            t += 86_400_000L
            val root = runCatching { keep("panel:$day", 3 * 60_000L) { JSONObject(get(CRICKET_PANEL + day)) } }.getOrNull() ?: continue
            val blocks = root.optJSONArray("scores") ?: continue
            for (b in 0 until blocks.length()) {
                val evs = blocks.optJSONObject(b)?.optJSONArray("events") ?: continue
                for (i in 0 until evs.length()) {
                    val e = evs.optJSONObject(i) ?: continue
                    val card = e.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("class")?.optString("generalClassCard")?.trim()
                    if (card !in CRICKET_FORMATS) continue
                    val eid = e.optString("id")
                    if (eid.isNotBlank()) out[eid] = withFullNames(e)
                }
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ HockeyTech

    private suspend fun ht(code: String, view: String, extra: String, get: suspend (String) -> String): JSONObject? {
        val key = HOCKEYTECH_KEYS[code] ?: return null
        return JSONObject(get("$HOCKEYTECH&view=$view&client_code=$code&key=$key$extra")).optJSONObject("SiteKit")
    }

    private fun JSONObject.any(vararg names: String): String? {
        for (n in names) optString(n, "").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        return null
    }

    /**
     * Each team's logo as the league's own score bar gives it ("ahl:327" -> its address). The
     * usual address (logos/<team>.png) is missing for a few teams (the Springfield Thunderbirds),
     * so the score bar's, which always works, is used for every team it has listed.
     */
    private val htLogos = SyncMap<String, String>()

    /** The score bar: the games of the last and next week, live scores for today's (kept a minute). */
    private suspend fun htBar(code: String, get: suspend (String) -> String): JSONArray {
        val bar = runCatching {
            keep("htbar:$code", 60_000L) { ht(code, "scorebar", "&numberofdaysback=7&numberofdaysahead=7", get)?.optJSONArray("Scorebar") ?: JSONArray() }
        }.getOrNull() ?: JSONArray()
        for (i in 0 until bar.length()) {
            val g = bar.optJSONObject(i) ?: continue
            for (side in listOf("Home", "Visitor")) {
                val tid = g.any("${side}ID") ?: continue
                val logo = g.any("${side}Logo")?.takeIf { it.startsWith("http") } ?: continue
                htLogos["$code:$tid"] = logo.replace("http://", "https://")
            }
        }
        return bar
    }

    private fun htLogo(code: String, tid: String) = htLogos["$code:$tid"] ?: "https://assets.leaguestat.com/$code/logos/$tid.png"

    private suspend fun hockeyTech(code: String, span: Pair<String, String>, get: suspend (String) -> String): List<JSONObject> {
        // The score bar first (it says where each team's logo is), then the season (kept half an
        // hour), with the score bar's games on top for their live scores.
        val bar = htBar(code, get)
        val season = runCatching { keep("ht:$code", 30 * 60_000L) { ht(code, "schedule", "", get)?.optJSONArray("Schedule") ?: JSONArray() } }.getOrNull() ?: JSONArray()
        val games = LinkedHashMap<String, JSONObject>()
        for (i in 0 until season.length()) {
            val g = season.optJSONObject(i) ?: continue
            val ev = htEvent(code, g) ?: continue
            if (inSpan(isoMs(ev.optString("date")), span)) games[ev.optString("id")] = ev
        }
        for (i in 0 until bar.length()) {
            val ev = htEvent(code, bar.optJSONObject(i) ?: continue) ?: continue
            if (inSpan(isoMs(ev.optString("date")), span)) games[ev.optString("id")] = ev
        }
        return games.values.toList()
    }

    /** One HockeyTech game (the season list and the score bar name things differently). */
    private fun htEvent(code: String, g: JSONObject): JSONObject? {
        val id = g.any("game_id", "ID", "id") ?: return null
        val start = isoMs(g.any("GameDateISO8601")) ?: return null
        val raw = g.any("game_status", "GameStatusStringLong", "GameStatusString").orEmpty()
        val low = raw.lowercase()
        val statusCode = g.any("GameStatus", "status")
        val state = when {
            low.startsWith("final") || statusCode == "4" -> "post"
            low == "ppd" || low.contains("postpon") || low.contains("cancel") -> "pre"
            Regex("""\b(1st|2nd|3rd|ot|so|intermission)\b|\d+:\d{2}\s*(1st|2nd|3rd|ot)""").containsMatchIn(low) && !Regex("""\d{1,2}:\d{2}\s*(am|pm)""").containsMatchIn(low) -> "in"
            statusCode == "2" || statusCode == "3" -> "in"
            else -> "pre"
        }
        val off = low == "ppd" || low.contains("postpon") || low.contains("cancel")
        fun team(side: String, alt: String): JSONObject? {
            val tid = g.any("${side}_team", "${alt}ID") ?: return null
            val city = g.any("${side}_team_city", "${alt}City").orEmpty()
            val nick = g.any("${side}_team_nickname", "${alt}Nickname").orEmpty()
            val name = listOf(city, nick).filter { it.isNotBlank() }.joinToString(" ").ifBlank { g.any("${side}_team_name", "${alt}LongName") ?: return null }
            return JSONObject().put("id", tid).put("displayName", name).put("shortDisplayName", nick.ifBlank { name })
                .put("abbreviation", g.any("${side}_team_code", "${alt}Code") ?: name.take(3).uppercase())
                .put("location", city.ifBlank { null })
                .put("logo", htLogo(code, tid)).put("color", colourOf(name))
        }
        val home = team("home", "Home") ?: return null
        val away = team("visiting", "Visitor") ?: return null
        val hs = g.any("home_goal_count", "HomeGoals")
        val vs = g.any("visiting_goal_count", "VisitorGoals")
        val done = state == "post"
        val hN = hs?.toIntOrNull()
        val vN = vs?.toIntOrNull()
        val type = JSONObject().put("state", state)
            .put("name", if (off) "STATUS_POSTPONED" else "")
            .put("description", if (off) "Postponed" else raw)
            .put("shortDetail", if (state == "pre") "" else raw.ifBlank { if (done) "Final" else "Live" })
        val status = JSONObject().put("type", type)
        fun comp(t: JSONObject, home: Boolean, score: String?, win: Boolean) = JSONObject().put("homeAway", if (home) "home" else "away")
            .put("team", t).put("score", score ?: "").put("winner", win)
        val competition = JSONObject().put("status", status)
            .put("competitors", JSONArray().put(comp(home, true, hs, done && hN != null && vN != null && hN > vN)).put(comp(away, false, vs, done && hN != null && vN != null && vN > hN)))
        g.any("venue_name", "venue_location")?.let { competition.put("venue", JSONObject().put("fullName", it)) }
        return JSONObject().put("id", "$code-$id").put("date", isoOut(start)).put("status", status)
            .put("name", "${away.optString("displayName")} at ${home.optString("displayName")}")
            .put("competitions", JSONArray().put(competition))
    }

    private suspend fun hockeyTechTeams(code: String, get: suspend (String) -> String): List<JSONObject> {
        htBar(code, get)
        val list = runCatching { keep("htteams:$code", 12 * 3_600_000L) { ht(code, "teamsbyseason", "", get)?.optJSONArray("Teamsbyseason") ?: JSONArray() } }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val tid = t.any("id", "team_id") ?: return@mapNotNull null
            val city = t.any("city").orEmpty()
            val nick = t.any("nickname").orEmpty()
            val name = listOf(city, nick).filter { it.isNotBlank() }.joinToString(" ").ifBlank { t.any("name") ?: return@mapNotNull null }
            JSONObject().put("id", tid).put("displayName", name).put("shortDisplayName", nick.ifBlank { name })
                .put("abbreviation", t.any("code") ?: name.take(3).uppercase()).put("location", city.ifBlank { null })
                .put("logo", htLogo(code, tid)).put("color", colourOf(name))
        }
    }

    // ------------------------------------------------------------------ Minor League Baseball (MLB Stats API)

    private suspend fun milb(sportId: String, span: Pair<String, String>, get: suspend (String) -> String, teamId: String? = null): List<JSONObject> {
        fun d(k: String) = "${k.take(4)}-${k.substring(4, 6)}-${k.substring(6, 8)}"
        val today = dayKey(LiveTvClock.nowMs())
        val live = today >= span.first && today <= span.second
        val url = "${MLB}schedule?sportId=$sportId&startDate=${d(span.first)}&endDate=${d(span.second)}&hydrate=team,venue,linescore,broadcasts(all)" +
            (teamId?.let { "&teamId=$it" } ?: "")
        val root = runCatching { keep("milb:$url", if (live) 60_000L else 30 * 60_000L) { JSONObject(get(url)) } }.getOrNull() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        val dates = root.optJSONArray("dates") ?: return out
        for (i in 0 until dates.length()) {
            val games = dates.optJSONObject(i)?.optJSONArray("games") ?: continue
            for (j in 0 until games.length()) milbEvent(games.optJSONObject(j) ?: continue)?.let { out += it }
        }
        return out
    }

    private fun milbTeamJson(t: JSONObject?): JSONObject? {
        t ?: return null
        val id = t.optInt("id", 0).takeIf { it > 0 }?.toString() ?: return null
        val name = t.optString("name").ifBlank { return null }
        return JSONObject().put("id", id).put("displayName", name)
            .put("shortDisplayName", t.optString("teamName").ifBlank { name })
            .put("abbreviation", t.optString("abbreviation").ifBlank { name.take(3).uppercase() })
            .put("location", t.optString("locationName").ifBlank { null })
            .put("logo", "https://www.mlbstatic.com/team-logos/$id.svg").put("color", colourOf(name))
    }

    private fun milbEvent(g: JSONObject): JSONObject? {
        val id = g.optLong("gamePk", 0L).takeIf { it > 0 } ?: return null
        val start = isoMs(g.optString("gameDate")) ?: return null
        val st = g.optJSONObject("status")
        val abstract = st?.optString("abstractGameState").orEmpty()
        val detailed = st?.optString("detailedState").orEmpty()
        val off = Regex("Postponed|Cancelled|Canceled|Suspended", RegexOption.IGNORE_CASE).containsMatchIn(detailed)
        val state = when {
            off -> "pre"
            abstract == "Final" -> "post"
            abstract == "Live" -> "in"
            else -> "pre"
        }
        val line = g.optJSONObject("linescore")
        val inning = line?.let { l -> listOf(l.optString("inningState").ifBlank { l.optString("inningHalf") }, l.optString("currentInningOrdinal")).filter { it.isNotBlank() }.joinToString(" ") }.orEmpty()
        val type = JSONObject().put("state", state)
            .put("name", if (off) "STATUS_POSTPONED" else "")
            .put("description", if (off) detailed else "")
            .put("shortDetail", when (state) { "in" -> inning.ifBlank { "Live" }; "post" -> "Final"; else -> "" })
        val status = JSONObject().put("type", type)
        val teams = g.optJSONObject("teams") ?: return null
        // Runs inning by inning, for the line score.
        val innings = line?.optJSONArray("innings")
        fun comp(side: String): JSONObject? {
            val s = teams.optJSONObject(side) ?: return null
            val t = milbTeamJson(s.optJSONObject("team")) ?: return null
            val score = if (s.has("score")) s.optInt("score").toString() else ""
            val c = JSONObject().put("homeAway", side).put("team", t).put("score", score).put("winner", s.optBoolean("isWinner", false))
            if (innings != null && innings.length() > 0 && state != "pre") {
                c.put("linescores", JSONArray((0 until innings.length()).map { i ->
                    val r = innings.optJSONObject(i)?.optJSONObject(side)?.opt("runs")
                    JSONObject().put("displayValue", r?.toString() ?: "-")
                }))
            }
            return c
        }
        val home = comp("home") ?: return null
        val away = comp("away") ?: return null
        val competition = JSONObject().put("status", status).put("competitors", JSONArray().put(home).put(away))
        // TV (and streaming) it's on.
        val tv = g.objs("broadcasts").filter { it.optString("type").equals("TV", true) || it.optString("type").isBlank() }
            .map { it.optString("name") }.filter { it.isNotBlank() }.distinct()
        if (tv.isNotEmpty()) competition.put("broadcasts", JSONArray().put(JSONObject().put("names", JSONArray(tv))))
        g.optJSONObject("venue")?.optString("name")?.takeIf { it.isNotBlank() }?.let { competition.put("venue", JSONObject().put("fullName", it)) }
        return JSONObject().put("id", "milb-$id").put("date", isoOut(start)).put("status", status)
            .put("name", "${away.optJSONObject("team")?.optString("displayName")} at ${home.optJSONObject("team")?.optString("displayName")}")
            .put("competitions", JSONArray().put(competition))
    }

    private suspend fun milbTeams(sportId: String, get: suspend (String) -> String): List<JSONObject> {
        val root = runCatching { keep("milbteams:$sportId", 12 * 3_600_000L) { JSONObject(get("${MLB}teams?sportId=$sportId")) } }.getOrNull() ?: return emptyList()
        val a = root.optJSONArray("teams") ?: return emptyList()
        return (0 until a.length()).mapNotNull { milbTeamJson(a.optJSONObject(it)) }
    }

    // ------------------------------------------------------------------ game pages and tables

    /** A game's details in the shape of ESPN's game summary (scoring, team stats, stars, officials). */
    suspend fun summary(path: String, eventId: String, get: suspend (String) -> String): JSONObject = when {
        path.startsWith("hockeytech/") -> htSummary(path.removePrefix("hockeytech/"), eventId, get)
        path.startsWith("milb/") -> milbSummary(eventId, get)
        else -> JSONObject(get("${ESPN}$path/summary?event=$eventId"))
    }

    /** A league's table in the shape of ESPN's standings, or null when the league has none. */
    suspend fun standings(path: String, get: suspend (String) -> String): String? = when {
        path.startsWith("hockeytech/") -> htStandings(path.removePrefix("hockeytech/"), get)
        path.startsWith("milb/") -> milbStandings(path.removePrefix("milb/"), get)
        else -> null
    }

    /** "(...)" or "callback(...)" around JSON (some HockeyTech views): just the JSON. */
    private fun unwrap(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{") || t.startsWith("[")) return t
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        return if (open >= 0 && close > open) t.substring(open + 1, close).trim() else t
    }

    private fun stat(name: String, label: String, value: Any?): JSONObject? {
        val v = value?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return JSONObject().put("name", name).put("label", label).put("displayName", label).put("displayValue", v)
    }

    private fun boxTeam(homeAway: String, teamId: String, stats: List<JSONObject?>) = JSONObject()
        .put("homeAway", homeAway).put("team", JSONObject().put("id", teamId))
        .put("statistics", JSONArray(stats.filterNotNull()))

    private fun leaderGroup(teamId: String, cats: List<Triple<String, String, String>>) = JSONObject()
        .put("team", JSONObject().put("id", teamId))
        .put("leaders", JSONArray(cats.map { (cat, name, line) ->
            JSONObject().put("displayName", cat).put("leaders", JSONArray().put(
                JSONObject().put("displayValue", line).put("athlete", JSONObject().put("displayName", name))
            ))
        }))

    private fun JSONObject.objs(name: String): List<JSONObject> {
        val a = optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    // ---- HockeyTech

    private const val HT_BASE = "https://lscluster.hockeytech.com/feed/index.php?"
    /** HockeyTech's league numbers (its standings want one). */
    private val HT_LEAGUE_IDS = mapOf("ahl" to "4", "pwhl" to "1", "ohl" to "1", "whl" to "7", "lhjmq" to "6")

    /** The current regular season's number (kept half a day). */
    private suspend fun htSeason(code: String, get: suspend (String) -> String): String? = runCatching {
        keep("htseason:$code", 12 * 3_600_000L) {
            val list = ht(code, "seasons", "", get)?.optJSONArray("Seasons") ?: JSONArray()
            val today = SportsDates.localIsoDate()
            val rows = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            val regular = rows.filter { it.optString("playoff") != "1" && it.optString("career") != "0" }
            (regular.firstOrNull { it.optString("start_date") <= today } ?: regular.firstOrNull() ?: rows.firstOrNull())
                ?.optString("season_id").orEmpty()
        }.ifBlank { null }
    }.getOrNull()

    private fun htName(o: JSONObject?): String? {
        o ?: return null
        val n = listOf(o.optString("first_name"), o.optString("last_name")).filter { it.isNotBlank() && it != "null" }.joinToString(" ")
        return n.ifBlank { null }
    }

    private suspend fun htSummary(code: String, eventId: String, get: suspend (String) -> String): JSONObject {
        val key = HOCKEYTECH_KEYS[code] ?: throw FeedError("no key")
        val gameId = eventId.removePrefix("$code-")
        val raw = get("${HT_BASE}feed=gc&tab=gamesummary&game_id=$gameId&key=$key&client_code=$code&lang_code=en&fmt=json")
        val root = JSONObject(unwrap(raw))
        val gs = root.optJSONObject("GC")?.optJSONObject("Gamesummary") ?: root.optJSONObject("GC") ?: root
        val home = gs.optJSONObject("home") ?: JSONObject()
        val vis = gs.optJSONObject("visitor") ?: JSONObject()
        val homeId = home.optString("id").ifBlank { home.optString("team_id") }
        val visId = vis.optString("id").ifBlank { vis.optString("team_id") }
        val homeCode = home.optString("code").ifBlank { home.optString("team_code") }
        val visCode = vis.optString("code").ifBlank { vis.optString("team_code") }
        val out = JSONObject()

        // Goals, in order, with the score after each.
        var hs = 0
        var vs = 0
        val goals = gs.objs("goals").sortedWith(compareBy({ it.optString("period_id").toIntOrNull() ?: 0 }, { g ->
            g.optString("time").split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        }))
        val plays = JSONArray()
        for (g in goals) {
            val isHome = g.optString("home") == "1" || g.optString("team_id") == homeId
            if (isHome) hs++ else vs++
            val scorer = htName(g.optJSONObject("goal_scorer")) ?: "Goal"
            val assists = listOfNotNull(htName(g.optJSONObject("assist1_player")), htName(g.optJSONObject("assist2_player")))
            val tags = listOfNotNull(
                "PP".takeIf { g.optString("power_play") == "1" }, "SH".takeIf { g.optString("short_handed") == "1" },
                "EN".takeIf { g.optString("empty_net") == "1" }, "PS".takeIf { g.optString("penalty_shot") == "1" }
            )
            val text = scorer + (if (assists.isNotEmpty()) " (" + assists.joinToString(", ") + ")" else " (unassisted)") +
                (if (tags.isNotEmpty()) " · " + tags.joinToString(" ") else "")
            plays.put(JSONObject()
                .put("period", JSONObject().put("number", g.optString("period_id").toIntOrNull() ?: 0))
                .put("clock", JSONObject().put("displayValue", g.optString("time")))
                .put("team", JSONObject().put("abbreviation", g.optJSONObject("goal_scorer")?.optString("team_code")?.takeIf { it.isNotBlank() && it != "null" } ?: if (isHome) homeCode else visCode))
                .put("text", text).put("awayScore", vs).put("homeScore", hs))
        }
        out.put("scoringPlays", plays)

        // Team stats.
        fun side(o: String, h: Boolean): Any? = gs.optJSONObject(o)?.opt(if (h) "home" else "visitor")
        fun teamStats(h: Boolean): List<JSONObject?> {
            val fo = gs.optJSONObject("totalFaceoffs")?.optJSONObject(if (h) "home" else "visitor")
            val foPct = fo?.let { f -> val att = f.optInt("att"); if (att > 0) "${(f.optInt("won") * 100.0 / att).roundToLong()}%" else null }
            val ppg = side("powerPlayGoals", h)
            val ppc = side("powerPlayCount", h)
            return listOf(
                stat("shotsTotal", "Shots", side("totalShots", h)),
                stat("powerPlayGoals", "Power Play", if (ppg != null && ppc != null) "$ppg/$ppc" else ppg),
                stat("hits", "Hits", side("totalHits", h)),
                stat("faceoffPercent", "Faceoffs Won", foPct),
                stat("penaltyMinutes", "Penalty Minutes", side("pimTotal", h))
            )
        }
        if (homeId.isNotBlank() && visId.isNotBlank()) {
            out.put("boxscore", JSONObject().put("teams", JSONArray().put(boxTeam("away", visId, teamStats(false))).put(boxTeam("home", homeId, teamStats(true)))))
        }

        // The three stars, with their game.
        val lineup = HashMap<String, JSONObject>()
        for (side in listOf("home_team_lineup", "visitor_team_lineup")) {
            gs.optJSONObject(side)?.let { l -> (l.objs("players") + l.objs("goalies")).forEach { p -> lineup[p.optString("player_id")] = p } }
        }
        val stars = gs.objs("threeStars").ifEmpty { gs.objs("mvps") }
        if (stars.isNotEmpty()) {
            val labels = listOf("1ST STAR", "2ND STAR", "3RD STAR")
            val byTeam = LinkedHashMap<String, MutableList<Triple<String, String, String>>>()
            stars.take(3).forEachIndexed { i, st ->
                val name = htName(st) ?: return@forEachIndexed
                val p = lineup[st.optString("player_id")]
                val line = when {
                    p == null -> ""
                    p.has("saves") -> "${p.optInt("saves")} saves"
                    else -> listOfNotNull(p.optInt("goals").takeIf { it > 0 }?.let { "$it G" }, p.optInt("assists").takeIf { it > 0 }?.let { "$it A" }).joinToString(", ")
                }
                val team = if (st.optString("home") == "1") homeId else visId
                byTeam.getOrPut(team) { ArrayList() } += Triple(labels[i], name, line)
            }
            out.put("leaders", JSONArray(byTeam.map { (t, cats) -> leaderGroup(t, cats) }))
        }

        // Line-ups (the starters).
        val rosters = JSONArray()
        for ((side, teamId) in listOf("visitor_team_lineup" to visId, "home_team_lineup" to homeId)) {
            val l = gs.optJSONObject(side) ?: continue
            val players = (l.objs("goalies") + l.objs("players")).map { p ->
                JSONObject().put("starter", p.optString("start") == "1").put("jersey", p.optString("jersey_number"))
                    .put("athlete", JSONObject().put("displayName", htName(p) ?: ""))
            }
            rosters.put(JSONObject().put("team", JSONObject().put("id", teamId)).put("roster", JSONArray(players)))
        }
        if (rosters.length() > 0) out.put("rosters", rosters)

        // Where, how many, who refereed.
        val venueParts = gs.optString("venue").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val gi = JSONObject()
        venueParts.firstOrNull()?.let { v ->
            gi.put("venue", JSONObject().put("fullName", v).put("address", JSONObject().put("city", venueParts.getOrNull(1) ?: "")))
        }
        gs.optJSONObject("meta")?.optString("attendance")?.toIntOrNull()?.takeIf { it > 0 }?.let { gi.put("attendance", it) }
        gs.optString("referee1").takeIf { it.isNotBlank() && it != "null" }?.let { r ->
            gi.put("officials", JSONArray().put(JSONObject().put("displayName", r.replace(Regex("""\s*\(\d+\)$"""), ""))
                .put("position", JSONObject().put("name", "Referee"))))
        }
        if (gi.length() > 0) out.put("gameInfo", gi)
        return out
    }

    private suspend fun htStandings(code: String, get: suspend (String) -> String): String? {
        val key = HOCKEYTECH_KEYS[code] ?: return null
        val season = htSeason(code, get) ?: return null
        val raw = runCatching {
            keep("htstand:$code", 30 * 60_000L) {
                get("${HT_BASE}feed=statviewfeed&view=teams&groupTeamsBy=division&context=overall&site_id=0&season=$season&special=false" +
                    "&key=$key&client_code=$code&league_id=${HT_LEAGUE_IDS[code].orEmpty()}&division=-1&sort=points&lang=en")
            }
        }.getOrNull() ?: return null
        val text = unwrap(raw)
        val top: JSONArray = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("sections") ?: return null
        // Sections, sometimes inside an outer one.
        val blocks = ArrayList<JSONObject>()
        for (i in 0 until top.length()) {
            val sec = top.optJSONObject(i) ?: continue
            val inner = sec.optJSONArray("sections")
            if (inner != null && inner.length() > 0) for (j in 0 until inner.length()) inner.optJSONObject(j)?.let { blocks += it } else blocks += sec
        }
        val children = JSONArray()
        for (b in blocks) {
            val entries = JSONArray()
            for (d in b.objs("data")) {
                val row = d.optJSONObject("row") ?: continue
                val prop = d.optJSONObject("prop")
                val tid = prop?.optJSONObject("name")?.optString("teamLink")?.takeIf { it.isNotBlank() }
                    ?: prop?.optJSONObject("team_code")?.optString("teamLink")?.takeIf { it.isNotBlank() } ?: continue
                var name = row.optString("name").ifBlank { row.optString("team_name") }
                // "x - Montréal Victoire": the letter says they've clinched.
                val clinch = Regex("""^([a-z*]{1,2})\s*-\s+""").find(name)?.groupValues?.get(1)
                if (clinch != null) name = name.substringAfter("- ").trim()
                val abbr = row.optString("team_code").substringAfter("- ").trim().ifBlank { name.take(3).uppercase() }
                fun n(k: String) = row.optString(k).toIntOrNull()
                val wins = n("wins") ?: listOfNotNull(n("regulation_wins"), n("non_reg_wins")).takeIf { it.isNotEmpty() }?.sum()
                val otl = listOfNotNull(n("ot_losses"), n("shootout_losses")).takeIf { it.isNotEmpty() }?.sum() ?: n("non_reg_losses")
                val stats = listOfNotNull(
                    n("games_played")?.let { JSONObject().put("name", "gamesPlayed").put("displayValue", "$it").put("value", it) },
                    wins?.let { JSONObject().put("name", "wins").put("displayValue", "$it").put("value", it) },
                    n("losses")?.let { JSONObject().put("name", "losses").put("displayValue", "$it").put("value", it) },
                    otl?.let { JSONObject().put("name", "otLosses").put("displayValue", "$it").put("value", it) },
                    n("points")?.let { JSONObject().put("name", "points").put("displayValue", "$it").put("value", it) },
                    row.optString("streak").takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject().put("name", "streak").put("displayValue", it) },
                    (row.optString("rank").toIntOrNull() ?: row.optString("overall_rank").toIntOrNull())?.let { JSONObject().put("name", "rank").put("value", it) },
                    clinch?.let { JSONObject().put("name", "clincher").put("displayValue", it) }
                )
                entries.put(JSONObject()
                    .put("team", JSONObject().put("id", tid).put("displayName", name).put("shortDisplayName", name).put("abbreviation", abbr)
                        .put("logos", JSONArray().put(JSONObject().put("href", htLogo(code, tid)))))
                    .put("stats", JSONArray(stats)))
            }
            if (entries.length() == 0) continue
            children.put(JSONObject().put("name", b.optString("title").ifBlank { code.uppercase() })
                .put("standings", JSONObject().put("entries", entries)))
        }
        if (children.length() == 0) return null
        return JSONObject().put("children", children).toString()
    }

    // ---- Minor League Baseball

    private suspend fun milbSummary(eventId: String, get: suspend (String) -> String): JSONObject {
        val pk = eventId.removePrefix("milb-")
        val box = JSONObject(get("${MLB}game/$pk/boxscore"))
        val pbp = runCatching { JSONObject(get("${MLB}game/$pk/playByPlay")) }.getOrNull()
        val out = JSONObject()
        val teams = box.optJSONObject("teams") ?: JSONObject()
        val home = teams.optJSONObject("home") ?: JSONObject()
        val away = teams.optJSONObject("away") ?: JSONObject()
        val homeId = home.optJSONObject("team")?.optInt("id")?.toString().orEmpty()
        val awayId = away.optJSONObject("team")?.optInt("id")?.toString().orEmpty()
        fun abbr(t: JSONObject) = t.optJSONObject("team")?.let { it.optString("abbreviation").ifBlank { it.optString("teamName") } }.orEmpty()

        // Scoring plays: "Top 5th · Smith doubles, Jones scores."
        val plays = JSONArray()
        if (pbp != null) {
            val all = pbp.optJSONArray("allPlays") ?: JSONArray()
            val idx = pbp.optJSONArray("scoringPlays") ?: JSONArray()
            for (i in 0 until idx.length()) {
                val p = all.optJSONObject(idx.optInt(i)) ?: continue
                val about = p.optJSONObject("about") ?: continue
                val result = p.optJSONObject("result") ?: continue
                val top = about.optString("halfInning") == "top"
                val inning = about.optInt("inning")
                val ord = when { inning % 100 in 11..13 -> "${inning}th"; inning % 10 == 1 -> "${inning}st"; inning % 10 == 2 -> "${inning}nd"; inning % 10 == 3 -> "${inning}rd"; else -> "${inning}th" }
                plays.put(JSONObject()
                    .put("clock", JSONObject().put("displayValue", (if (top) "Top " else "Bot ") + ord))
                    .put("team", JSONObject().put("abbreviation", if (top) abbr(away) else abbr(home)))
                    .put("text", result.optString("description"))
                    .put("awayScore", result.opt("awayScore")).put("homeScore", result.opt("homeScore")))
            }
        }
        out.put("scoringPlays", plays)

        // Team stats.
        fun teamStats(t: JSONObject): List<JSONObject?> {
            val ts = t.optJSONObject("teamStats") ?: return emptyList()
            val bat = ts.optJSONObject("batting") ?: JSONObject()
            val fld = ts.optJSONObject("fielding") ?: JSONObject()
            return listOf(
                stat("hits", "Hits", bat.opt("hits")), stat("runs", "Runs", bat.opt("runs")),
                stat("errors", "Errors", fld.opt("errors")), stat("homeRuns", "Home Runs", bat.opt("homeRuns")),
                stat("strikeouts", "Strikeouts", bat.opt("strikeOuts")), stat("walks", "Walks", bat.opt("baseOnBalls")),
                stat("leftOnBase", "Left on Base", bat.opt("leftOnBase"))
            )
        }
        out.put("boxscore", JSONObject().put("teams", JSONArray().put(boxTeam("away", awayId, teamStats(away))).put(boxTeam("home", homeId, teamStats(home)))))

        // Each team's best hitter and its pitcher who went longest.
        fun leaders(t: JSONObject, teamId: String): JSONObject? {
            val players = t.optJSONObject("players") ?: return null
            var bestBat: Triple<String, String, Int>? = null
            var bestPitch: Triple<String, String, Double>? = null
            for (k in players.keys()) {
                val pl = players.optJSONObject(k) ?: continue
                val name = pl.optJSONObject("person")?.optString("fullName").orEmpty()
                if (name.isBlank()) continue
                val st = pl.optJSONObject("stats") ?: continue
                st.optJSONObject("batting")?.let { b ->
                    val ab = b.optInt("atBats"); val h = b.optInt("hits"); val hr = b.optInt("homeRuns"); val rbi = b.optInt("rbi")
                    if (ab > 0 || h > 0) {
                        val score = h * 3 + hr * 4 + rbi * 2
                        val line = listOfNotNull("$h-$ab", hr.takeIf { it > 0 }?.let { if (it == 1) "HR" else "$it HR" }, rbi.takeIf { it > 0 }?.let { "$it RBI" }).joinToString(", ")
                        if (bestBat == null || score > bestBat!!.third) bestBat = Triple(name, line, score)
                    }
                }
                st.optJSONObject("pitching")?.let { pi ->
                    val ip = pi.optString("inningsPitched").toDoubleOrNull() ?: return@let
                    if (ip <= 0) return@let
                    val line = "${pi.optString("inningsPitched")} IP, ${pi.optInt("strikeOuts")} K, ${pi.optInt("earnedRuns")} ER"
                    if (bestPitch == null || ip > bestPitch!!.third) bestPitch = Triple(name, line, ip)
                }
            }
            val cats = listOfNotNull(bestBat?.let { Triple("BATTING", it.first, it.second) }, bestPitch?.let { Triple("PITCHING", it.first, it.second) })
            return if (cats.isEmpty()) null else leaderGroup(teamId, cats)
        }
        val lead = JSONArray()
        leaders(away, awayId)?.let { lead.put(it) }
        leaders(home, homeId)?.let { lead.put(it) }
        if (lead.length() > 0) out.put("leaders", lead)

        // Attendance, weather, the plate umpire.
        val gi = JSONObject()
        for (inf in box.objs("info")) {
            val label = inf.optString("label")
            val value = inf.optString("value").trim().trimEnd('.')
            when {
                label.startsWith("Att") -> value.filter { it.isDigit() }.toIntOrNull()?.let { gi.put("attendance", it) }
                label.startsWith("Weather") -> gi.put("weather", JSONObject().put("displayValue", value))
                label.startsWith("Venue") -> gi.put("venue", JSONObject().put("fullName", value))
            }
        }
        box.objs("officials").firstOrNull { it.optString("officialType").contains("Plate", true) }?.let { o ->
            o.optJSONObject("official")?.optString("fullName")?.takeIf { it.isNotBlank() }?.let { name ->
                gi.put("officials", JSONArray().put(JSONObject().put("displayName", name).put("position", JSONObject().put("name", "Referee"))))
            }
        }
        if (gi.length() > 0) out.put("gameInfo", gi)
        return out
    }

    private suspend fun milbStandings(sportId: String, get: suspend (String) -> String): String? {
        val (year, month) = SportsDates.localYearMonth()
        val season = if (month < 3) year - 1 else year
        val leagues = runCatching { keep("milbleagues:$sportId:$season", 12 * 3_600_000L) { JSONObject(get("${MLB}leagues?sportId=$sportId&season=$season")) } }.getOrNull()
            ?.objs("leagues").orEmpty()
        if (leagues.isEmpty()) return null
        val leagueNames = leagues.associate { it.optInt("id").toString() to it.optString("name") }
        val divisions = runCatching { keep("milbdivs:$sportId:$season", 12 * 3_600_000L) { JSONObject(get("${MLB}divisions?sportId=$sportId&season=$season")) } }.getOrNull()
            ?.objs("divisions").orEmpty().associate { it.optInt("id").toString() to it.optString("name") }
        val raw = runCatching {
            keep("milbstand:$sportId:$season", 30 * 60_000L) {
                JSONObject(get("${MLB}standings?leagueId=${leagueNames.keys.joinToString(",")}&season=$season&standingsTypes=regularSeason&hydrate=team"))
            }
        }.getOrNull() ?: return null
        val children = JSONArray()
        for (rec in raw.objs("records")) {
            val divId = rec.optJSONObject("division")?.optInt("id")?.toString()
            val lgId = rec.optJSONObject("league")?.optInt("id")?.toString()
            val name = divId?.let { divisions[it] }?.takeIf { it.isNotBlank() } ?: lgId?.let { leagueNames[it] } ?: "Standings"
            val entries = JSONArray()
            for (tr in rec.objs("teamRecords")) {
                val t = milbTeamJson(tr.optJSONObject("team")) ?: continue
                t.put("logos", JSONArray().put(JSONObject().put("href", t.optString("logo"))))
                fun s(n: String, v: Any?) = v?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject().put("name", n).put("displayValue", it).put("value", it.toDoubleOrNull() ?: 0.0) }
                val stats = listOfNotNull(
                    s("wins", tr.opt("wins")), s("losses", tr.opt("losses")),
                    s("winPercent", tr.optString("winningPercentage")), s("gamesBehind", tr.optString("gamesBack")),
                    s("streak", tr.optJSONObject("streak")?.optString("streakCode")),
                    s("rank", tr.optString("divisionRank").ifBlank { tr.optString("leagueRank") })
                )
                entries.put(JSONObject().put("team", t).put("stats", JSONArray(stats)))
            }
            if (entries.length() > 0) children.put(JSONObject().put("name", name).put("standings", JSONObject().put("entries", entries)))
        }
        if (children.length() == 0) return null
        return JSONObject().put("children", children).toString()
    }
}
