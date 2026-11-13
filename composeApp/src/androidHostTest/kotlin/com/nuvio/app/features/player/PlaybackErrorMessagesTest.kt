package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_stream_blocked
import nuvio.composeapp.generated.resources.player_error_stream_expired
import nuvio.composeapp.generated.resources.player_error_stream_rate_limited
import nuvio.composeapp.generated.resources.player_error_stream_removed
import nuvio.composeapp.generated.resources.player_error_stream_unavailable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackErrorMessagesTest {

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
    fun exoHttpErrorShowsStatusCodeNameAndReason() = runBlocking {
        val http = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://pixeldrain.dev/api/file/x?download")),
            ByteArray(0),
        )
        val error = ExoPlaybackException.createForSource(http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        assertEquals(
            "HTTP 403 Forbidden [ERROR_CODE_IO_BAD_HTTP_STATUS]\n\n" +
                "The stream source is blocked or restricted. Try a different source.",
            error.toDisplayMessage(),
        )
    }

    @Test
    fun exoNetworkErrorShowsTheCauseInsteadOfSourceError() = runBlocking {
        val error = ExoPlaybackException.createForSource(
            IOException("Unable to connect to host"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        assertEquals(
            "Unable to connect to host [ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]",
            error.toDisplayMessage(),
        )
    }

    @Test
    fun exoUnrecognizedFormatExplainsTheSourceReturnedNoVideo() = runBlocking {
        val error = ExoPlaybackException.createForSource(
            UnrecognizedInputFormatException("None of the available extractors could read the stream.", Uri.EMPTY, emptyList()),
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )

        assertEquals(
            "Source error: The stream source returned invalid or unplayable content. The link may have expired " +
                "or the server returned an error page instead of video.\n\nTry a different source. " +
                "[ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED]",
            error.toDisplayMessage(),
        )
    }
}
