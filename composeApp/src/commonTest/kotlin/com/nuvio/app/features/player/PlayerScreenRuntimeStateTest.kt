package com.nuvio.app.features.player

import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerScreenRuntimeStateTest {

    @Test
    fun providerRowSurvivesRuntimeResolutionAndPlaybackOptionSynchronization() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val providers = listOf("OpenSubtitles v3", "AIOStreams | ElfHosted", "Third provider")
        runtime.addonSubtitles = providers.map { provider ->
            AddonSubtitle(
                id = "11742948",
                url = "https://example.com/shared.srt",
                language = "ind",
                display = "Indonesian",
                addonName = provider,
            )
        }
        val options = buildSubtitleSelectionOptions("id", emptyList(), runtime.visibleAddonSubtitles)
            .filterIsInstance<SubtitleSelectionOption.Addon>()
        assertEquals(3, options.size)
        assertEquals(3, options.map { it.id }.distinct().size)

        // The row callback writes selectionKey; the modal later replaces its pending
        // option with selectedSubtitleOptionId derived from the runtime's resolved addon.
        // Starting with B reproduces the first-tap jump, not just the warm A/B case.
        for (index in listOf(1, 0, 1, 0, 1, 2)) {
            val tapped = options[index]
            runtime.selectedAddonSubtitleId = tapped.subtitle.selectionKey
            runtime.selectedSubtitleIndex = -1

            val effective = runtime.selectedAddonSubtitle
            assertEquals(providers[index], effective?.addonName)
            assertEquals("https://example.com/shared.srt", effective?.url)
            assertEquals(tapped.id, selectedSubtitleOptionId(emptyList(), -1, effective))
        }

        // Off -> B must not synchronize the highlight back to provider A.
        runtime.selectedAddonSubtitleId = null
        assertNull(runtime.selectedAddonSubtitle)
        assertNull(selectedSubtitleOptionId(emptyList(), -1, runtime.selectedAddonSubtitle))
        runtime.selectedAddonSubtitleId = options[1].subtitle.selectionKey
        assertEquals("AIOStreams | ElfHosted", runtime.selectedAddonSubtitle?.addonName)
        assertEquals(options[1].id, selectedSubtitleOptionId(emptyList(), -1, runtime.selectedAddonSubtitle))
    }

    @Test
    fun legacyUrlSelectionRemainsReadableUntilAProviderRowIsSelected() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val first = AddonSubtitle("shared", "https://example.com/shared.srt", "en", "English", "First")
        val second = first.copy(addonName = "Second")
        runtime.addonSubtitles = listOf(first, second)
        runtime.selectedAddonSubtitleId = first.url
        assertEquals(first, runtime.selectedAddonSubtitle)
        assertEquals(first.url, runtime.selectedAddonSubtitleId)

        runtime.selectedAddonSubtitleId = second.selectionKey
        assertEquals(second, runtime.selectedAddonSubtitle)
        runtime.addonSubtitles = listOf(second.copy(), first.copy())
        assertEquals(second, runtime.selectedAddonSubtitle)
    }

    @Test
    fun controlsStartHidden() {
        assertFalse(PlayerScreenRuntime(testPlayerScreenArgs()).controlsVisible)
    }

    @Test
    fun bufferedScrubKeepsReleasedPositionUntilThePlayerAcknowledgesIt() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val buffering = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L, durationMs = 120_000L)
        runtime.playbackSnapshot = buffering
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 80_000L

        runtime.finishTimelineScrub(80_000L)
        assertFalse(runtime.isScrubbingTimeline)
        assertEquals(80_000L, runtime.scrubbingPositionMs)
        runtime.updatePlaybackSnapshot(buffering)
        assertEquals(80_000L, runtime.scrubbingPositionMs)
        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 30_250L))
        assertEquals(80_000L, runtime.scrubbingPositionMs)

        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 80_000L))
        assertNull(runtime.scrubbingPositionMs)
        assertEquals(80_000L, runtime.playbackSnapshot.positionMs)
    }

    @Test
    fun backwardScrubAlsoKeepsTheTargetDuringBuffering() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val buffering = PlayerPlaybackSnapshot(isLoading = true, positionMs = 90_000L, durationMs = 120_000L)
        runtime.playbackSnapshot = buffering

        runtime.finishTimelineScrub(20_000L)
        runtime.updatePlaybackSnapshot(buffering)
        assertEquals(20_000L, runtime.scrubbingPositionMs)

        runtime.updatePlaybackSnapshot(buffering.copy(positionMs = 20_100L))
        assertNull(runtime.scrubbingPositionMs)
    }

    @Test
    fun bufferingEndReleasesThePreviewEvenWhenThePlayerLandsOnAnotherKeyframe() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L)
        runtime.finishTimelineScrub(80_000L)

        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, positionMs = 78_000L))

        assertNull(runtime.scrubbingPositionMs)
        assertEquals(78_000L, runtime.playbackSnapshot.positionMs)
    }

    @Test
    fun activePlaybackKeepsItsExistingScrubReleaseBehavior() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 30_000L)
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 80_000L

        runtime.finishTimelineScrub(80_000L)

        assertFalse(runtime.isScrubbingTimeline)
        assertNull(runtime.scrubbingPositionMs)
        assertEquals(30_000L to 80_000L, runtime.lastManualSkipSeekPositions)
    }

    @Test
    fun oldSeekUpdatesDoNotOverrideANewerScrub() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true, positionMs = 30_000L)
        runtime.finishTimelineScrub(80_000L)
        runtime.isScrubbingTimeline = true
        runtime.scrubbingPositionMs = 100_000L

        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, positionMs = 80_000L))

        assertTrue(runtime.isScrubbingTimeline)
        assertEquals(100_000L, runtime.scrubbingPositionMs)
    }

    @Test
    fun tappingNextEpisodeDuringSearchKeepsTheCurrentJob() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val job = Job()
        runtime.nextEpisodeAutoPlayJob = job
        runtime.nextEpisodeAutoPlaySearching = true

        runtime.playNextEpisode()

        assertSame(job, runtime.nextEpisodeAutoPlayJob)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun tappingNextEpisodeDuringCountdownKeepsTheCurrentJob() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val job = Job()
        runtime.nextEpisodeAutoPlayJob = job
        runtime.nextEpisodeAutoPlayCountdown = 2

        runtime.playNextEpisode()

        assertSame(job, runtime.nextEpisodeAutoPlayJob)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun parentalGuideDoesNotRevealPlaybackControls() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        runtime.parentalWarnings = listOf(ParentalWarning(label = "Violence", severity = "Mild"))

        runtime.tryShowParentalGuide()

        assertTrue(runtime.showParentalGuide)
        assertFalse(runtime.controlsVisible)
    }

    @Test
    fun sourceFilterUpdatesInvalidateUiWithoutPlaybackUpdates() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val selectedFilter = derivedStateOf { runtime.sourceStreamsState.selectedFilter }

        assertNull(selectedFilter.value)

        runtime.sourceStreamsState = StreamsUiState(selectedFilter = "addon-id")

        assertEquals("addon-id", selectedFilter.value)
    }

    @Test
    fun episodeFilterUpdatesInvalidateUiWithoutPlaybackUpdates() {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs())
        val selectedFilter = derivedStateOf { runtime.episodeStreamsRepoState.selectedFilter }

        assertNull(selectedFilter.value)

        runtime.episodeStreamsRepoState = StreamsUiState(selectedFilter = "addon-id")

        assertEquals("addon-id", selectedFilter.value)
    }

    @Test
    fun seekScrobbleUpdate_requiresActiveIncompletePlayback() {
        assertTrue(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = true,
                progressPercent = 50f,
            ),
        )
        assertFalse(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = false,
                progressPercent = 50f,
            ),
        )
        assertFalse(
            shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = true,
                progressPercent = 80f,
            ),
        )
    }

    @Test
    fun stopScrobble_closesActiveSessionBelowOnePercent() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                progressPercent = 0f,
            ),
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                progressPercent = 0.5f,
            ),
        )
    }

    @Test
    fun stopScrobble_skipsEarlyProgressWithoutActiveSession() {
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 0.5f,
            ),
        )
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 79.99f,
            ),
        )
    }

    @Test
    fun stopScrobble_allowsCompletionWithoutActiveSession() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 80f,
            ),
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 100f,
            ),
        )
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}
