package com.nuvio.app.features.livetv.sports

/*
 * More about a game, for the bottom of its page: the same ESPN summary the latest scoring comes
 * from (one request for both). Every part is optional: ESPN gives different things for different
 * sports and at different times, and a part it doesn't give simply isn't shown.
 */

/** One team stat, side by side ("TOTAL YARDS 214 · 331"). Values for the bars when they're numbers. */
data class StatLine(val label: String, val away: String, val home: String, val awayValue: Double?, val homeValue: Double?)

/** A game's leader: "PASSING · Patrick Mahomes · 22/31, 236 YDS, 1 TD". [teamId]: whose (for the dot's colour). */
data class Performer(val category: String, val name: String, val line: String, val teamId: String?)

/** A team's last results, newest last ("W", "L", "D"). */
data class FormRow(val teamId: String?, val team: String, val results: List<String>)

/** One earlier meeting: when, and how it went ("Everton 0–2 Arsenal"). */
data class Meeting(val dateMs: Long?, val text: String)

/** A line of the league table. [mine]: one of this game's teams. */
data class TableRow(val pos: String, val team: String, val a: String, val b: String, val c: String, val mine: Boolean)

/** A team's starting line-up (football). */
data class Lineup(val team: String, val teamId: String?, val formation: String?, val players: List<String>)

data class GameExtras(
    val stats: List<StatLine> = emptyList(),
    val performers: List<Performer> = emptyList(),
    val form: List<FormRow> = emptyList(),
    val meetings: List<Meeting> = emptyList(),
    val tableTitle: String? = null,
    val tableHead: List<String> = emptyList(),
    val table: List<TableRow> = emptyList(),
    val venue: String? = null,
    val city: String? = null,
    val tempF: Int? = null,
    val conditions: String? = null,
    val referee: String? = null,
    val attendance: Int? = null,
    /** Chances before (or during) the game, in percent: home, away, draw. */
    val homeWin: Double? = null,
    val awayWin: Double? = null,
    val draw: Double? = null,
    /** The home team's chance through the game (0–1), oldest first. */
    val winHistory: List<Float> = emptyList(),
    val lineups: List<Lineup> = emptyList()
) {
    val hasMatchInfo: Boolean get() = venue != null || tempF != null || referee != null || attendance != null
    val hasOdds: Boolean get() = homeWin != null && awayWin != null
}

internal object SportsExtras {

    private fun JSONObject.s(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }

    /** "54.2%", "24:12", "4-11", "331" -> a number for the bars (the first one in "4-11"). */
    private fun num(v: String?): Double? {
        val t = v?.trim()?.removeSuffix("%") ?: return null
        Regex("""^(\d+):(\d{2})$""").find(t)?.let { m -> return m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble() }
        return Regex("""^-?\d+(?:\.\d+)?""").find(t)?.value?.toDoubleOrNull()
    }

    /** The stats worth a line, per sport, in order (ESPN's own names). Others fill in up to six. */
    private fun preferred(sport: Sport): List<String> = when (sport) {
        Sport.AMERICAN_FOOTBALL -> listOf("totalYards", "netPassingYards", "rushingYards", "turnovers", "possessionTime", "thirdDownEff")
        Sport.FOOTBALL -> listOf("possessionPct", "totalShots", "shotsOnTarget", "wonCorners", "foulsCommitted", "saves")
        Sport.BASKETBALL -> listOf("fieldGoalPct", "threePointFieldGoalPct", "totalRebounds", "assists", "turnovers", "steals")
        Sport.HOCKEY -> listOf("shotsTotal", "powerPlayGoals", "hits", "blockedShots", "takeaways", "faceoffPercent")
        Sport.BASEBALL -> listOf("hits", "runs", "errors", "homeRuns", "strikeouts", "walks")
        else -> emptyList()
    }

    fun parse(root: JSONObject, ev: SportsEvent, sport: Sport): GameExtras {
        val homeId = ev.home?.team?.id
        val awayId = ev.away?.team?.id
        val names = listOfNotNull(ev.home?.team, ev.away?.team).associate { it.id to it.name }
        fun nameOf(id: String?, fallback: String?) = names[id] ?: fallback ?: ""
        return GameExtras(
            stats = runCatching { stats(root, sport, homeId) }.getOrDefault(emptyList()),
            performers = runCatching { performers(root) }.getOrDefault(emptyList()),
            form = runCatching { form(root, ::nameOf) }.getOrDefault(emptyList()),
            meetings = runCatching { meetings(root, ::nameOf) }.getOrDefault(emptyList()),
            lineups = runCatching { lineups(root, ::nameOf) }.getOrDefault(emptyList())
        ).let { e -> runCatching { table(root, e, setOfNotNull(homeId, awayId), names.values.toSet()) }.getOrDefault(e) }
            .let { e -> runCatching { info(root, e) }.getOrDefault(e) }
            .let { e -> runCatching { odds(root, e) }.getOrDefault(e) }
    }

    // ---- team stats (boxscore)
    private fun stats(root: JSONObject, sport: Sport, homeId: String?): List<StatLine> {
        val teams = root.optJSONObject("boxscore")?.optJSONArray("teams").objs()
        if (teams.size != 2) return emptyList()
        fun isHome(t: JSONObject) = t.s("homeAway") == "home" || (t.optJSONObject("team")?.s("id") == homeId && homeId != null)
        val home = teams.firstOrNull(::isHome) ?: teams[1]
        val away = teams.firstOrNull { it !== home } ?: teams[0]
        // Some sports group their stats ("batting": [...]): one flat list either way.
        fun flat(t: JSONObject): Map<String, Pair<String, String>> {
            val out = LinkedHashMap<String, Pair<String, String>>()
            fun add(o: JSONObject) {
                val name = o.s("name") ?: return
                val value = o.s("displayValue") ?: return
                out.putIfAbsent(name, (o.s("label") ?: o.s("displayName") ?: name) to value)
            }
            t.optJSONArray("statistics").objs().forEach { o ->
                val inner = o.optJSONArray("stats")
                if (inner != null) inner.objs().forEach(::add) else add(o)
            }
            return out
        }
        val h = flat(home)
        val a = flat(away)
        val keys = preferred(sport).filter { it in h && it in a }.toMutableList()
        if (keys.size < 4) h.keys.filter { it in a && it !in keys }.take(6 - keys.size).forEach { keys += it }
        return keys.take(6).map { k ->
            val label = (h[k]?.first ?: k).uppercase()
            val av = a[k]!!.second
            val hv = h[k]!!.second
            StatLine(label, av, hv, num(av), num(hv))
        }
    }

    // ---- leaders
    private fun performers(root: JSONObject): List<Performer> {
        val perTeam = root.optJSONArray("leaders").objs()
        if (perTeam.isEmpty()) return emptyList()
        // [{team, leaders: [{displayName, leaders: [{displayValue, athlete}]}]}], one per team.
        val byTeam = perTeam.map { t ->
            val teamId = t.optJSONObject("team")?.s("id")
            t.optJSONArray("leaders").objs().mapNotNull { cat ->
                val top = cat.optJSONArray("leaders").objs().firstOrNull() ?: return@mapNotNull null
                val name = top.optJSONObject("athlete")?.let { it.s("displayName") ?: it.s("shortName") } ?: return@mapNotNull null
                Performer(
                    (cat.s("shortDisplayName") ?: cat.s("displayName") ?: cat.s("name") ?: "").uppercase()
                        .replace(" YARDS", "").replace("YARDS", "").trim(),
                    name, top.s("displayValue") ?: "", teamId
                )
            }
        }
        // Both teams' first two kinds (passing, then rushing…), each team's leader in turn.
        val out = ArrayList<Performer>()
        for (i in 0 until 3) for (list in byTeam) list.getOrNull(i)?.let { out += it }
        return out.distinctBy { it.name + it.category }.take(4)
    }

    // ---- last five
    private fun form(root: JSONObject, nameOf: (String?, String?) -> String): List<FormRow> {
        val lists = root.optJSONArray("lastFiveGames").objs().ifEmpty { root.optJSONObject("boxscore")?.optJSONArray("form").objs() }
        return lists.mapNotNull { t ->
            val team = t.optJSONObject("team")
            val id = team?.s("id")
            val results = t.optJSONArray("events").objs().mapNotNull { e -> e.s("gameResult")?.uppercase()?.take(1) }
            if (results.isEmpty()) null else FormRow(id, nameOf(id, team?.s("displayName") ?: team?.s("shortDisplayName")), results.take(5).reversed())
        }.take(2)
    }

    private fun dateOf(s: String?): Long? = SportsDates.parseIso(s?.take(16))

    // ---- earlier meetings
    private fun meetings(root: JSONObject, nameOf: (String?, String?) -> String): List<Meeting> {
        val t = root.optJSONArray("headToHeadGames").objs().firstOrNull() ?: return emptyList()
        val teamId = t.optJSONObject("team")?.s("id")
        val teamName = nameOf(teamId, t.optJSONObject("team")?.s("displayName"))
        return t.optJSONArray("events").objs().mapNotNull { e ->
            val opp = e.optJSONObject("opponent")
            val oppName = nameOf(opp?.s("id"), opp?.s("displayName") ?: opp?.s("abbreviation"))
            val hs = e.s("homeTeamScore")
            val ascore = e.s("awayTeamScore")
            val homeIsTeam = e.s("homeTeamId") == teamId
            val text = if (hs != null && ascore != null) {
                if (homeIsTeam) "$teamName $hs–$ascore $oppName" else "$oppName $hs–$ascore $teamName"
            } else e.s("score")?.let { "$teamName $it $oppName" } ?: return@mapNotNull null
            Meeting(dateOf(e.s("gameDate")), text)
        }.take(3)
    }

    // ---- line-ups (football)
    private fun lineups(root: JSONObject, nameOf: (String?, String?) -> String): List<Lineup> =
        root.optJSONArray("rosters").objs().mapNotNull { r ->
            val team = r.optJSONObject("team")
            val starters = r.optJSONArray("roster").objs().filter { it.optBoolean("starter", false) }.mapNotNull { p ->
                val n = p.optJSONObject("athlete")?.let { it.s("shortName") ?: it.s("displayName") } ?: return@mapNotNull null
                listOfNotNull(p.s("jersey"), n).joinToString(" ")
            }
            if (starters.size < 5) null else Lineup(nameOf(team?.s("id"), team?.s("displayName")), team?.s("id"), r.s("formation"), starters)
        }.take(2)

    // ---- standings: the top three and the rows around this game's teams
    private fun table(root: JSONObject, e: GameExtras, ids: Set<String>, teamNames: Set<String>): GameExtras {
        val st = root.optJSONObject("standings") ?: return e
        val groups = st.optJSONArray("groups").objs()
        fun entries(g: JSONObject) = g.optJSONObject("standings")?.optJSONArray("entries").objs()
        fun isMine(en: JSONObject) = en.s("id") in ids || (en.s("team") ?: en.optJSONObject("team")?.s("displayName")) in teamNames
        val group = groups.firstOrNull { g -> entries(g).any(::isMine) } ?: groups.firstOrNull() ?: return e
        val all = entries(group)
        if (all.isEmpty()) return e
        fun stat(en: JSONObject, vararg n: String): String? {
            val stats = en.optJSONArray("stats").objs()
            for (name in n) stats.firstOrNull { it.s("name") == name || it.s("type") == name }?.let { s -> s.s("displayValue")?.let { return it } }
            return null
        }
        val soccerLike = all.any { stat(it, "points") != null && stat(it, "gamesPlayed") != null }
        val head = if (soccerLike) listOf("P", "GD", "Pts") else listOf("W", "L", "PCT")
        val rows = all.mapIndexed { i, en ->
            val name = en.s("team") ?: en.optJSONObject("team")?.s("displayName") ?: "?"
            val pos = stat(en, "rank", "position") ?: (i + 1).toString()
            if (soccerLike) TableRow(pos, name, stat(en, "gamesPlayed") ?: "", stat(en, "pointDifferential", "differential") ?: "", stat(en, "points") ?: "", isMine(en))
            else TableRow(pos, name, stat(en, "wins") ?: "", stat(en, "losses") ?: "", stat(en, "winPercent", "leagueWinPercent") ?: "", isMine(en))
        }
        // The top three, and one either side of each of this game's teams (in table order).
        val keep = sortedSetOf(0, 1, 2)
        rows.forEachIndexed { i, r -> if (r.mine) { keep += i - 1; keep += i; keep += i + 1 } }
        val slice = keep.filter { it in rows.indices }.map { rows[it] }.take(8)
        val title = group.s("header") ?: st.s("header")
        return e.copy(tableTitle = title, tableHead = head, table = slice)
    }

    // ---- venue, weather, referee, attendance
    private fun info(root: JSONObject, e: GameExtras): GameExtras {
        val gi = root.optJSONObject("gameInfo") ?: return e
        val venue = gi.optJSONObject("venue")
        val addr = venue?.optJSONObject("address")
        val weather = gi.optJSONObject("weather")
        val refs = gi.optJSONArray("officials").objs()
        val ref = (refs.firstOrNull { it.optJSONObject("position")?.s("name")?.equals("Referee", true) == true } ?: refs.minByOrNull { it.optInt("order", 99) })
            ?.let { it.s("displayName") ?: it.s("fullName") }
        return e.copy(
            venue = venue?.s("fullName"),
            city = listOfNotNull(addr?.s("city"), addr?.s("state") ?: addr?.s("country")).joinToString(", ").ifBlank { null },
            tempF = weather?.let { w -> if (w.has("temperature")) w.optInt("temperature") else null },
            conditions = weather?.let { it.s("displayValue") ?: it.s("conditionId")?.takeIf { c -> c.any(Char::isLetter) } },
            referee = ref,
            attendance = gi.optInt("attendance", 0).takeIf { it > 0 }
        )
    }

    // ---- chances: ESPN's prediction before the game, its win probability during it
    private fun odds(root: JSONObject, e: GameExtras): GameExtras {
        val history = root.optJSONArray("winprobability").objs().mapNotNull { p ->
            if (p.has("homeWinPercentage")) p.optDouble("homeWinPercentage").toFloat() else null
        }
        val sampled = if (history.size <= 60) history else (0 until 60).map { history[it * (history.size - 1) / 59] }
        var home: Double? = null
        var away: Double? = null
        var draw: Double? = null
        root.optJSONObject("predictor")?.let { p ->
            home = p.optJSONObject("homeTeam")?.s("gameProjection")?.toDoubleOrNull()
            away = p.optJSONObject("awayTeam")?.s("gameProjection")?.toDoubleOrNull()
            draw = p.s("tieProjection")?.toDoubleOrNull() ?: p.optJSONObject("homeTeam")?.s("tieProjection")?.toDoubleOrNull()
        }
        // During the game: the latest probability wins over the prediction.
        root.optJSONArray("winprobability").objs().lastOrNull()?.let { last ->
            if (last.has("homeWinPercentage")) {
                val h = last.optDouble("homeWinPercentage") * 100
                val t = if (last.has("tiePercentage")) last.optDouble("tiePercentage") * 100 else 0.0
                home = h; draw = t.takeIf { it > 0.5 }; away = (100 - h - t).coerceAtLeast(0.0)
            }
        }
        if (draw == null && home != null && away != null && 100 - home!! - away!! > 1.0) draw = 100 - home!! - away!!
        return e.copy(homeWin = home, awayWin = away, draw = draw, winHistory = sampled)
    }
}
