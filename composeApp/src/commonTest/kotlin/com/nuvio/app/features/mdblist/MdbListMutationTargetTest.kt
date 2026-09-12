package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListMutationTargetTest {
    private val snapshot = MdbListSyncSnapshot(42)

    @Test
    fun `native MDBList identifiers survive the provider-neutral media boundary`() {
        val ids = parseTrackingExternalIds("mdblist:public:1:2").mergeMissing(TrackingExternalIds(tmdb = 50))
        assertEquals("public", ids.mdblist)
        assertTrue(ids.hasAny)
        assertEquals("public", ids.toMdbListIds()?.mdblist)
        val target = snapshot.mutationTarget(movie().copy(ids = ids))!!
        assertEquals("public", target.scrobbleBody(10.0).objectValue("movie")?.objectValue("ids")?.text("mdblist"))
    }

    @Test
    fun `unresolved anime identifiers are rejected instead of guessed from title`() {
        val reference = TrackingMediaReference(TrackingMediaKind.ANIME, "Series", ids = TrackingExternalIds(mal = 100), episode = TrackingEpisode(null, 1))
        assertNull(snapshot.mutationTarget(reference))
        assertNull(snapshot.mutationTarget(reference.copy(ids = TrackingExternalIds(imdb = "tt1"))))
    }

    @Test
    fun `movie scrobbles contain only resolvable media IDs and progress`() {
        val payload = snapshot.mutationTarget(movie())!!.scrobbleBody(12.5)
        assertEquals(setOf("movie", "progress"), payload.keys)
        assertEquals("tt1", payload.objectValue("movie")?.objectValue("ids")?.text("imdb"))
        assertEquals("12.5", payload.text("progress"))
    }

    @Test
    fun `player percentages use at most two decimal places without crossing the watched threshold`() {
        val samples = listOf(
            64.321533203125 to 64.32,
            0.20450681447982788 to 0.20,
            50.45989227294922 to 50.45,
            79.999999 to 79.99,
            80.0 to 80.0,
            100.0 to 100.0
        )
        for (reference in listOf(movie(), series())) {
            val target = snapshot.mutationTarget(reference)!!
            for ((progress, expected) in samples) {
                val encoded = target.scrobbleBody(progress).text("progress")!!
                assertEquals(expected, encoded.toDouble(), 0.0)
                assertTrue(encoded.substringAfter('.', "").length <= 2)
            }
        }
    }

    @Test
    fun `episode history sends episode IDs separately from parent show IDs`() {
        val target = snapshot.mutationTarget(series().copy(episode = TrackingEpisode(1, 2, tvdbId = 700, tmdbId = 600)))!!
        val payload = mdbListResponseElement(mdbListHistoryPayload(listOf(MdbListHistoryChange(target, MDBLIST_TEST_TIME)))).jsonObject
        assertEquals(setOf("episodes"), payload.keys)
        val item = payload.arrayValue("episodes").single().objectValue()
        assertEquals(600L, item.objectValue("ids")?.number("tmdb"))
        assertEquals(700L, item.objectValue("ids")?.number("tvdb"))
        assertEquals(null, item.objectValue("ids")?.text("imdb"))
    }

    @Test
    fun `unresolved episode IDs use exact nested coordinates and never expand the whole season`() {
        val target = snapshot.mutationTarget(series())!!
        val payload = mdbListResponseElement(mdbListHistoryPayload(listOf(MdbListHistoryChange(target, MDBLIST_TEST_TIME)))).jsonObject
        val show = payload.arrayValue("shows").single().objectValue()
        val season = show.arrayValue("seasons").single().objectValue()
        val episode = season.arrayValue("episodes").single().objectValue()
        assertEquals("tt1", show.objectValue("ids")?.text("imdb"))
        assertEquals(1, season.integer("number"))
        assertEquals(2, episode.integer("number"))
        assertEquals(MDBLIST_TEST_TIME, episode.text("watched_at"))
        assertFalse(show.containsKey("watched_at"))
        assertFalse(season.containsKey("watched_at"))
    }

    @Test
    fun `known episode mapping resolves TVDB coordinates to MDBList coordinates`() {
        val known = mdbListTestEpisode(2, 4).copy(episodeTmdbId = 600, episodeTvdbId = 700)
        val reference = series().copy(episode = TrackingEpisode(1, 15, tvdbId = 700, usesTvdbSeasonMapping = true))
        val target = snapshot.copy(watched = listOf(known)).mutationTarget(reference)!!
        assertEquals(2, target.season)
        assertEquals(4, target.episode)
        assertTrue(target.scrobbleCoordinatesResolved)
        assertEquals(600L, target.episodeTmdbId)
        assertFalse(snapshot.mutationTarget(reference)!!.scrobbleCoordinatesResolved)
    }

    private fun movie() = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt1"))
    private fun series() = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(imdb = "tt1"), episode = TrackingEpisode(1, 2))
}
