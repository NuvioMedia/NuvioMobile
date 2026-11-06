package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MdbListUserRatingsTest {
    @Test
    fun `ratings decode every type with precise values and skip unreadable rows`() {
        val result = decodeMdbListUserRatings("""{
          "movies":[{"rated_at":"2026-09-01T12:00:00Z","rating":9,"rating_precise":8.5,"movie":{"title":"Movie","ids":{"imdb":"tt1","tmdb":1}}}],
          "shows":[{"rated_at":"2026-09-01T12:00:00Z","rating":7,"show":{"ids":{"tmdb":2}}},{"rating":7,"show":{"ids":{"tmdb":3}}}],
          "episodes":[{"rated_at":"2026-09-01T12:00:00Z","rating":10,"rating_precise":null,"episode":{
            "season":1,"number":4,"ids":{"tmdb":500,"tvdb":600},"show":{"ids":{"imdb":"tt9","tmdb":9}}
          }}],
          "pagination":{"next_cursor":"next"}
        }""")

        assertEquals(listOf(8.5, 7.0, 10.0), result.items.map { it.rating })
        val episode = result.items.single { it.type == MdbListItemType.EPISODE }
        assertEquals("tt9", episode.media.ids.imdb)
        assertEquals(1, episode.season)
        assertEquals(4, episode.episode)
        assertEquals(500L, episode.episodeTmdbId)
        assertEquals("next", result.nextCursor)
    }

    @Test
    fun `rating lookup matches movies by ids and episodes by numbers`() {
        val snapshot = MdbListSyncSnapshot(
            42,
            ratings = listOf(
                MdbListRatingRecord(MdbListItemType.MOVIE, MdbListMedia(MdbListIds(imdb = "tt1", tmdb = 1)), 8.0, TIME),
                MdbListRatingRecord(MdbListItemType.SHOW, MdbListMedia(MdbListIds(imdb = "tt9")), 6.0, TIME),
                MdbListRatingRecord(MdbListItemType.EPISODE, MdbListMedia(MdbListIds(imdb = "tt9")), 9.0, TIME, 2, 3),
            )
        )

        assertEquals(8.0, snapshot.userRating(movie()))
        assertEquals(6.0, snapshot.userRating(show()))
        assertEquals(9.0, snapshot.userRating(show(TrackingEpisode(season = 2, number = 3))))
        assertNull(snapshot.userRating(show(TrackingEpisode(season = 2, number = 4))))
        assertNull(snapshot.copy(ratings = null).userRating(movie()))
    }

    @Test
    fun `payload uses episode ids when known and show numbers otherwise`() {
        val snapshot = MdbListSyncSnapshot(42)
        val byIds = payload(snapshot.mutationTarget(show(TrackingEpisode(season = 1, number = 2, tmdbId = 77))), 8)
        val byNumbers = payload(snapshot.mutationTarget(show(TrackingEpisode(season = 1, number = 2))), null)
        val movie = payload(snapshot.mutationTarget(movie()), 10)

        val episode = byIds.getValue("episodes").jsonArray.single().jsonObject
        assertEquals("77", episode.getValue("ids").jsonObject.getValue("tmdb").jsonPrimitive.content)
        assertEquals("8", episode.getValue("rating").jsonPrimitive.content)
        val season = byNumbers.getValue("shows").jsonArray.single().jsonObject.getValue("seasons").jsonArray.single().jsonObject
        assertEquals("1", season.getValue("number").jsonPrimitive.content)
        val nested = season.getValue("episodes").jsonArray.single().jsonObject
        assertEquals("2", nested.getValue("number").jsonPrimitive.content)
        assertNull(nested["rating"])
        assertEquals("10", movie.getValue("movies").jsonArray.single().jsonObject.getValue("rating").jsonPrimitive.content)
    }

    @Test
    fun `write failures count not found items and errors`() {
        assertEquals(0, mdbListRatingFailures("""{"updated":{"movies":1},"not_found":{"movies":[]}}"""))
        assertEquals(2, mdbListRatingFailures("""{"updated":{},"not_found":{"movies":[{"ids":{"tmdb":1}}]},"errors":[{"error":"bad"}]}"""))
        assertEquals(1, mdbListRatingFailures("""{"removed":{"movies":0},"not_found":{"movies":1}}"""))
    }

    @Test
    fun `sync fetches ratings only when enabled and they changed`() = runTest {
        val remote = MdbListTestSyncRemote().apply {
            ratings = listOf(MdbListRatingRecord(MdbListItemType.MOVIE, MdbListMedia(MdbListIds(tmdb = 1)), 8.0, TIME))
        }
        val engine = MdbListSyncEngine(remote)
        val base = MdbListSyncSnapshot(42, activities = remote.activities, watermark = MDBLIST_TEST_TIME, isInitialized = true)

        val disabled = engine.synchronize(base.copy(ratings = remote.ratings), includeRatings = false)
        assertNull(disabled.ratings)

        val loaded = engine.synchronize(base, includeRatings = true)
        assertEquals(remote.ratings, loaded.ratings)
        assertEquals(1, remote.calls.count { it == "ratings" })

        val unchanged = engine.synchronize(loaded, includeRatings = true)
        assertSame(loaded.ratings, unchanged.ratings)
        assertEquals(1, remote.calls.count { it == "ratings" })

        remote.activities = remote.activities.copy(values = remote.activities.values + ("rated_at" to TIME))
        engine.synchronize(unchanged, includeRatings = true)
        assertEquals(2, remote.calls.count { it == "ratings" })
    }

    private fun payload(target: MdbListMutationTarget?, rating: Int?) =
        Json.parseToJsonElement(mdbListRatingPayload(requireNotNull(target), rating)).jsonObject

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        ids = TrackingExternalIds(imdb = "tt1"),
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(imdb = "tt9"),
        episode = episode,
    )

    private companion object {
        const val TIME = "2026-09-06T00:00:00Z"
    }
}
