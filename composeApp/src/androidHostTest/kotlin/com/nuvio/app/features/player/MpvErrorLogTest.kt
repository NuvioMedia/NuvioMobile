package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_stream_blocked
import nuvio.composeapp.generated.resources.player_error_stream_expired
import nuvio.composeapp.generated.resources.player_error_stream_rate_limited
import nuvio.composeapp.generated.resources.player_error_stream_removed
import nuvio.composeapp.generated.resources.player_error_stream_unavailable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvErrorLogTest {

    @Test
    fun httpStatusLeadsAndYtdlHookLinesAreDropped() {
        // mpv's log for a PixelDrain file blocked for hotlinking.
        val log = MpvErrorLog()
        log.onLog("ffmpeg", isError = false, text = "https: HTTP error 403 Forbidden")
        log.onLog("cplayer", isError = true, text = "Failed to open https://pixeldrain.dev/api/file/x?download.")
        log.onLog("ytdl_hook", isError = true, text = "Subprocess failed: init")
        log.onLog("ytdl_hook", isError = true, text = "youtube-dl failed: not found or not enough permissions")

        assertEquals("HTTP 403 Forbidden\nloading failed", log.message("loading failed"))
        assertEquals(403, log.httpStatus())
    }

    @Test
    fun httpStatusesMapToTheSameExplanationsAsTv() {
        assertEquals(Res.string.player_error_stream_expired, httpStatusExplanation(401))
        assertEquals(Res.string.player_error_stream_expired, httpStatusExplanation(410))
        assertEquals(Res.string.player_error_stream_removed, httpStatusExplanation(404))
        assertEquals(Res.string.player_error_stream_rate_limited, httpStatusExplanation(429))
        assertEquals(Res.string.player_error_stream_unavailable, httpStatusExplanation(503))
        assertEquals(Res.string.player_error_stream_blocked, httpStatusExplanation(403))
        assertNull(httpStatusExplanation(302))
    }

    @Test
    fun withoutAnHttpStatusTheLastErrorLinesAndFileErrorAreShown() {
        val log = MpvErrorLog()
        log.onLog("ffmpeg/demuxer", isError = true, text = "matroska: EBML header parsing failed")
        log.onLog("ffmpeg", isError = false, text = "Warning that is not a cause")
        log.onLog("cplayer", isError = true, text = "Failed to recognize file format.")

        assertEquals(
            "[ffmpeg/demuxer] matroska: EBML header parsing failed\n" +
                "[cplayer] Failed to recognize file format.\n" +
                "unrecognized file format",
            log.message("unrecognized file format"),
        )
    }

    @Test
    fun onlyTheLastThreeErrorLinesAreKept() {
        val log = MpvErrorLog()
        (1..5).forEach { log.onLog("cplayer", isError = true, text = "error $it") }

        assertEquals("[cplayer] error 3\n[cplayer] error 4\n[cplayer] error 5", log.message(null))
    }

    @Test
    fun clearForgetsThePreviousFile() {
        val log = MpvErrorLog()
        log.onLog("ffmpeg", isError = false, text = "https: HTTP error 404 Not Found")
        log.onLog("cplayer", isError = true, text = "error")
        log.clear()

        assertNull(log.message(null))
        assertNull(log.httpStatus())
        assertEquals("loading failed", log.message(" loading failed "))
    }
}
