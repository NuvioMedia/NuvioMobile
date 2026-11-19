package com.nuvio.app.features.usenet

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsenetRankingIntegrationTest {
    private val gb = 1L shl 30
    private val ranking = StreamRankingSettings(StreamRankingPreset.BALANCED)

    @Test fun `existing saved configuration retains its original order`() {
        val old = Json.decodeFromString<UsenetSourceConfiguration>("""{"sort":"SMALLEST","maxResults":10}""")
        assertEquals(StreamRankingPreset.ORIGINAL, old.ranking.preset)
        val big = UsenetRelease("Film.2160p.BluRay.REMUX-G", "https://a/big", size = 60 * gb)
        val small = UsenetRelease("Film.1080p.WEB-DL-G", "https://a/small", size = 2 * gb)
        assertEquals(listOf(small, big), NewznabProtocol.arrange(listOf(big, small), old))
    }

    @Test fun `profile ranks before the result limit and hard search filters still apply`() {
        val big = UsenetRelease("Film.2160p.BluRay.REMUX-FraMeSToR", "https://a/big", size = 60 * gb)
        val web = UsenetRelease("Film.2160p.WEB-DL-FLUX", "https://a/web", size = 12 * gb)
        val cam = UsenetRelease("Film.1080p.HDCAM-G", "https://a/cam", size = gb)
        val passworded = web.copy(nzbUrl = "https://a/password", passworded = true)
        val config = UsenetSourceConfiguration(ranking = ranking, maxResults = 1, maxSizeGb = 20)
        assertEquals(listOf(web), NewznabProtocol.arrange(listOf(big, cam, passworded, web), config))
        assertEquals(listOf(big), NewznabProtocol.arrange(listOf(web, big), config.copy(maxSizeGb = 0)) { if (it == big) 0 else 1 })
    }

    @Test fun `episode size preferences use the complete pack episode count when available`() {
        val pack = UsenetRelease("Show.S01-S02.2160p.BluRay.REMUX-FraMeSToR", "https://a/pack", size = 80 * gb)
        val episode = UsenetRelease("Show.S01E01.1080p.WEB-DL-NTb", "https://a/episode", size = gb)
        val config = UsenetSourceConfiguration(ranking = ranking)
        // 4 GB per episode is preferred; the whole 80 GB is not used as an episode size.
        assertEquals(pack, NewznabProtocol.arrange(listOf(episode, pack), config, seasonEpisodes = mapOf(1 to 10, 2 to 10), series = true).first())
        // Known 8 GB per episode exceeds the soft 5 GB preference, so a smaller alternative leads.
        assertEquals(episode, NewznabProtocol.arrange(listOf(episode, pack), config, seasonEpisodes = mapOf(1 to 5, 2 to 5), series = true).first())
        // Missing counts are unknown, rather than an invented 80 GB episode that loses its quality tier.
        assertEquals(pack, NewznabProtocol.arrange(listOf(episode, pack), config, series = true).first())
    }

    @Test fun `duplicate selection evaluates series rules with the right context`() {
        val high = UsenetIndexer(id = "high", name = "High", apiUrl = "https://high.test/api")
        val low = UsenetIndexer(id = "low", name = "Low", apiUrl = "https://low.test/api", priority = 2)
        val profile = StreamRankingProfile(rules = listOf(StreamRankingRule("Only episodes", action = StreamRankingAction.REQUIRE,
            conditions = listOf(StreamRankingCondition(StreamRankingField.MEDIA_TYPE, StreamRankingOperator.IS, "series")))))
        val config = UsenetSourceConfiguration(indexers = listOf(high, low), ranking = StreamRankingSettings(StreamRankingPreset.CUSTOM, profile))
        val release = UsenetRelease("Show.S01E02.1080p.WEB-DL-NTb", "https://a/high", indexerId = "high")
        val repeat = release.copy(nzbUrl = "https://a/low", indexerId = "low")
        assertEquals(listOf(release), NewznabProtocol.withoutDuplicates(listOf(repeat, release), config, series = true))
        assertEquals(listOf(release), NewznabProtocol.arrange(listOf(release), config, series = true))
        assertTrue(NewznabProtocol.arrange(listOf(release), config, series = false).isEmpty())
    }
}
