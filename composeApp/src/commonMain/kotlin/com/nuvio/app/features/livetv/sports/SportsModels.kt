package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel

/** The sports the Sports page sorts everything into (the chips and the Pick Your Teams list). */
enum class Sport(val label: String, val icon: String) {
    AMERICAN_FOOTBALL("American Football", "🏈"),
    BASKETBALL("Basketball", "🏀"),
    HOCKEY("Hockey", "🏒"),
    BASEBALL("Baseball", "⚾"),
    FOOTBALL("Football", "⚽"),
    RUGBY("Rugby", "🏉"),
    FIGHTING("Fighting", "🥊"),
    RACING("Racing", "🏁"),
    GOLF("Golf", "⛳"),
    TENNIS("Tennis", "🎾"),
    CRICKET("Cricket", "🏏"),
    DARTS("Darts", "🎯"),
    SNOOKER("Snooker", "🎱"),
    WRESTLING("Wrestling", "💪"),
    AUSSIE_RULES("Aussie Rules", "🏉"),
    VOLLEYBALL("Volleyball", "🏐"),
    LACROSSE("Lacrosse", "🥍"),
    GAELIC("Gaelic Games", "☘️"),
    NETBALL("Netball", "🤾"),
    OTHER("More Sports", "🏅");

    /** Home team first with "v" (UK style) instead of away team first with "at" (US style). */
    val homeFirst: Boolean get() = this == FOOTBALL || this == RUGBY || this == CRICKET || this == AUSSIE_RULES
}

/** How a league's events look: two teams, or one event with many competitors (a race, a fight card). */
enum class LeagueKind { TEAMS, EVENT }

/**
 * One competition the Sports page knows about.
 *  - [espnPath]: its ESPN feed ("football/nfl"), or null for one found only in your guide.
 *  - [guideWords]: how guides usually name it, to find it in the TV guide.
 *  - [regions]: where it's shown and suggested by default ("US", "UK", "IE", "CA", "AU", "OTHER").
 */
data class League(
    val id: String,
    val sport: Sport,
    val name: String,
    val short: String,
    val espnPath: String? = null,
    val kind: LeagueKind = LeagueKind.TEAMS,
    val guideWords: List<String> = emptyList(),
    val regions: Set<String> = ALL_REGIONS,
    /** Usual length in minutes, for "ends about" times and the time bar. */
    val minutes: Int = 180,
    /** College sports: guides name the school ("Michigan"), not the nickname. */
    val schoolNames: Boolean = false,
    /** Extra ESPN query (college: every game, not only the ranked ones). */
    val espnQuery: String = "",
    /** A sport found only through the TV guide (darts, snooker…): these words in a live show's title. */
    val guideOnly: Boolean = false,
    /** sporting-events.org dataset for event dates (guide-only sports). */
    val fixtureSlug: String? = null,
    /** Badge color for the league tag. */
    val color: Long = 0xFF2A2F3A,
    /**
     * Guide-only shows that are live without saying so (wrestling's weekly shows: guides call
     * them "WWE SmackDown", not "Live: …"). Repeats and highlights are still left out.
     */
    val liveByDefault: Boolean = false,
    /** National teams (England, France, USA…): following one covers all their competitions. */
    val national: Boolean = false,
    /** Women's national teams: their own countries list (ESPN's England women aren't England men). */
    val women: Boolean = false
) {
    /** National leagues that share a countries list: same sport, men's or women's. */
    val nationalGroup: String get() = sport.name + if (women) "|w" else ""

    companion object {
        val ALL_REGIONS = setOf("US", "UK", "IE", "CA", "AU", "OTHER")
    }
}

/** A team (or athlete) as the feeds describe it. */
data class TeamInfo(
    val leagueId: String,
    val id: String,
    val name: String,
    val fullName: String,
    val abbr: String,
    val location: String?,
    val color: Long?,
    val altColor: Long?,
    val logo: String?,
    val record: String? = null
) {
    val key: String get() = "$leagueId:$id"
}

/** One side of a game: who, and the score when there is one. */
data class Side(
    val team: TeamInfo,
    val score: String?,
    val record: String?,
    val winner: Boolean,
    val home: Boolean,
    /** Score per quarter / period / inning / half. */
    val periods: List<String> = emptyList()
)

enum class GameState { PRE, LIVE, POST, OFF }

/** One line of a race result or leaderboard: "1", "Kyle Larson", "-12" (golf) or null. */
data class Standing(val pos: String, val name: String, val detail: String?)

enum class EventSource { ESPN, GUIDE, FIXTURES }

data class SportsEvent(
    val id: String,
    val leagueId: String,
    val startMs: Long,
    /** False for events that come with a date only (no start time). */
    val hasTime: Boolean,
    val state: GameState,
    val title: String,
    val subtitle: String?,
    /** "3rd · 4:12", "72'", "Final", "Halftime"… (the start time is worked out on screen). */
    val status: String?,
    val away: Side?,
    val home: Side?,
    val broadcasts: List<String> = emptyList(),
    /** "DET ball · 2nd & 7 at CHI 38". */
    val situation: String? = null,
    val source: EventSource = EventSource.ESPN,
    /** Game progress from the game clock (0..1), when the feed gives it. */
    val progress: Float? = null,
    /** Guide events: the channels showing it (already known). */
    val guideHits: List<Pair<String, EpgProgram>> = emptyList(),
    /** ESPN's own id for the game (for its scoring plays). */
    val espnId: String? = null,
    /** Short note on event cards ("Day 4 of 7", "Main card"). */
    val note: String? = null,
    /** When it ends, when known (shows found in the guide). */
    val endMs: Long? = null,
    /** A name you follow that this show mentions ("Luke Littler"). */
    val word: String? = null,
    /** A race's finishing (or running) order, a golf leaderboard: position, name, detail. */
    val standings: List<Standing> = emptyList(),
    /** A few lines about the event (wrestling shows, from TheSportsDB). */
    val about: String? = null
) {
    val sides: List<Side> get() = listOfNotNull(away, home)
    val hasScore: Boolean get() = (state == GameState.LIVE || state == GameState.POST) && sides.size == 2 && sides.all { !it.score.isNullOrBlank() }

    /** The two sides in the order people say them: "Bears at Lions", "Liverpool v Man City". */
    fun ordered(sport: Sport): List<Side> = if (sport.homeFirst) listOfNotNull(home, away) else listOfNotNull(away, home)
}

/** A channel that's showing (or probably showing) an event, and why we think so. */
data class ChannelMatch(
    val channel: LiveChannel,
    val score: Int,
    val how: String,
    val program: EpgProgram?,
    val quality: String?,
    val catchup: Boolean
) {
    val sure: Boolean get() = score >= 80
}

// ------------------------------------------------------------------ settings and follows

data class SportsSettings(
    val showInSidebar: Boolean = true,
    /** "" = from the TV's own country. */
    val region: String = "",
    val hideScores: Boolean = false,
    val scoreOnInfoBar: Boolean = true,
    val bestChannelFirst: Boolean = true,
    val remindMyTeams: Boolean = true,
    /** Minutes before the start (0 = at the start). */
    val remindMinutes: Int = 15,
    /** When a reminder goes off: switch to the game by itself, about a minute before it starts. */
    val autoSwitch: Boolean = false,
    val scoringAlerts: Boolean = false,
    /** Leave out games none of your channels show (games of teams you follow always show). */
    val onlyOnMyChannels: Boolean = false,
    /** The video window on the Sports page (games play there; off: the full-width layout). */
    val videoWindow: Boolean = true,
    /** Leagues taken off the Sports page. */
    val hiddenLeagues: Set<String> = emptySet(),
    /** A network as ESPN names it (lowercase) -> the channel you picked for it. */
    val networkChannels: Map<String, String> = emptyMap(),
    /** Event id -> "channelKey|startMs": the channel you picked for that game. */
    val eventChannels: Map<String, String> = emptyMap(),
    /** "eventId|channelKey": channels you said aren't showing that game. */
    val blocked: Set<String> = emptySet(),
    /** A team ("family|team id", see [SportsCatalog.teamFamily]) -> the channel for all its games (a team's own sports network). */
    val teamChannels: Map<String, String> = emptyMap(),
    /** A competition (league id: Formula 1, PGA Tour…) -> the channel that shows all of its events. */
    val leagueChannels: Map<String, String> = emptyMap(),
    /** Channel groups (lowercase names) whose channels go to the top of every game's list. */
    val preferredGroups: Set<String> = emptySet(),
    /** "Channels from" on the game page ("" = all; see [ChannelRegions]). */
    val channelRegion: String = "",
    /** League Networks: a network's id (see [LeagueNetworks]) -> the channel you picked for it. */
    val leagueNetworkChannels: Map<String, String> = emptyMap()
) {
    fun toJson(): String = JSONObject()
        .put("showInSidebar", showInSidebar)
        .put("region", region)
        .put("hideScores", hideScores)
        .put("scoreOnInfoBar", scoreOnInfoBar)
        .put("bestChannelFirst", bestChannelFirst)
        .put("remindMyTeams", remindMyTeams)
        .put("remindMinutes", remindMinutes)
        .put("autoSwitch", autoSwitch)
        .put("scoringAlerts", scoringAlerts)
        .put("onlyMyChannels", onlyOnMyChannels)
        .put("videoWindow", videoWindow)
        .put("hiddenLeagues", JSONArray(hiddenLeagues.toList()))
        .put("networkChannels", JSONObject(networkChannels as Map<*, *>))
        .put("eventChannels", JSONObject(eventChannels as Map<*, *>))
        .put("blocked", JSONArray(blocked.toList()))
        .put("teamChannels", JSONObject(teamChannels as Map<*, *>))
        .put("leagueChannels", JSONObject(leagueChannels as Map<*, *>))
        .put("preferredGroups", JSONArray(preferredGroups.toList()))
        .put("channelRegion", channelRegion)
        .put("leagueNetworkChannels", JSONObject(leagueNetworkChannels as Map<*, *>))
        .toString()

    companion object {
        fun fromJson(raw: String?): SportsSettings {
            if (raw.isNullOrBlank()) return SportsSettings()
            return runCatching {
                val o = JSONObject(raw)
                val d = SportsSettings()
                SportsSettings(
                    showInSidebar = o.optBoolean("showInSidebar", d.showInSidebar),
                    region = o.optString("region", ""),
                    hideScores = o.optBoolean("hideScores", d.hideScores),
                    scoreOnInfoBar = o.optBoolean("scoreOnInfoBar", d.scoreOnInfoBar),
                    bestChannelFirst = o.optBoolean("bestChannelFirst", d.bestChannelFirst),
                    remindMyTeams = o.optBoolean("remindMyTeams", d.remindMyTeams),
                    remindMinutes = o.optInt("remindMinutes", d.remindMinutes),
                    autoSwitch = o.optBoolean("autoSwitch", d.autoSwitch),
                    scoringAlerts = o.optBoolean("scoringAlerts", d.scoringAlerts),
                    // Saved under a new name: the old one was on for everyone by default, so it starts off.
                    onlyOnMyChannels = o.optBoolean("onlyMyChannels", d.onlyOnMyChannels),
                    videoWindow = o.optBoolean("videoWindow", d.videoWindow),
                    hiddenLeagues = o.optJSONArray("hiddenLeagues").strings().toSet(),
                    networkChannels = o.optJSONObject("networkChannels").stringMap(),
                    eventChannels = o.optJSONObject("eventChannels").stringMap(),
                    blocked = o.optJSONArray("blocked").strings().toSet(),
                    teamChannels = o.optJSONObject("teamChannels").stringMap(),
                    leagueChannels = o.optJSONObject("leagueChannels").stringMap(),
                    preferredGroups = o.optJSONArray("preferredGroups").strings().toSet(),
                    channelRegion = o.optString("channelRegion", ""),
                    leagueNetworkChannels = o.optJSONObject("leagueNetworkChannels").stringMap()
                )
            }.getOrDefault(SportsSettings())
        }
    }
}

/** A team you follow, with enough saved to show it before the feeds have loaded. */
data class FollowedTeam(
    val leagueId: String,
    val teamId: String,
    val name: String,
    val abbr: String,
    val color: Long?,
    val logo: String?
) {
    val key: String get() = "$leagueId:$teamId"
}

/** "Remind me" on a game. */
/** [autoSwitch]: switch to the game when it starts (null: as Sports settings say). */
data class SportsReminder(val eventId: String, val title: String, val startMs: Long, val autoSwitch: Boolean? = null)

data class SportsFollows(
    val teams: List<FollowedTeam> = emptyList(),
    /** Whole leagues (every game), by league id. */
    val leagues: Set<String> = emptySet(),
    /** Names to look for in the guide ("Luke Littler"). */
    val words: List<String> = emptyList(),
    val reminders: List<SportsReminder> = emptyList(),
    /** True once Pick Your Teams has been seen (it opens by itself the first time otherwise). */
    val setUp: Boolean = false,
    /** Leagues with their own button in the Sports top bar (and a row under My Teams), in order. */
    val pins: List<String> = emptyList(),
    /** Leagues taken off the top bar (not added back by themselves when you follow a team there). */
    val unpinned: Set<String> = emptySet(),
    /**
     * The order of My Sports' rows (Arrange My Sports): "t:" + a team's key, "l:" + a league id,
     * "w:" + a name. Rows not in it (followed since) go after, in the order they were followed.
     */
    val order: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = teams.isEmpty() && leagues.isEmpty() && words.isEmpty() && pins.isEmpty()

    /**
     * Following [teamId]: in its own league, and in every competition it plays in (a football
     * club's id is the same in the Premier League, the Champions League and the cups; a country's
     * in the World Cup, qualifiers and friendlies).
     */
    fun followsTeam(leagueId: String, teamId: String?): Boolean {
        if (teamId == null) return false
        val family = SportsCatalog.teamFamily(leagueId)
        return teams.any { it.teamId == teamId && (it.leagueId == leagueId || SportsCatalog.teamFamily(it.leagueId) == family) }
    }

    /** The followed team [team] is, if any. */
    fun followedTeam(team: TeamInfo): FollowedTeam? {
        val family = SportsCatalog.teamFamily(team.leagueId)
        return teams.firstOrNull { it.teamId == team.id && (it.leagueId == team.leagueId || SportsCatalog.teamFamily(it.leagueId) == family) }
    }

    fun toJson(): String = JSONObject()
        .put("teams", JSONArray().also { a ->
            teams.forEach { t ->
                a.put(JSONObject().put("l", t.leagueId).put("id", t.teamId).put("n", t.name).put("a", t.abbr)
                    .put("c", t.color ?: JSONObject.NULL).put("logo", t.logo ?: ""))
            }
        })
        .put("leagues", JSONArray(leagues.toList()))
        .put("words", JSONArray(words))
        .put("reminders", JSONArray().also { a ->
            reminders.forEach { r -> a.put(JSONObject().put("e", r.eventId).put("t", r.title).put("s", r.startMs).also { o -> r.autoSwitch?.let { o.put("a", it) } }) }
        })
        .put("setUp", setUp)
        .put("pins", JSONArray(pins))
        .put("unpinned", JSONArray(unpinned.toList()))
        .put("order", JSONArray(order))
        .toString()

    companion object {
        fun fromJson(raw: String?): SportsFollows {
            if (raw.isNullOrBlank()) return SportsFollows()
            return runCatching {
                val o = JSONObject(raw)
                val teams = o.optJSONArray("teams")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val t = a.optJSONObject(i) ?: return@mapNotNull null
                        FollowedTeam(
                            leagueId = t.optString("l"), teamId = t.optString("id"), name = t.optString("n"),
                            abbr = t.optString("a"),
                            color = if (t.isNull("c")) null else t.optLong("c"),
                            logo = t.optString("logo").ifBlank { null }
                        )
                    }
                }.orEmpty()
                val reminders = o.optJSONArray("reminders")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val r = a.optJSONObject(i) ?: return@mapNotNull null
                        SportsReminder(r.optString("e"), r.optString("t"), r.optLong("s"), if (r.has("a")) r.optBoolean("a") else null)
                    }
                }.orEmpty()
                SportsFollows(
                    teams = teams,
                    leagues = emptySet(),
                    words = o.optJSONArray("words").strings(),
                    reminders = reminders,
                    setUp = o.optBoolean("setUp", false),
                    // "Every game" leagues (from before there was one Follow) are followed leagues now.
                    pins = (o.optJSONArray("pins").strings() + o.optJSONArray("leagues").strings()).distinct(),
                    unpinned = o.optJSONArray("unpinned").strings().toSet(),
                    order = o.optJSONArray("order").strings()
                )
            }.getOrDefault(SportsFollows())
        }
    }
}
