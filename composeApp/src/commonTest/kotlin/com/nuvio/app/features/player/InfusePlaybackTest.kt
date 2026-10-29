package com.nuvio.app.features.player

import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfusePlaybackTest {
    private var stored: String? = null
    private fun callbacks() = InfusePlaybackCallbacks(load = { stored }, save = { stored = it })

    private val session = WatchProgressPlaybackSession(
        profileId = 2, contentType = "series", parentMetaId = "tt123", parentMetaType = "series",
        videoId = "tt123:2:5", title = "Series", seasonNumber = 2, episodeNumber = 5,
    )
    private val request = ExternalPlayerPlaybackRequest(
        sourceUrl = "https://example.com/video.mkv?token=a+b&name=Episode%205#part",
        title = "Series & Friends", season = 2, episode = 5, episodeTitle = "Here + There",
        resumePositionMs = 125_999L, durationMs = 3_600_000L, playbackSession = session,
        subtitles = listOf(
            SubtitleInput("https://example.com/english.srt?key=a+b&lang=en", "English", "en"),
            SubtitleInput("https://example.com/arabic.srt", "Arabic", "ar"),
        ),
    )

    @Test
    fun launchEncodesResumePositionMetadataSubtitlesAndCallbacks() {
        val url = Url(buildInfusePlaybackUrl(request, "session-1"))
        assertEquals(request.sourceUrl, url.parameters["url"])
        assertEquals("125", url.parameters["position"])
        assertEquals("Series & Friends - S02E05 - Here + There", url.parameters["filename"])
        assertEquals(request.subtitles!!.map { it.url }, url.parameters.getAll("sub"))
        assertEquals("nuvio://external-player/infuse/session-1/success", url.parameters["x-success"])
        assertEquals("nuvio://external-player/infuse/session-1/error", url.parameters["x-error"])
        assertEquals("0", Url(buildInfusePlaybackUrl(request.copy(resumePositionMs = -1), "session-1")).parameters["position"])
    }

    @Test
    fun returnedPositionRetainsVideoAndProfileAfterAppRestart() {
        val launch = Url(callbacks().prepare(request))
        val restored = callbacks()
        assertTrue(restored.handleUrl(success(launch, "1800")))
        val result = assertNotNull(restored.results.value?.playbackResult())
        assertEquals(1_800_000L, result.positionMs)
        assertEquals(3_600_000L, result.durationMs)
        assertEquals(session, result.playbackSession)
        assertTrue(result.endedByUser)
        assertFalse(isProgressComplete(result.positionMs, result.durationMs!!, !result.endedByUser))
    }

    @Test
    fun knownDurationUsesExistingCompletionThreshold() {
        val callbacks = callbacks()
        val launch = Url(callbacks.prepare(request))
        callbacks.handleUrl(success(launch, "3300"))
        val result = assertNotNull(callbacks.results.value?.playbackResult())
        assertTrue(isProgressComplete(result.positionMs, result.durationMs!!, !result.endedByUser))
    }

    @Test
    fun unknownDurationDoesNotMarkPlaybackFinished() {
        val callbacks = callbacks()
        val launch = Url(callbacks.prepare(request.copy(durationMs = null)))
        callbacks.handleUrl(success(launch, "3300"))
        val result = assertNotNull(callbacks.results.value?.playbackResult())
        assertEquals(3_300_000L, result.positionMs)
        assertNull(result.durationMs)
        assertFalse(isProgressComplete(result.positionMs, 0L, !result.endedByUser))
    }

    @Test
    fun pendingResultSurvivesRestartUntilConsumed() {
        val initial = callbacks()
        val launch = Url(initial.prepare(request))
        initial.handleUrl(success(launch, "2400"))
        val restored = callbacks()
        restored.restoreResult()
        val result = assertNotNull(restored.results.value)
        assertEquals(2_400_000L, result.returnedPositionMs)
        restored.consume(result.id)
        assertNull(stored)
        assertNull(restored.results.value)
        assertTrue(restored.handleUrl(success(launch, "2400")))
        assertNull(restored.results.value)
    }

    @Test
    fun staleCallbacksCannotUpdateAnotherLaunchEvenWithSameStream() {
        val callbacks = callbacks()
        val first = Url(callbacks.prepare(request))
        val second = Url(callbacks.prepare(request))
        callbacks.handleUrl(success(first, "1200"))
        assertNull(callbacks.results.value)
        callbacks.handleUrl(success(second, "1800"))
        assertEquals(1_800_000L, callbacks.results.value?.returnedPositionMs)
    }

    @Test
    fun mismatchedVideoAndInvalidPositionsAreIgnored() {
        val callbacks = callbacks()
        val launch = Url(callbacks.prepare(request))
        callbacks.handleUrl(success(launch, "1200", "https://example.com/other.mkv"))
        assertNull(callbacks.results.value)
        listOf("-1", "NaN", "1.5", "9223372036854775807", "").forEach {
            callbacks.handleUrl(success(launch, it))
            assertNull(callbacks.results.value)
        }
        callbacks.handleUrl(success(launch, "0"))
        assertEquals(0L, callbacks.results.value?.returnedPositionMs)
    }

    @Test
    fun errorDoesNotProducePlaybackProgress() {
        val callbacks = callbacks()
        val launch = Url(callbacks.prepare(request))
        assertTrue(callbacks.handleUrl("${launch.parameters["x-error"]}?errorCode=100&errorMessage=Unsupported"))
        assertTrue(assertNotNull(callbacks.results.value).failed)
        assertNull(callbacks.results.value?.playbackResult())
        val restored = callbacks()
        restored.restoreResult()
        assertTrue(assertNotNull(restored.results.value).failed)
    }

    @Test
    fun duplicateCallbackCannotOverwritePendingProgress() {
        val callbacks = callbacks()
        val launch = Url(callbacks.prepare(request))
        callbacks.handleUrl(success(launch, "1800"))
        callbacks.handleUrl(success(launch, "2000"))
        assertEquals(1_800_000L, callbacks.results.value?.returnedPositionMs)
    }

    @Test
    fun failedLaunchClearsOnlyItsOwnPendingSession() {
        val callbacks = callbacks()
        val first = callbacks.prepare(request)
        val second = callbacks.prepare(request)
        callbacks.cancelLaunch(first)
        assertNotNull(stored)
        callbacks.cancelLaunch(second)
        assertNull(stored)
    }

    @Test
    fun unrelatedLinksAndCorruptStoredSessionsDoNotProduceResults() {
        val callbacks = callbacks()
        assertFalse(callbacks.handleUrl("nuvio://meta?type=movie&id=tt123"))
        assertFalse(callbacks.handleUrl("https://external-player/infuse/session-1/success?position=1"))
        stored = "invalid json"
        callbacks.restoreResult()
        assertNull(callbacks.results.value)
        assertTrue(callbacks.handleUrl("nuvio://external-player/infuse/session-1/success?position=1"))
    }

    private fun success(launch: Url, seconds: String, source: String = request.sourceUrl): String =
        "${launch.parameters["x-success"]}?lastPlayedUrl=${source.encodeURLParameter()}&position=${seconds.encodeURLParameter()}"
}
