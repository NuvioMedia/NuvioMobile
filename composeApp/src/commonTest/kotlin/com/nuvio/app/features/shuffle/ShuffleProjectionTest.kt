package com.nuvio.app.features.shuffle

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.applyHomeShuffle
import com.nuvio.app.features.home.components.shouldBlurContinueWatchingArtwork
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShuffleProjectionTest {
    private val videos = (1..4).map { MetaVideo("show:1:$it", "Episode $it", season = 1, episode = it) }
    private val meta = MetaDetails("show", "series", "Show", videos = videos)
    private val settings = EpisodeShuffleSettings(enabled = true)
    private val profile = EpisodeShuffleProfile(available = true, shows = mapOf("show" to settings))

    @Test
    fun latestPartialEpisodeTakesPrecedenceOverShuffleAndFurthestProgress() {
        val action = meta.shufflePrimaryAction(1, settings,
            listOf(progress(4, 20f, 1), progress(1, 30f, 2)), emptySet(), 0, EpisodeShuffle())
        assertEquals(1, action?.episodeNumber)
        assertEquals(30L, action?.resumePositionMs)
    }

    @Test
    fun completedProgressAndWatchedMarkersLeaveOnlyEligibleEpisode() {
        val action = meta.shufflePrimaryAction(1, settings,
            listOf(progress(1, 95f), progress(2, 100f)),
            setOf(watchedItemKey("series", "show", 1, 3)), 0, EpisodeShuffle())
        assertEquals(4, action?.episodeNumber)
        assertNull(action?.resumePositionMs)
    }

    @Test
    fun caughtUpUnwatchedHasNoSequentialFallback() {
        assertNull(meta.shufflePrimaryAction(1, settings,
            (1..4).map { progress(it, 100f) }, emptySet(), 0, EpisodeShuffle()))
    }

    @Test
    fun globalTogglePausesSavedSettingsAndNonSeriesNeverShuffle() {
        assertFalse(profile.copy(available = false).settings("show", "series").enabled)
        assertTrue(profile.settings("show", "tv").enabled)
        assertFalse(profile.settings("show", "movie").enabled)
        assertTrue(profile.copy(available = false).shows.getValue("show").enabled)
    }

    @Test
    fun homeKeepsLatestResumeAndRemovesDuplicateNextUp() {
        val projected = home(listOf(item(1), item(3), item(4, true)),
            listOf(progress(1, 30f, 2), progress(3, 40f, 1)))
        assertEquals(1, projected.size)
        assertEquals("show:1:1", projected.single().videoId)
        assertTrue(projected.single().shufflePlayback)
    }

    @Test
    fun homeReplacesUnairedNextUpAndClearsReleaseAlerts() {
        val projected = home(listOf(item(5, true).copy(released = "2999-01-01", isReleaseAlert = true, isNewSeasonRelease = true)),
            (1..3).map { progress(it, 100f) }).single()
        assertEquals("show:1:4", projected.videoId)
        assertEquals("Episode 4", projected.episodeTitle)
        assertNull(projected.released)
        assertFalse(projected.isReleaseAlert)
        assertFalse(projected.isNewSeasonRelease)
        assertTrue(projected.shufflePlayback)
    }

    @Test
    fun homeOmitsEmptyPoolAndRetainsNormalItemsWhenPaused() {
        assertTrue(home(listOf(item(5, true)), (1..4).map { progress(it, 100f) }).isEmpty())
        val items = listOf(item(5, true))
        assertEquals(items, applyHomeShuffle(items, 1, profile.copy(available = false), emptyList(),
            emptySet(), EpisodeShuffle(), 0) { videos })
    }

    @Test
    fun watchedShuffleArtworkRemainsVisible() {
        val watchedProfile = profile.copy(shows = mapOf("show" to settings.copy(includeWatched = true)))
        val video = videos.first().copy(thumbnail = "episode.jpg")
        val item = applyHomeShuffle(listOf(item(5, true)), 1, watchedProfile, listOf(progress(1, 100f)),
            emptySet(), EpisodeShuffle(), 0) { listOf(video) }.single()
        assertTrue(item.isWatched)
        assertFalse(item.shouldBlurContinueWatchingArtwork(true, true, video.thumbnail))
        assertTrue(item.copy(isWatched = false).shouldBlurContinueWatchingArtwork(true, true, video.thumbnail))
    }

    private fun home(items: List<ContinueWatchingItem>, progress: List<WatchProgressEntry>) =
        applyHomeShuffle(items, 1, profile, progress, emptySet(), EpisodeShuffle(), 0) { videos }

    private fun item(number: Int, next: Boolean = false) = ContinueWatchingItem(
        parentMetaId = "show", parentMetaType = "series", videoId = "show:1:$number", title = "Show",
        subtitle = "", imageUrl = null, seasonNumber = 1, episodeNumber = number,
        isNextUp = next, resumePositionMs = if (next) 0 else 30, durationMs = 100,
        progressFraction = if (next) 0f else 0.3f,
    )

    private fun progress(number: Int, percent: Float, updated: Long = 0) = WatchProgressEntry(
        contentType = "series", parentMetaId = "show", parentMetaType = "series",
        title = "Show", videoId = "show:1:$number", seasonNumber = 1, episodeNumber = number,
        lastPositionMs = percent.toLong(), durationMs = 100L, lastUpdatedEpochMs = updated,
        progressPercent = percent,
    )
}
