package com.nuvio.app.features.watching.application

import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueWatchingMarkWatchedTest {
    private val today = "2026-06-15"

    private fun item(
        type: String = "series",
        isNextUp: Boolean = false,
        released: String? = null,
        season: Int? = 1,
        episode: Int? = 2,
        episodeTitle: String? = "Pilot",
        thumbnail: String? = "thumb",
    ) = ContinueWatchingItem(
        parentMetaId = "tt1",
        parentMetaType = type,
        videoId = "tt1:1:2",
        title = "Show",
        subtitle = "",
        imageUrl = null,
        background = "background",
        poster = "poster",
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = thumbnail,
        released = released,
        isNextUp = isNextUp,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
    )

    @Test
    fun inProgressItemsCanBeMarked() {
        assertTrue(item().canMarkWatched(today))
        assertTrue(item(type = "movie", season = null, episode = null).canMarkWatched(today))
    }

    @Test
    fun airedNextUpCanBeMarked() {
        assertTrue(item(isNextUp = true, released = "2020-01-01").canMarkWatched(today))
    }

    @Test
    fun upcomingNextUpCannotBeMarked() {
        assertFalse(item(isNextUp = true, released = "2999-01-01").canMarkWatched(today))
    }

    @Test
    fun cloudLibraryItemsCannotBeMarked() {
        assertFalse(item(type = CloudLibraryContentType).canMarkWatched(today))
    }

    @Test
    fun episodeBecomesWatchedItemForThatEpisode() {
        val watched = item().toWatchedItem(markedAtEpochMs = 5L)

        assertEquals("tt1", watched.id)
        assertEquals("series", watched.type)
        assertEquals("Pilot", watched.name)
        assertEquals("thumb", watched.poster)
        assertEquals(1, watched.season)
        assertEquals(2, watched.episode)
        assertEquals("tt1:1:2", watched.videoId)
        assertEquals(5L, watched.markedAtEpochMs)
    }

    @Test
    fun movieKeepsItsTitleWithoutEpisodeNumbers() {
        val watched = item(
            type = "movie",
            season = null,
            episode = null,
            episodeTitle = null,
            thumbnail = null,
        ).toWatchedItem()

        assertEquals("Show", watched.name)
        assertEquals("background", watched.poster)
        assertNull(watched.season)
        assertNull(watched.episode)
    }
}
