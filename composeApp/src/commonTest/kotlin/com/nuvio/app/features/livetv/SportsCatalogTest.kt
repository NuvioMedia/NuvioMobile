package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.sports.withoutAirDate
import com.nuvio.app.features.livetv.sports.wrestlingHeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsCatalogTest {
    @Test
    fun leaguesAreFoundByIdAndHaveUniqueIds() {
        assertEquals("NFL", SportsCatalog.league("football/nfl")?.name)
        assertEquals(Sport.FOOTBALL, SportsCatalog.league("soccer/eng.1")?.sport)
        assertNull(SportsCatalog.league("nope/none"))
        assertEquals(SportsCatalog.leagues.size, SportsCatalog.leagues.map { it.id }.toSet().size)
        assertTrue(SportsCatalog.leagues.size > 160)
    }

    @Test
    fun regionsDecideWhatShowsAndYourOwnChoicesWin() {
        val us = SportsSettings(region = "US")
        val uk = SportsSettings(region = "UK")
        val nfl = SportsCatalog.league("football/nfl")!!
        val epl = SportsCatalog.league("soccer/eng.1")!!
        val championship = SportsCatalog.league("soccer/eng.2")!!
        assertTrue(SportsCatalog.shown(nfl, us))
        assertFalse(SportsCatalog.shown(nfl, uk))
        assertTrue(SportsCatalog.shown(epl, us))
        assertTrue(SportsCatalog.shown(championship, uk))
        assertFalse(SportsCatalog.shown(championship, us))
        // Taken off in its own region; added from outside it.
        assertFalse(SportsCatalog.shown(nfl, us.copy(hiddenLeagues = setOf("football/nfl"))))
        assertTrue(SportsCatalog.shown(nfl, uk.copy(hiddenLeagues = setOf("+football/nfl"))))
        assertEquals(Sport.FOOTBALL, SportsCatalog.sportsOrder(uk).first())
        assertEquals(Sport.AMERICAN_FOOTBALL, SportsCatalog.sportsOrder(us).first())
    }

    @Test
    fun relatedLeaguesAndTeamFamilies() {
        assertTrue("soccer/uefa.champions" in SportsCatalog.relatedLeagues("soccer/eng.1"))
        assertTrue("soccer/eng.fa" in SportsCatalog.relatedLeagues("soccer/eng.1"))
        assertFalse("soccer/eng.1" in SportsCatalog.relatedLeagues("soccer/eng.1"))
        // A country followed in the World Cup is followed in every international competition.
        assertTrue("soccer/uefa.euro" in SportsCatalog.relatedLeagues("soccer/fifa.world"))
        assertFalse("soccer/fifa.wwc" in SportsCatalog.relatedLeagues("soccer/fifa.world"))
        assertEquals("soccer", SportsCatalog.teamFamily("soccer/esp.1"))
        assertEquals("football/nfl", SportsCatalog.teamFamily("football/nfl"))
        val f = SportsFollows(teams = listOf(com.nuvio.app.features.livetv.sports.FollowedTeam("soccer/eng.1", "359", "Arsenal", "ARS", null, null)))
        assertTrue(f.followsTeam("soccer/uefa.champions", "359"))
        assertFalse(f.followsTeam("football/nfl", "359"))
    }

    @Test
    fun namesAreNormalizedForTheGuide() {
        assertEquals(" man utd v spurs ", SportsCatalog.norm("Man Utd v Spurs"))
        assertEquals(" gaucho ", SportsCatalog.norm("Gaúcho"))
        assertEquals(" a and b ", SportsCatalog.norm("A & B"))
        assertTrue(SportsCatalog.hasWord(SportsCatalog.norm("Live: Premier League"), "premier league"))
        assertFalse(SportsCatalog.hasWord(SportsCatalog.norm("USA Network"), "us"))
        assertEquals("Collision live", SportsCatalog.plainSmall("Collision ᴸᴵⱽᴱ").replace(Regex(" +"), " ").trim())
        assertEquals("AEW Collision", SportsCatalog.withoutSmallMarks("AEW Collision ᴸᴵⱽᴱ"))
    }

    @Test
    fun teamNamesCoverAliasesAndSchools() {
        val epl = SportsCatalog.league("soccer/eng.1")!!
        val (strong, weak) = SportsCatalog.teamNames(TeamInfo("soccer/eng.1", "1", "Man United", "Manchester United", "MUN", null, null, null, null), epl)
        assertTrue("manchester united" in strong)
        assertTrue("man utd" in strong)
        assertTrue(weak.isEmpty() || "manchester" !in strong)
        val cfb: League = SportsCatalog.league("football/college-football")!!
        val (s2, w2) = SportsCatalog.teamNames(TeamInfo(cfb.id, "2", "Wolverines", "Michigan Wolverines", "MICH", "Michigan", null, null, null), cfb)
        assertTrue("michigan" in s2)
        assertTrue("wolverines" in w2)
        val nfl = SportsCatalog.league("football/nfl")!!
        val (s3, w3) = SportsCatalog.teamNames(TeamInfo(nfl.id, "8", "Lions", "Detroit Lions", "DET", "Detroit", null, null, null), nfl)
        assertTrue("lions" in s3 && "detroit lions" in s3)
        assertTrue("detroit" in w3)
    }

    @Test
    fun showTitlesLoseDatesAndPromotions() {
        assertEquals("NXT", withoutAirDate("NXT - October 20th 2026"))
        assertEquals("Raw", withoutAirDate("Raw: 20/10/2026"))
        assertEquals("SmackDown", withoutAirDate("SmackDown (Oct 24, 2026)"))
        assertEquals("Fright Night Collision", wrestlingHeadline("Collision #168 Fright Night Collision"))
        assertEquals("RAW", wrestlingHeadline("RAW #1743"))
        assertEquals("Friday Night SmackDown", wrestlingHeadline("WWE Friday Night SmackDown"))
        assertNotNull(SportsCatalog.regionName("UK"))
    }
}
