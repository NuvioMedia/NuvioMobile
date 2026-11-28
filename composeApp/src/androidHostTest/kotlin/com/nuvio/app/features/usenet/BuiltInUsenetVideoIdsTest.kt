package com.nuvio.app.features.usenet

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInUsenetVideoIdsTest {
    @Test fun `only an anime catalog's ids are looked up in their meta`() {
        assertEquals("kitsu:46474", animeMetaId("kitsu:46474:5", series = true))
        assertEquals("mal:52991", animeMetaId("mal:52991:12", series = true))
        assertEquals("kitsu:1376", animeMetaId("kitsu:1376", series = false))
        assertNull(animeMetaId("tt22248376:1:5", series = true))
        assertNull(animeMetaId("tmdb:209867:1:5", series = true))
        assertNull(animeMetaId("tt0111161", series = false))
        assertNull(animeMetaId("kitsu:46474", series = true))
        assertNull(animeMetaId("custom:46474:5", series = true))
    }

    @Test fun `an episode takes the IMDb numbering its meta gives, never the catalog's own`() {
        val meta = meta("tt22248376",
            episode("kitsu:49240:1", imdbSeason = 2, imdbEpisode = 1),
            episode("kitsu:49240:2", imdbId = "tt999", imdbSeason = 1, imdbEpisode = 2),
            episode("kitsu:49240:3", imdbSeason = null, imdbEpisode = 3),
            episode("kitsu:49240:4", imdbSeason = 2, imdbEpisode = null))
        assertEquals(ImdbEquivalent("tt22248376", 2, 1), imdbEquivalent(meta, "kitsu:49240:1", series = true))
        // An episode's own IMDb id goes before the show's.
        assertEquals(ImdbEquivalent("tt999", 1, 2), imdbEquivalent(meta, "kitsu:49240:2", series = true))
        // Without both numbers the episode is not searched: the catalog's would name another one.
        assertNull(imdbEquivalent(meta, "kitsu:49240:3", series = true))
        assertNull(imdbEquivalent(meta, "kitsu:49240:4", series = true))
        assertNull(imdbEquivalent(meta, "kitsu:49240:9", series = true))
        assertNull(imdbEquivalent(meta(null, episode("kitsu:1:1", imdbSeason = 1, imdbEpisode = 1)), "kitsu:1:1", series = true))
    }

    @Test fun `a title's own IMDb id goes first, with its numbering unless the episode gives IMDb's`() {
        val plain = episode("kitsu:1:5", imdbSeason = null, imdbEpisode = null)
        val mapped = episode("kitsu:1:5", imdbSeason = 3, imdbEpisode = 2)
        assertEquals(ImdbEquivalent("tt777", 1, 5), contentImdbEquivalent("tt777", plain, series = true, season = 1, episode = 5))
        assertEquals(ImdbEquivalent("tt777", 1, 5), contentImdbEquivalent("tt777", null, series = true, season = 1, episode = 5))
        assertEquals(ImdbEquivalent("tt777", 3, 2), contentImdbEquivalent("tt777", mapped, series = true, season = 1, episode = 5))
        assertEquals(ImdbEquivalent("tt777", null, null), contentImdbEquivalent("tt777", null, series = false, season = null, episode = null))
        // A catalog's own title id, or an episode without numbering, gives nothing.
        assertNull(contentImdbEquivalent("kitsu:1", plain, series = true, season = 1, episode = 5))
        assertNull(contentImdbEquivalent(null, plain, series = true, season = 1, episode = 5))
        assertNull(contentImdbEquivalent("tt777", plain, series = true, season = null, episode = 5))
        assertNull(contentImdbEquivalent("tt777", plain, series = true, season = 1, episode = 0))
    }

    @Test fun `a film takes its meta's IMDb id`() {
        assertEquals(ImdbEquivalent("tt0245429", null, null), imdbEquivalent(meta("tt0245429"), "kitsu:1376", series = false))
        assertNull(imdbEquivalent(meta(null), "kitsu:1376", series = false))
    }

    @Test fun `the engine picks a pack's file by the numbering the release was searched by`() {
        val plain = StreamItem(addonName = BUILT_IN_USENET_GROUP_NAME, addonId = BUILT_IN_USENET_GROUP_ID, nzbUrl = "https://a.test/nzb/1")
        assertEquals(1 to 5, engineEpisode(plain, 1, 5))
        assertEquals(null to null, engineEpisode(plain, null, null))
        assertEquals(2 to 1, engineEpisode(plain.copy(usenetSeason = 2, usenetEpisode = 1), 1, 1))
    }

    private fun meta(imdbId: String?, vararg videos: MetaVideo) =
        MetaDetails(id = "kitsu:1", type = "series", name = "Show", imdbId = imdbId, videos = videos.toList())

    private fun episode(id: String, imdbId: String? = null, imdbSeason: Int?, imdbEpisode: Int?) = MetaVideo(id = id,
        title = id, season = 1, episode = id.substringAfterLast(':').toInt(),
        imdbId = imdbId, imdbSeason = imdbSeason, imdbEpisode = imdbEpisode)
}
