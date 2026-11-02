package com.nuvio.app.features.livetv.sports

/**
 * My Teams as rows: what's on now and next across everything you follow, then a row per team
 * (and country) you follow, a row per league on your top bar, a row per whole league or sport you
 * follow, and one per name you follow. Each row runs left to right in date order, opening on the
 * game that's on or next (results are to the left of it).
 */
internal class SportsRow(
    val key: String,
    val title: String,
    val kind: Kind,
    /** For a team row: whose it is. */
    val team: FollowedTeam? = null,
    /** For a league row. */
    val league: League? = null,
    val events: List<SportsEvent>,
    /** Where the row opens: the first game that isn't over. */
    val startIndex: Int = 0
) {
    enum class Kind { UPCOMING, TEAM, LEAGUE, WORD }
}

/** [heroes]: the big card's games (on now first, then the next ones); left and right go through them. */
internal class MyTeamsPage(val heroes: List<SportsEvent>, val rows: List<SportsRow>)

internal object SportsRows {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    /** Results kept at the start of a team's row. */
    private const val RESULTS = 5

    /** The keys of My Sports' rows (not Live & Upcoming), in your order (see [SportsFollows.order]). */
    fun rowKeys(f: SportsFollows): List<String> {
        val all = LinkedHashSet<String>()
        f.teams.forEach { all += "t:${it.key}" }
        (f.pins + f.leagues).forEach { all += "l:$it" }
        f.words.forEach { all += "w:$it" }
        val placed = f.order.filter { it in all }
        return placed + all.filter { it !in placed }
    }

    /** Sports you follow something in (a team, a whole league, a league on the top bar). */
    fun followedSports(follows: SportsFollows, order: List<Sport>): List<Sport> {
        val ids = follows.teams.map { it.leagueId } + follows.leagues + follows.pins
        val sports = ids.mapNotNull { SportsCatalog.league(it)?.sport }.toSet()
        return sports.sortedBy { s -> order.indexOf(s).let { if (it < 0) 99 else it } }
    }

    /**
     * The rows; with [sport], only that sport's: its teams, its leagues and its games ("Football"
     * from the top bar shows Arsenal, England and the Premier League, not the NFL).
     */
    fun build(known: List<SportsEvent>, follows: SportsFollows, now: Long, sport: Sport? = null, isMine: (SportsEvent) -> Boolean): MyTeamsPage {
        if (sport != null) {
            fun of(id: String) = SportsCatalog.league(id)?.sport == sport
            val f = follows.copy(
                teams = follows.teams.filter { of(it.leagueId) },
                leagues = follows.leagues.filter { of(it) }.toSet(),
                pins = follows.pins.filter { of(it) },
                words = emptyList()
            )
            return build(known.filter { of(it.leagueId) }, f, now, null, isMine)
        }
        val rows = ArrayList<SportsRow>()

        // ---- Live & Upcoming: everything you follow, on now or in the next week
        val soon = known.filter { ev ->
            isMine(ev) && (ev.state == GameState.LIVE ||
                (ev.state == GameState.PRE && ev.startMs in (now - 3 * HOUR)..(now + 8 * DAY)))
        }.sortedWith(compareBy<SportsEvent> { if (it.state == GameState.LIVE) 0 else 1 }.thenBy { it.startMs })
            .take(24)
        if (soon.isNotEmpty()) rows += SportsRow("up", "Live & Upcoming", SportsRow.Kind.UPCOMING, events = soon)

        // ---- a row per team or country
        for (t in follows.teams) {
            val family = SportsCatalog.teamFamily(t.leagueId)
            val games = known.filter { ev ->
                ev.sides.any { s -> s.team.id == t.teamId && (ev.leagueId == t.leagueId || SportsCatalog.teamFamily(ev.leagueId) == family) }
            }
            rows += timeline("t:${t.key}", t.name, SportsRow.Kind.TEAM, games, now, team = t)
        }

        // ---- a row per top-bar league, then whole leagues and sports you follow
        val leagueIds = LinkedHashSet<String>().apply { addAll(follows.pins); addAll(follows.leagues) }
        for (id in leagueIds) {
            val l = SportsCatalog.league(id) ?: continue
            val all = known.filter { it.leagueId == id }
            // This week (with the last two days' results); a league between rounds: its next games.
            // The last couple of days' results, then every game ahead we have (up to five weeks;
            // capped so a busy league like MLB stays a row you can get through).
            val recent = all.filter { it.startMs >= now - 2 * DAY && (it.state == GameState.POST || it.state == GameState.OFF) }
            val ahead = all.filter { it.state == GameState.LIVE || ((it.state == GameState.PRE || it.state == GameState.OFF) && it.startMs >= now - 3 * HOUR) }
                .sortedBy { it.startMs }.take(60)
            val games = recent + ahead
            if (games.isEmpty()) continue
            rows += timeline("l:$id", l.name, SportsRow.Kind.LEAGUE, games.distinctBy { it.id }, now, league = l, results = 8)
        }

        // ---- names you follow
        for (w in follows.words) {
            val games = known.filter { it.word.equals(w, ignoreCase = true) }
            if (games.isEmpty()) continue
            rows += timeline("w:$w", "“$w”", SportsRow.Kind.WORD, games, now)
        }

        // The big card: games and events at a set time (not a week-long tournament).
        val heroes = soon.filter { it.hasTime && (it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs >= now - HOUR)) }.take(8)
        // Your order (Arrange My Sports); Live & Upcoming always first.
        val rank = rowKeys(follows).withIndex().associate { (i, k) -> k to i }
        val sorted = rows.filter { it.kind == SportsRow.Kind.UPCOMING } +
            rows.filter { it.kind != SportsRow.Kind.UPCOMING }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        return MyTeamsPage(heroes, sorted)
    }

    /** Games in date order: the last few results, then what's on and what's next. */
    private fun timeline(
        key: String, title: String, kind: SportsRow.Kind, games: List<SportsEvent>, now: Long,
        team: FollowedTeam? = null, league: League? = null, results: Int = RESULTS
    ): SportsRow {
        val sorted = games.sortedBy { it.startMs }
        val done = sorted.filter { it.state == GameState.POST || (it.state == GameState.OFF && it.startMs < now) }
        val doneIds = done.mapTo(HashSet()) { it.id }
        val ahead = sorted.filter { it.id !in doneIds }
        val past = done.takeLast(results)
        return SportsRow(key, title, kind, team, league, past + ahead, startIndex = if (ahead.isEmpty()) (past.size - 1).coerceAtLeast(0) else past.size)
    }
}
