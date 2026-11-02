package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlin.math.floor

/**
 * League tables ("standings"): ESPN's for the leagues it covers (any sport with a table, and
 * the F1 / NASCAR / IndyCar driver standings), TheSportsDB's for the non-league ones (from the
 * nuvio-data files). Fetched when a table is looked at and kept for an hour.
 */

/** A column of the table: [key] picks the value from a row, [label] heads it ("W", "GD", "PTS"). */
data class StandingsColumn(val key: String, val label: String)

/** What a place in the table leads to (Champions League, relegation…), with its colour. */
data class StandingsZone(val label: String, val color: Long)

data class StandingsRow(
    /** The team's (or driver's) id in its feed. */
    val id: String,
    val name: String,
    val shortName: String,
    val abbr: String,
    val logo: String?,
    val pos: Int,
    /** Column key -> what to show. */
    val values: Map<String, String>,
    /** Last results, oldest first ("WDLWW"), when known. */
    val form: String? = null,
    val zone: StandingsZone? = null,
    /** US sports: "x" clinched a playoff place, "y" the division, "z" first in the conference, "e" out. */
    val clincher: String? = null,
    val clincherText: String? = null,
    /** Playoff seed (US sports), when given. */
    val seed: Int? = null,
    /** Places moved since the last round: + up, - down. */
    val change: Int? = null,
    val record: String? = null,
    val home: String? = null,
    val road: String? = null,
    val streak: String? = null,
    /** Win % (US sports) or points (tables), for sorting a merged view. */
    val sortValue: Double = 0.0,
    /** A driver, not a team. */
    val athlete: Boolean = false,
    /** The team's colours (from its league's team list), for its row. */
    val color: Long? = null,
    val altColor: Long? = null
)

/** A division, a conference, a group, or the whole league. [parent]: its conference, if any. */
data class StandingsGroup(val name: String, val parent: String?, val rows: List<StandingsRow>)

enum class StandingsView { DIVISION, CONFERENCE, LEAGUE }

enum class StandingsKind {
    /** Points tables (football, rugby, Aussie rules…): P W D L GD PTS. */
    TABLE,
    /** US sports: W L PCT GB STRK. */
    RECORD,
    /** Hockey: GP W L OTL PTS. */
    HOCKEY,
    /** Drivers' championship. */
    RACING
}

data class Standings(
    val leagueId: String,
    val season: String?,
    val kind: StandingsKind,
    val columns: List<StandingsColumn>,
    /** The smallest groups the feed gives (divisions, or the league's single table). */
    val groups: List<StandingsGroup>,
    val source: String,
    val fetchedAt: Long
) {
    /** The conferences (AFC, NFC…), when the groups have them. */
    val conferences: List<String> get() = groups.mapNotNull { it.parent }.distinct()

    /** True when there are divisions inside conferences (Division / Conference / League make sense). */
    val hasDivisions: Boolean get() = conferences.isNotEmpty()

    /** The groups for a view: divisions of one conference, a whole conference, or the league. */
    fun view(view: StandingsView, conference: String?): List<StandingsGroup> {
        if (!hasDivisions) return groups
        val conf = conference?.takeIf { it in conferences } ?: conferences.first()
        return when (view) {
            StandingsView.DIVISION -> groups.filter { it.parent == conf }
            StandingsView.CONFERENCE -> listOf(merged(conf, groups.filter { it.parent == conf }.flatMap { it.rows }, bySeed = true))
            StandingsView.LEAGUE -> listOf(merged(SportsCatalog.league(leagueId)?.name ?: "League", groups.flatMap { it.rows }, bySeed = false))
        }
    }

    private fun merged(name: String, rows: List<StandingsRow>, bySeed: Boolean): StandingsGroup {
        val sorted = if (bySeed && rows.all { it.seed != null }) rows.sortedBy { it.seed }
        else rows.sortedByDescending { it.sortValue }
        return StandingsGroup(name, null, sorted.mapIndexed { i, r -> r.copy(pos = i + 1) })
    }

    /** Where [teamId] is: its group and row (in the smallest groups). */
    fun find(teamId: String): Pair<StandingsGroup, StandingsRow>? {
        for (g in groups) g.rows.firstOrNull { it.id == teamId }?.let { return g to it }
        return null
    }

    /** "2nd in Premier League", "1st in AFC West", for a team's row header. */
    fun placeText(teamId: String): String? {
        val (g, r) = find(teamId) ?: return null
        val where = when {
            groups.size == 1 -> SportsCatalog.league(leagueId)?.name ?: g.name
            else -> shortGroupName(g.name)
        }
        return ordinal(r.pos) + " in " + where
    }

    /**
     * Playoff lines for a merged conference (or league) view: after these places, a line
     * ("Playoffs", "Play-In"). Only for US leagues with seeds.
     */
    fun playoffLines(view: StandingsView): List<Pair<Int, String>> {
        val conferenceView = (hasDivisions && view == StandingsView.CONFERENCE) || (!hasDivisions && groups.size > 1)
        val leagueView = !hasDivisions && groups.size == 1
        return when (leagueId) {
            "football/nfl" -> if (conferenceView) listOf(7 to "Playoff line") else emptyList()
            "basketball/nba" -> if (conferenceView) listOf(6 to "Playoffs", 10 to "Play-In") else emptyList()
            "hockey/nhl" -> if (conferenceView) listOf(8 to "Playoff line") else emptyList()
            "baseball/mlb" -> if (conferenceView) listOf(6 to "Playoff line") else emptyList()
            "soccer/usa.1" -> if (conferenceView || groups.size > 1) listOf(9 to "Playoff line") else emptyList()
            "basketball/wnba" -> if (leagueView || view == StandingsView.LEAGUE) listOf(8 to "Playoff line") else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        private const val HOUR = 3_600_000L

        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            return "$n$suffix"
        }

        /** "American Football Conference" -> "AFC"; "AFC West" stays. */
        fun shortGroupName(name: String): String = when (name) {
            "American Football Conference" -> "AFC"
            "National Football Conference" -> "NFC"
            "American League" -> "AL"
            "National League" -> "NL"
            else -> name
        }

        // ------------------------------------------------------------------ ESPN

        /** The ESPN address for [league]'s standings (US leagues by division). */
        fun espnUrl(league: League): String? {
            val path = league.espnPath ?: return null
            val divisions = path in setOf("football/nfl", "basketball/nba", "hockey/nhl", "baseball/mlb")
            return "https://site.api.espn.com/apis/v2/sports/$path/standings" + if (divisions) "?level=3" else ""
        }

        fun parseEspn(league: League, raw: String, now: Long = LiveTvClock.nowMs()): Standings? {
            val root = JSONObject(raw)
            val leaves = ArrayList<Triple<String, String?, JSONObject>>()
            var season: String? = null
            fun walk(node: JSONObject, parent: String?) {
                val st = node.optJSONObject("standings")
                val entries = st?.optJSONArray("entries")
                if (st != null && entries != null && entries.length() > 0) {
                    if (season == null) season = st.str("seasonDisplayName") ?: st.opt("season")?.toString()
                    leaves += Triple(node.str("name") ?: node.str("abbreviation") ?: league.name, parent, node)
                }
                val kids = node.optJSONArray("children") ?: return
                // A node with children: its name is the parent of what's inside (a conference).
                val name = node.str("name")
                for (i in 0 until kids.length()) kids.optJSONObject(i)?.let { walk(it, if (node === root) null else name) }
            }
            walk(root, null)
            if (leaves.isEmpty()) return null
            // Racing: the drivers' table only (ESPN puts it first).
            val firstEntry = leaves.first().third.optJSONObject("standings")!!.optJSONArray("entries")!!.optJSONObject(0)
            val athletes = firstEntry?.has("athlete") == true
            val used = if (athletes) leaves.take(1) else leaves
            val statNames = HashSet<String>()
            for ((_, _, node) in used) {
                val e = node.optJSONObject("standings")?.optJSONArray("entries")?.optJSONObject(0) ?: continue
                e.optJSONArray("stats").objs().forEach { s -> s.str("name")?.let { statNames += it } }
            }
            val kind = when {
                athletes -> StandingsKind.RACING
                "otLosses" in statNames -> StandingsKind.HOCKEY
                league.sport == Sport.FOOTBALL || ("points" in statNames && "ties" in statNames && "winPercent" !in statNames) -> StandingsKind.TABLE
                else -> StandingsKind.RECORD
            }
            val groups = used.map { (name, parent, node) ->
                val entries = node.optJSONObject("standings")!!.optJSONArray("entries").objs()
                StandingsGroup(name, parent, parseEntries(entries, kind))
            }
            val anyTies = groups.any { g -> g.rows.any { (it.values["ties"] ?: "0").let { t -> t != "0" && t.isNotBlank() } } }
            val columns = when (kind) {
                StandingsKind.TABLE -> listOf(c("gamesPlayed", "P"), c("wins", "W"), c("ties", "D"), c("losses", "L"), c("pointDifferential", "GD"), c("points", "PTS"))
                StandingsKind.HOCKEY -> listOf(c("gamesPlayed", "GP"), c("wins", "W"), c("losses", "L"), c("otLosses", "OTL"), c("points", "PTS"), c("streak", "STRK"))
                StandingsKind.RACING -> listOf(c("points", "PTS"), c("behind", "BEHIND"))
                StandingsKind.RECORD -> listOfNotNull(c("wins", "W"), c("losses", "L"), if (anyTies) c("ties", "T") else null, c("winPercent", "PCT"), c("gamesBehind", "GB"), c("streak", "STRK"))
            }
            // Only columns the feed actually filled in.
            var filled = columns.filter { col -> col.key == "behind" || groups.any { g -> g.rows.any { !it.values[col.key].isNullOrBlank() } } }
            // A table in the feed's own words (cricket: matches, won, lost, no result, points, net
            // run rate): its columns as it labels them, when the usual ones aren't there.
            if (filled.size < 2 && !athletes) {
                val first = used.first().third.optJSONObject("standings")?.optJSONArray("entries")?.optJSONObject(0)
                val own = first?.optJSONArray("stats").objs().mapNotNull { st ->
                    val name = st.str("name") ?: return@mapNotNull null
                    if (name in setOf("rank", "rankChange", "clincher", "playoffSeed") || st.str("displayValue").isNullOrBlank()) return@mapNotNull null
                    c(name, (st.str("abbreviation") ?: st.str("shortDisplayName") ?: st.str("displayName") ?: name).uppercase().take(5))
                }.distinctBy { it.key }.take(7)
                if (own.size >= 2) filled = own
            }
            return Standings(league.id, season, kind, filled, groups, "ESPN", now)
        }

        private fun c(key: String, label: String) = StandingsColumn(key, label)

        private fun parseEntries(entries: List<JSONObject>, kind: StandingsKind): List<StandingsRow> {
            val rows = entries.mapNotNull { e -> runCatching { parseEntry(e, kind) }.getOrNull() }
            // In table order: the feed's own rank, else as it gives them (US divisions are in order).
            val ranked = if (rows.all { it.pos > 0 }) rows.sortedBy { it.pos } else rows
            val withPos = ranked.mapIndexed { i, r -> if (r.pos > 0) r else r.copy(pos = i + 1) }
            if (kind != StandingsKind.RACING || withPos.isEmpty()) return withPos
            // Racing: points behind the leader.
            val lead = withPos.first().values["points"]?.toDoubleOrNull() ?: return withPos
            return withPos.map { r ->
                val p = r.values["points"]?.toDoubleOrNull()
                val behind = if (p == null || r.pos == 1) "–" else "-" + fmt(lead - p)
                r.copy(values = r.values + ("behind" to behind))
            }
        }

        private fun parseEntry(e: JSONObject, kind: StandingsKind): StandingsRow? {
            val team = e.optJSONObject("team")
            val athlete = e.optJSONObject("athlete")
            val who = team ?: athlete ?: return null
            val id = who.str("id") ?: return null
            val stats = HashMap<String, JSONObject>()
            var record: String? = null; var home: String? = null; var road: String? = null
            e.optJSONArray("stats").objs().forEach { s ->
                val type = s.str("type").orEmpty().lowercase()
                when (type) {
                    "total" -> record = s.str("summary") ?: s.str("displayValue")
                    "home" -> home = s.str("summary") ?: s.str("displayValue")
                    "road", "away" -> road = s.str("summary") ?: s.str("displayValue")
                }
                s.str("name")?.let { stats[it] = s }
            }
            fun shown(name: String): String? = stats[name]?.let { s -> s.str("displayValue")?.trim()?.takeIf { it.isNotEmpty() } }
            fun num(name: String): Double? = stats[name]?.opt("value")?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
            val values = HashMap<String, String>()
            // Every stat by its name (the usual columns, and a feed's own ones like cricket's).
            for (k in stats.keys) shown(k)?.let { values[k] = it }
            if (kind == StandingsKind.RACING) (shown("championshipPts") ?: shown("points"))?.let { values["points"] = it }
            if (values["gamesBehind"] == "-") values["gamesBehind"] = "–"
            val rank = num("rank")?.toInt() ?: 0
            val note = e.optJSONObject("note")
            val zone = note?.str("description")?.let { d -> StandingsZone(d, parseColor(note.str("color")) ?: zoneColor(d)) }
            val clinch = shown("clincher")?.takeIf { it != "*" && it.length <= 2 }
            val clinchText = stats["clincher"]?.str("description")
            val change = num("rankChange")?.toInt()?.takeIf { it != 0 }
            val sortValue = when (kind) {
                StandingsKind.TABLE, StandingsKind.HOCKEY, StandingsKind.RACING -> (num("points") ?: num("championshipPts") ?: 0.0) * 1000 + (num("pointDifferential") ?: 0.0)
                StandingsKind.RECORD -> (num("winPercent") ?: 0.0) * 1000 + (num("wins") ?: 0.0)
            }
            val logo = team?.optJSONArray("logos").objs().firstOrNull()?.str("href")
                ?: athlete?.optJSONObject("flag")?.str("href")
            val name = who.str("displayName") ?: who.str("name") ?: return null
            return StandingsRow(
                id = id,
                name = name,
                shortName = who.str("shortDisplayName") ?: who.str("shortName") ?: name,
                abbr = who.str("abbreviation") ?: name.take(3).uppercase(),
                logo = logo,
                pos = rank,
                values = values,
                zone = zone,
                clincher = clinch,
                clincherText = clinchText,
                seed = num("playoffSeed")?.toInt()?.takeIf { it > 0 },
                change = change,
                record = record,
                home = home,
                road = road,
                streak = shown("streak"),
                sortValue = sortValue,
                athlete = athlete != null && team == null
            )
        }

        // ------------------------------------------------------------------ TheSportsDB (nuvio-data)

        /** The "table" a nuvio-data league file carries (written once a day), or null. */
        fun parseTsdb(league: League, raw: String, now: Long = LiveTvClock.nowMs()): Standings? {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("table") ?: return null
            if (arr.length() == 0) return null
            val rows = arr.objs().mapNotNull { t ->
                val id = t.str("id") ?: return@mapNotNull null
                val name = t.str("name") ?: return@mapNotNull null
                val values = HashMap<String, String>()
                fun put(k: String, v: Any?) { v?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { values[k] = it } }
                put("gamesPlayed", t.opt("p")); put("wins", t.opt("w")); put("ties", t.opt("d")); put("losses", t.opt("l"))
                put("pointDifferential", t.opt("gd")?.let { g -> g.toString().toIntOrNull()?.let { if (it > 0) "+$it" else "$it" } ?: g })
                put("points", t.opt("pts"))
                val note = t.str("note")
                StandingsRow(
                    id = id, name = name, shortName = name, abbr = name.take(3).uppercase(),
                    logo = t.str("badge"),
                    pos = t.optInt("pos", 0),
                    values = values,
                    form = t.str("form")?.uppercase()?.filter { it in "WDL" }?.takeLast(5)?.ifBlank { null },
                    zone = note?.let { StandingsZone(it, zoneColor(it)) },
                    sortValue = (t.optDouble("pts", 0.0)) * 1000 + t.optDouble("gd", 0.0)
                )
            }
            if (rows.isEmpty()) return null
            val sorted = rows.sortedBy { if (it.pos > 0) it.pos else Int.MAX_VALUE }.mapIndexed { i, r -> if (r.pos > 0) r else r.copy(pos = i + 1) }
            val columns = listOf(c("gamesPlayed", "P"), c("wins", "W"), c("ties", "D"), c("losses", "L"), c("pointDifferential", "GD"), c("points", "PTS"))
                .filter { col -> sorted.any { !it.values[col.key].isNullOrBlank() } }
            return Standings(league.id, root.str("season"), StandingsKind.TABLE, columns, listOf(StandingsGroup(league.name, null, sorted)), "TheSportsDB", now)
        }

        // ------------------------------------------------------------------ form and place changes

        /** Last five results (oldest first) for each team, from finished games we know of. */
        fun formFrom(events: List<SportsEvent>, leagueId: String): Map<String, String> {
            val out = HashMap<String, ArrayList<Pair<Long, Char>>>()
            for (ev in events) {
                if (ev.leagueId != leagueId || ev.state != GameState.POST) continue
                val a = ev.away ?: continue
                val h = ev.home ?: continue
                val sa = a.score?.toDoubleOrNull() ?: continue
                val sh = h.score?.toDoubleOrNull() ?: continue
                fun res(us: Double, them: Double) = if (us > them) 'W' else if (us < them) 'L' else 'D'
                out.getOrPut(a.team.id) { ArrayList() } += ev.startMs to res(sa, sh)
                out.getOrPut(h.team.id) { ArrayList() } += ev.startMs to res(sh, sa)
            }
            return out.mapValues { (_, l) -> l.sortedBy { it.first }.takeLast(5).map { it.second }.joinToString("") }
        }

        /** Adds [form] to rows that don't have their own. */
        fun withForm(s: Standings, form: Map<String, String>): Standings {
            if (form.isEmpty() || s.kind == StandingsKind.RACING) return s
            return s.copy(groups = s.groups.map { g -> g.copy(rows = g.rows.map { r -> if (r.form != null) r else r.copy(form = form[r.id]?.takeIf { it.isNotEmpty() }) }) })
        }

        /**
         * Places moved since the last round. [memory] is what was saved last time (JSON), or null;
         * returns the table with the moves and what to save now. A "round" has passed when the
         * games played (or wins and losses) add up to more than last time.
         */
        fun withChanges(s: Standings, memory: String?): Pair<Standings, String> {
            val now = HashMap<String, Int>()
            var total = 0.0
            for (g in s.groups) for (r in g.rows) {
                now[g.name + "|" + r.id] = r.pos
                total += (r.values["gamesPlayed"]?.toDoubleOrNull()
                    ?: ((r.values["wins"]?.toDoubleOrNull() ?: 0.0) + (r.values["losses"]?.toDoubleOrNull() ?: 0.0) + (r.values["ties"]?.toDoubleOrNull() ?: 0.0)))
                if (s.kind == StandingsKind.RACING) total += r.values["points"]?.toDoubleOrNull() ?: 0.0
            }
            val old = runCatching { memory?.let { JSONObject(it) } }.getOrNull()
            val oldTotal = old?.optDouble("total", -1.0) ?: -1.0
            fun read(o: JSONObject?): Map<String, Int> {
                if (o == null) return emptyMap()
                val m = HashMap<String, Int>()
                o.keySet.forEach { k -> m[k] = o.optInt(k) }
                return m
            }
            val oldNow = read(old?.optJSONObject("now"))
            val base: Map<String, Int> = when {
                old == null -> emptyMap()
                total > oldTotal -> oldNow // a round was played since: last time's table is the one to compare with
                else -> read(old.optJSONObject("base"))
            }
            val save = JSONObject()
                .put("total", if (total > oldTotal || old == null) total else oldTotal)
                .put("now", JSONObject(now as Map<*, *>))
                .put("base", JSONObject(base as Map<*, *>))
            if (base.isEmpty()) return s to save.toString()
            val changed = s.copy(groups = s.groups.map { g ->
                g.copy(rows = g.rows.map { r ->
                    if (r.change != null) r
                    else {
                        val before = base[g.name + "|" + r.id]
                        if (before == null || before == r.pos) r else r.copy(change = before - r.pos)
                    }
                })
            })
            return changed to save.toString()
        }

        // ------------------------------------------------------------------ helpers

        private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }

        private fun fmt(d: Double): String = if (d == floor(d)) d.toLong().toString() else ((d * 10).toLong() / 10.0).toString()

        /** "#81D6AC" (or ESPN's occasional "##B5E7CE") as a colour. */
        fun parseColor(s: String?): Long? {
            val hex = s?.trim()?.trimStart('#')?.takeIf { it.length == 6 && it.all { ch -> ch.isLetterOrDigit() } } ?: return null
            return hex.toLongOrNull(16)?.let { 0xFF000000L or it }
        }

        /** A colour for a zone the feed doesn't colour (TheSportsDB's notes). */
        fun zoneColor(label: String): Long {
            val l = label.lowercase()
            return when {
                "relegat" in l -> 0xFFE53935
                "champions" in l -> 0xFF2B7DE9
                "europa" in l && "conference" !in l -> 0xFFF39C12
                "conference" in l -> 0xFF27AE60
                "play" in l -> 0xFF8E6CEF
                "promot" in l -> 0xFF2E9E5B
                else -> 0xFF5FA8E6
            }
        }
    }
}
