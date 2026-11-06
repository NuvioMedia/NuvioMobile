package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklRatingsTest {
    @Test
    fun `rating body puts each kind in its own bucket`() {
        val movie = body(buildSimklRatingBody(movie(), entry = null, rating = 8))
        val item = movie.getValue("movies").jsonArray.single().jsonObject
        assertEquals("8", item.getValue("rating").jsonPrimitive.content)
        assertEquals("tt1", item.getValue("ids").jsonObject.getValue("imdb").jsonPrimitive.content)
        assertEquals("Movie", item.getValue("title").jsonPrimitive.content)

        val anime = anime()
        assertTrue("anime" in body(buildSimklRatingBody(anime, entry = null, rating = 9)))
        val removal = body(buildSimklRatingBody(anime, entry = null, rating = null))
        assertNull(removal.getValue("shows").jsonArray.single().jsonObject["rating"])

        val show = movie().copy(kind = TrackingMediaKind.SHOW)
        assertTrue("shows" in body(buildSimklRatingBody(show, entry = null, rating = 7)))
        assertTrue("anime" in body(buildSimklRatingBody(show, entry = showEntry(SimklMediaType.ANIME), rating = 7)))
    }

    @Test
    fun `rating is read from and written to the matching library entry`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                movieEntry().copy(userRating = 6),
                showEntry(SimklMediaType.SHOWS),
            ),
        )

        assertEquals(6, snapshot.ratingEntry(movie())?.userRating)
        assertNull(snapshot.ratingEntry(movie().copy(kind = TrackingMediaKind.SHOW, catalog = null)))

        val updated = snapshot.withRating(movie(), rating = 9, ratedAt = "2026-11-06T10:00:00Z")
        assertEquals(9, updated?.ratingEntry(movie())?.userRating)
        assertEquals("2026-11-06T10:00:00Z", updated?.ratingEntry(movie())?.userRatedAt)
        assertNull(updated?.withRating(movie(), rating = null, ratedAt = "x")?.ratingEntry(movie())?.userRating)
        assertNull(SimklSyncSnapshot().withRating(movie(), rating = 9, ratedAt = null))
    }

    @Test
    fun `episodes are left to other trackers`() {
        assertFalse(SimklRatings.supports(movie().copy(episode = TrackingEpisode(season = 1, number = 1))))
        assertTrue(SimklRatings.supports(movie()))
    }

    @Test
    fun `not found items count as failures`() {
        assertEquals(0, simklNotFoundCount("""{"added":{"movies":1},"not_found":{"movies":[],"shows":[]}}"""))
        assertEquals(1, simklNotFoundCount("""{"added":{"movies":0},"not_found":{"movies":[{"rating":11}]}}"""))
        assertEquals(0, simklNotFoundCount(""))
    }

    private fun body(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Movie",
        year = 2024,
        ids = TrackingExternalIds(imdb = "tt1"),
        catalog = TrackingCatalogReference(contentId = "tt1", contentType = "movie"),
    )

    private fun anime() = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        ids = TrackingExternalIds(mal = 5114),
    )

    private fun movieEntry() = SimklLibraryEntry(
        mediaType = SimklMediaType.MOVIES,
        movie = SimklMedia(title = "Movie", year = 2024, ids = mapOf("imdb" to JsonPrimitive("tt1"))),
    )

    private fun showEntry(type: SimklMediaType) = SimklLibraryEntry(
        mediaType = type,
        show = SimklMedia(title = "Show", ids = mapOf("imdb" to JsonPrimitive("tt2"))),
    )
}
