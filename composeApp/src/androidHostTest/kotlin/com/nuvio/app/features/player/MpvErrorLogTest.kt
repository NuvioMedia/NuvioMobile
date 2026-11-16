package com.nuvio.app.features.player

import `is`.xyz.mpv.MPV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The message cases come from NuvioTV's MpvPlaybackErrorPolicyTest, so both apps stay in step.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvErrorLogTest {

    @Test
    fun pixelDrainHotlinkBlockShowsTheHttpStatusAndReason() {
        // mpv's log for a PixelDrain file blocked for hotlinking.
        val log = MpvErrorLog()
        log.onLog("ffmpeg", MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN, "https: HTTP error 403 Forbidden")
        log.onLog("cplayer", MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR, "Failed to open https://pixeldrain.dev/api/file/x?download.")
        log.onLog("ytdl_hook", MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR, "Subprocess failed: init")
        log.onLog("ytdl_hook", MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR, "youtube-dl failed: not found or not enough permissions")

        assertEquals(403, mpvHttpStatusCode(log.logLine()))
        assertEquals(
            "The stream source is blocked or restricted. Try a different source.\n\nHTTP 403 Forbidden [loading failed]",
            mpvErrorMessage(
                fileError = "loading failed",
                logLine = log.logLine(),
                codecContext = "mkv, video hevc",
                fallback = "Unable to play this stream.",
                httpExplanation = "The stream source is blocked or restricted. Try a different source.",
                statusExplanation = "unused",
            ),
        )
    }

    @Test
    fun withoutAnHttpStatusTheCodecContextAndStatusExplanationAreUsed() {
        assertEquals(
            "This stream uses a format your device may not support. Try a different source.\n\n" +
                "video output initialization failed\nCould not open codec.\nmkv, video hevc, 3840x2160",
            mpvErrorMessage(
                fileError = "video output initialization failed",
                logLine = "[vd] Could not open codec.",
                codecContext = "mkv, video hevc, 3840x2160",
                fallback = "Unable to play this stream.",
                httpExplanation = "unused",
                statusExplanation = "This stream uses a format your device may not support. Try a different source.",
            ),
        )
    }

    @Test
    fun logGateKeepsCausesAndDropsNoise() {
        val log = MpvErrorLog()
        log.onLog("cplayer", MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN, "Timestamp discontinuity")
        log.onLog("cplayer", MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO, "fps 23.976")
        log.onLog("cplayer", MPV.mpvLogLevel.MPV_LOG_LEVEL_V, "verbose detail")
        assertNull(log.logLine())

        log.onLog("vd", MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN, "Hardware decoding of hevc is not supported")
        log.onLog("cplayer", MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO, "VO: [gpu] 1920x1080 yuv420p")
        assertEquals("[vd] Hardware decoding of hevc is not supported\n[cplayer] VO: [gpu] 1920x1080 yuv420p", log.logLine())

        log.clear()
        assertNull(log.logLine())
    }

    @Test
    fun directErrorShowsHttpCauseWithoutUrl() {
        val message = mpvDirectErrorMessage(
            fileError = "loading failed",
            logLine = "[ffmpeg] https: HTTP error 428 \n" +
                "[stream] Failed to open https://example.com/video.mp4?psig=secret\n" +
                "[ytdl_hook] Subprocess failed: init",
            fallback = "Playback error",
            httpExplanation = "\n\nThe stream source is blocked or restricted. Try a different source."
        )

        assertEquals(
            "The stream source is blocked or restricted. Try a different source.\n\nHTTP 428 [loading failed]",
            message
        )
        assertFalse(message.contains("example.com"))
        assertFalse(message.contains("psig"))
        assertFalse(message.contains("ytdl_hook"))
        assertFalse(message.contains("Failed to open"))
    }

    @Test
    fun directErrorKeepsDecoderDetailWithFileError() {
        assertEquals(
            "video output initialization failed\n" +
                "Could not open codec.\n" +
                "Failed (re)creating swapchain!\n" +
                "hevc: No decoder available for hardware decoding",
            mpvDirectErrorMessage(
                fileError = "video output initialization failed",
                logLine = "[vd] Could not open codec.\n" +
                    "[cplayer] Could not initialize video chain.\n" +
                    "[vo/gpu/libplacebo] Failed (re)creating swapchain!\n" +
                    "[ffmpeg] hevc @ 0x55aa: No decoder available for hardware decoding\n" +
                    "[ytdl_hook] Subprocess failed: init",
                fallback = "Playback error"
            )
        )
    }

    @Test
    fun directErrorPutsFormatExplanationAboveCodecDetail() {
        val message = mpvDirectErrorMessage(
            fileError = "video output initialization failed",
            logLine = "[vd] Could not open codec.\n" +
                "[ffmpeg] hevc: No decoder available for hardware decoding\n" +
                "mp4, video hevc, 1920x1080, yuv420p10, hwdec mediacodec",
            fallback = "Playback error",
            statusExplanation = "This stream uses a format your device may not support. Try a different source. [video output initialization failed]"
        )

        assertEquals(
            "This stream uses a format your device may not support. Try a different source. [video output initialization failed]\n\n" +
                "video output initialization failed\n" +
                "Could not open codec.\n" +
                "hevc: No decoder available for hardware decoding\n" +
                "mp4, video hevc, 1920x1080, yuv420p10, hwdec mediacodec",
            message
        )
    }

    @Test
    fun directErrorShowsOpenAndFormatCausesUnderTheFileError() {
        assertEquals(
            "loading failed\nVO: [gpu] 1920x1080 yuv420p\nUsing hardware decoding (mediacodec).",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = "[cplayer] VO: [gpu] 1920x1080 yuv420p\n[vd] Using hardware decoding (mediacodec).",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "loading failed\nConnection refused",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = "[stream] Failed to open\n[ffmpeg] Connection refused",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "unrecognized file format\nInvalid data found when processing input",
            mpvDirectErrorMessage(
                fileError = "unrecognized file format",
                logLine = "[lavf] Invalid data found when processing input",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "audio output initialization failed\nFailed to initialize audio driver 'audiotrack'",
            mpvDirectErrorMessage(
                fileError = "audio output initialization failed",
                logLine = "[ao] Failed to initialize audio driver 'audiotrack'\n" +
                    "[cplayer] Could not open/initialize audio device -> no sound.",
                fallback = "Playback error"
            )
        )
    }

    @Test
    fun directErrorKeepsPlaybackFileErrorsUnderTheExistingExplanation() {
        listOf(
            "loading failed",
            "audio output initialization failed",
            "video output initialization failed",
            "unrecognized file format",
            "no audio or video data played",
            "not supported",
            "something happened"
        ).forEach { fileError ->
            val message = mpvDirectErrorMessage(
                fileError = fileError,
                logLine = null,
                fallback = "Playback error",
                statusExplanation = "Existing explanation"
            )
            assertTrue(message.contains(fileError), message)
            assertTrue(message.startsWith("Existing explanation\n\n$fileError"), message)
        }
    }

    @Test
    fun directErrorPutsOpenExplanationAboveLoadingFailed() {
        assertEquals(
            "The stream server is currently unavailable. Try a different source.\n\nloading failed",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = null,
                fallback = "Playback error",
                statusExplanation = "The stream server is currently unavailable. Try a different source."
            )
        )
    }

    @Test
    fun nonHttpKindSeparatesFormatInvalidContentAndOpenFailure() {
        assertEquals(
            MpvNonHttpExplanationKind.UnsupportedFormat,
            mpvNonHttpExplanationKind("video output initialization failed", "[vd] Could not open codec.")
        )
        assertEquals(
            MpvNonHttpExplanationKind.InvalidContent,
            mpvNonHttpExplanationKind("unrecognized file format", null)
        )
        assertEquals(
            MpvNonHttpExplanationKind.OpenFailed,
            mpvNonHttpExplanationKind("loading failed", null)
        )
    }

    @Test
    fun warnLineKeepsCodecReasonAndDropsUnrelatedNoise() {
        assertTrue(mpvWarnLineIsUseful("vd", "Hardware decoding of hevc is not supported"))
        assertTrue(mpvWarnLineIsUseful("ffmpeg/video", "h264_mediacodec: Failed to initialize decoder"))
        assertTrue(mpvWarnLineIsUseful("vo/gpu/libplacebo", "Failed (re)creating swapchain!"))
        assertTrue(mpvWarnLineIsUseful("cplayer", "Could not initialize video chain."))
        assertTrue(mpvWarnLineIsUseful("vd", "Error while decoding frame!"))
        assertTrue(mpvWarnLineIsUseful("mkv", "EBML header parsing failed"))
        assertTrue(mpvWarnLineIsUseful("ffmpeg", "certificate verify failed"))
        assertTrue(mpvWarnLineIsUseful("autoconvert", "can't find video conversion for yuv420p10"))
        assertFalse(mpvWarnLineIsUseful("cplayer", "Timestamp discontinuity"))
        assertFalse(mpvWarnLineIsUseful("ffmpeg", "Queue overflow"))
        assertFalse(mpvWarnLineIsUseful("stream", "Failed to open"))
        assertFalse(mpvWarnLineIsUseful("ytdl_hook", "codec not supported"))
        assertTrue(mpvInfoLineIsUseful("VO: [gpu] 1920x1080 yuv420p"))
        assertTrue(mpvInfoLineIsUseful("Using hardware decoding (mediacodec)."))
        assertFalse(mpvInfoLineIsUseful("fps 23.976"))
    }

    @Test
    fun rememberLogKeepsTheCauseWhenLaterLinesFillTheBuffer() {
        var remembered: String? = "[ffmpeg] Connection refused"
        repeat(8) { index ->
            remembered = rememberMpvErrorLog(remembered, "[cplayer] packet $index")
        }
        val message = mpvDirectErrorMessage(
            fileError = "loading failed",
            logLine = remembered,
            fallback = "Playback error"
        )
        assertTrue(message.contains("Connection refused"))
    }

    @Test
    fun rememberLogKeepsOpenFailureAheadOfYtdlNoise() {
        val remembered = rememberMpvErrorLog(current = null, line = "[ffmpeg] https: HTTP error 428")
            .let { rememberMpvErrorLog(it, "[stream] Failed to open") }
            .let { rememberMpvErrorLog(it, "[ytdl_hook] Subprocess failed: init") }
            .let { rememberMpvErrorLog(it, "[ytdl_hook] youtube-dl failed: not found or not enough permissions") }
            .let { rememberMpvErrorLog(it, "[cplayer] loading failed") }

        assertEquals(
            "HTTP 428 [loading failed]",
            mpvDirectErrorMessage(fileError = "loading failed", logLine = remembered, fallback = "Playback error")
        )
    }

    @Test
    fun directErrorUsesFileErrorWhenMpvLoggedNothing() {
        assertEquals(
            "audio output initialization failed",
            mpvDirectErrorMessage(
                fileError = "audio output initialization failed",
                logLine = null,
                fallback = "Playback error"
            )
        )
    }
}
