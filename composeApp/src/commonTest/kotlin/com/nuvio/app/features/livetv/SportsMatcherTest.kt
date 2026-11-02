package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.EventSource
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.GuideSearch
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsMatcher
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.TeamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsMatcherTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val kickoff = now + hour

    private fun channel(key: String, name: String, group: String = "Sports") =
        LiveChannel(key, "pl", "Playlist", name, null, null, null, "pl::$group", group, 1, "http://h/$key.ts")

    private fun team(id: String, name: String, location: String, abbr: String) =
        TeamInfo("football/nfl", id, name, "$location $name", abbr, location, null, null, null)

    private val lions = team("8", "Lions", "Detroit", "DET")
    private val bears = team("3", "Bears", "Chicago", "CHI")
    private val packers = team("9", "Packers", "Green Bay", "GB")

    private fun game(id: String, away: TeamInfo, home: TeamInfo, broadcasts: List<String> = emptyList()) = SportsEvent(
        id = "espn:football/nfl:$id", leagueId = "football/nfl", startMs = kickoff, hasTime = true, state = GameState.PRE,
        title = "${away.name} at ${home.name}", subtitle = null, status = null,
        away = Side(away, null, null, false, false), home = Side(home, null, null, false, true),
        broadcasts = broadcasts, source = EventSource.ESPN, espnId = id,
    )

    private val espn = channel("espn", "US: ESPN HD")
    private val fox = channel("fox", "US: FOX HD")
    private val nflEvent = channel("nfl03", "NFL 03: Bears @ Lions")
    private val movies = channel("movies", "Movies 24/7")

    private val guide = GuideSearch.InMemory(
        mapOf(
            // ESPN: the game itself, named both ways.
            "espn" to listOf(EpgProgram(kickoff - 15 * 60_000L, kickoff + 3 * hour, "NFL Football: Chicago Bears at Detroit Lions")),
            // FOX: a different game at the same time.
            "fox" to listOf(EpgProgram(kickoff, kickoff + 3 * hour, "NFL Football: Packers at Vikings")),
            "movies" to listOf(EpgProgram(kickoff - hour, kickoff + 2 * hour, "Chicago Bears at Detroit Lions")),
        )
    )

    private fun match(vararg events: SportsEvent, settings: SportsSettings = SportsSettings(), channels: List<LiveChannel> = listOf(espn, fox, nflEvent, movies)) =
        SportsMatcher(guide).match(SportsMatcher.Input(events.toList(), channels, emptyMap(), 0L, settings, now)).matches

    @Test
    fun theGuideNamingBothTeamsIsTheBestMatch() {
        val g = game("1", bears, lions)
        val list = match(g)[g.id]
        assertTrue(list != null && list.isNotEmpty())
        val best = list!!.first()
        assertEquals("espn", best.channel.key)
        assertTrue(best.sure)
        assertTrue(best.how.startsWith("Guide:"))
        // The other game's channel isn't listed for this one.
        assertFalse(list.any { it.channel.key == "fox" })
    }

    @Test
    fun aChannelNamedForTheGameMatchesWithoutTheGuide() {
        val g = game("1", bears, lions)
        val list = match(g)[g.id].orEmpty()
        val byName = list.firstOrNull { it.channel.key == "nfl03" }
        assertTrue(byName != null && byName.sure)
        assertEquals("Channel name", byName!!.how)
    }

    @Test
    fun twentyFourSevenChannelsNeverCount() {
        val g = game("1", bears, lions)
        assertNull(match(g)[g.id].orEmpty().firstOrNull { it.channel.key == "movies" })
    }

    @Test
    fun yourOwnPickComesFirstAndBlockedChannelsAreLeftOut() {
        val g = game("1", bears, lions)
        val picked = SportsSettings(eventChannels = mapOf(g.id to "fox|$kickoff"), blocked = setOf("${g.id}|espn"))
        val list = match(g, settings = picked)[g.id].orEmpty()
        assertEquals("fox", list.first().channel.key)
        assertEquals(200, list.first().score)
        assertFalse(list.any { it.channel.key == "espn" })
    }

    @Test
    fun aGameNoListingNamesInFullIsNeverSure() {
        val g = game("2", packers, lions)
        // Packers at Lions: ESPN's listing names the Lions and FOX's the Packers, each with the
        // other game's opponent: "maybe" at best, never a sure match.
        val list = match(g)[g.id].orEmpty()
        assertFalse(list.any { it.sure })
        assertFalse(list.any { it.channel.key == "nfl03" })
    }

    @Test
    fun networkChannelsHelpersReadNames() {
        assertEquals("espn plus", SportsMatcher.networkKey("ESPN+"))
        assertEquals("4K", SportsMatcher.nameQuality("US: ESPN 4K"))
        assertEquals("FHD", SportsMatcher.nameQuality("ESPN FHD"))
        assertNull(SportsMatcher.nameQuality("ESPN"))
        assertTrue(SportsMatcher.isBanner("##### USA SPORTS #####"))
        assertFalse(SportsMatcher.isBanner("ESPN"))
        assertEquals("US", SportsMatcher.channelRegionCode(channel("x", "US: ESPN")))
        assertEquals("lang:es", SportsMatcher.channelRegionCode(channel("x", "ES: ESPN Deportes")))
        assertEquals(SportsMatcher.copyKey(channel("a", "ESPN FHD")), SportsMatcher.copyKey(channel("b", "ESPN HD")))
    }
}
