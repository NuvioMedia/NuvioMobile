package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyRepository
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** System events (output loss, interruption) pause and resume only this device. */
class PlayerPartySuspensionTest {

    @Test
    fun suspendLocallyPausesAndMarksTheDeviceAsSuspended() {
        val runtime = runtime()
        val controller = RecordingController()
        runtime.playerController = controller
        runtime.shouldPlay = true
        runtime.partyBehind = false

        runtime.suspendLocally()

        assertTrue(runtime.systemSuspended)
        assertTrue(runtime.partyBehind)
        assertFalse(runtime.shouldPlay)
        assertFalse(runtime.systemSuspendedWasBehind)
        assertEquals(1, controller.pauses)
    }

    @Test
    fun suspendKeepsTheBehindStateThatExistedBeforeTheEvent() {
        val runtime = runtime()
        runtime.partyBehind = true
        runtime.suspendLocally()

        assertTrue(runtime.systemSuspendedWasBehind)
    }

    @Test
    fun resumeRejoinsARunningRoomAtTheCurrentPosition() {
        val runtime = runtime()
        val controller = RecordingController()
        runtime.playerController = controller
        runtime.systemSuspended = true
        runtime.systemSuspendedWasBehind = false
        runtime.partyBehind = true
        runtime.shouldPlay = false

        val resumed = runtime.resumeSuspendedPlayback(playback(paused = false))

        assertTrue(resumed)
        assertFalse(runtime.systemSuspended)
        assertFalse(runtime.partyBehind)
        assertTrue(runtime.shouldPlay)
        assertEquals(1, controller.plays)
        assertEquals(listOf(1f), controller.speeds)
        assertTrue(controller.seeks.single() in 10_000L..10_500L)
    }

    @Test
    fun resumeKeepsAMemberWhoWasAlreadyBehind() {
        val runtime = runtime()
        val controller = RecordingController()
        runtime.playerController = controller
        runtime.partyBehind = true
        runtime.shouldPlay = true
        runtime.suspendLocally()

        val resumed = runtime.resumeSuspendedPlayback(playback(paused = false))

        assertTrue(resumed)
        assertTrue(runtime.partyBehind)
        assertTrue(runtime.shouldPlay)
        assertEquals(1, controller.plays)
        assertTrue(controller.seeks.isEmpty())
    }

    @Test
    fun resumeStaysPausedWhenTheRoomIsPaused() {
        val runtime = runtime()
        val controller = RecordingController()
        runtime.playerController = controller
        runtime.systemSuspended = true
        runtime.systemSuspendedWasBehind = false
        runtime.partyBehind = true
        runtime.shouldPlay = false

        val resumed = runtime.resumeSuspendedPlayback(playback(paused = true))

        assertFalse(resumed)
        assertFalse(runtime.systemSuspended)
        assertFalse(runtime.partyBehind)
        assertFalse(runtime.shouldPlay)
        assertTrue(controller.seeks.isEmpty())
        assertEquals(0, controller.plays)
    }

    private fun playback(paused: Boolean) = PartyPlayback(
        seq = 1,
        memberId = "a",
        paused = paused,
        positionMs = 10_000,
        rate = 1f,
        at = PartyRepository.nowMs(),
    )

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Test",
            sourceUrl = "https://example.com/video.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Test",
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
            videoId = null,
            parentMetaId = "test",
            parentMetaType = "movie",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        )
    )

    private class RecordingController : PlayerEngineController {
        var plays = 0
        var pauses = 0
        val seeks = mutableListOf<Long>()
        val speeds = mutableListOf<Float>()

        override fun play() {
            plays += 1
        }

        override fun pause() {
            pauses += 1
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) {
            speeds += speed
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
