package com.nuvio.app.features.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replay of nuvio_log (2): playback prepared at 1193023 ms, the torrent response
 * closed at 1245041 ms, and the following prepare used 1193023 ms again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStallLogReplayTest {

    @Test
    fun closedTorrentReopensAtTheStalledFrame() {
        val initialPositionMs = 1_193_023L
        val stalledPositionMs = 1_245_041L
        val durationMs = 2_721_803L
        val session = PlaybackReopenSession(initialPositionMs)

        assertEquals(initialPositionMs, session.prepareStartPositionMs())
        session.onReady()
        assertEquals(initialPositionMs, session.prepareStartPositionMs())

        val closedStream = PlaybackException(
            "Source error",
            EOFException(),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertEquals("ERROR_CODE_IO_UNSPECIFIED", closedStream.errorCodeName)
        assertTrue(closedStream.cause is EOFException)
        assertEquals(PlaybackSourceFailure.ClosedStream, closedStream.toPlaybackSourceFailure())

        val reopened = session.onFailure(
            failure = closedStream.toPlaybackSourceFailure(),
            positionMs = stalledPositionMs,
            durationMs = durationMs,
        )

        assertTrue(reopened)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertTrue(session.probeAttempted)
        assertEquals(1, session.resumeGeneration)
        assertFalse(
            useVodCacheForSource(
                vodCacheEnabled = true,
                youtubeChunked = false,
                adaptive = false,
                loopback = true,
            ),
        )

        val cacheRejection = PlaybackException(
            "Source error",
            DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE),
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        assertEquals("ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE", cacheRejection.errorCodeName)
        assertTrue(cacheRejection.cause is DataSourceException)
        assertEquals(
            PlaybackSourceFailure.ReadPositionOutOfRange,
            cacheRejection.toPlaybackSourceFailure(),
        )

        val adoptedRewoundPosition = session.onFailure(
            failure = cacheRejection.toPlaybackSourceFailure(),
            positionMs = initialPositionMs,
            durationMs = durationMs,
        )

        assertFalse(adoptedRewoundPosition)
        assertEquals(stalledPositionMs, session.prepareStartPositionMs())
        assertEquals(1, session.resumeGeneration)
    }
}
