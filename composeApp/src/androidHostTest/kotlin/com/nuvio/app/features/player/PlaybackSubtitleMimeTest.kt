package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import com.nuvio.app.features.streams.StreamSubtitle
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSubtitleMimeTest {
    @Test
    fun startupConfigurationsSkipRemoteSidecarsAndGuessLocalMimeFromTheUrl() {
        val configs = startupSubtitleConfigurations(
            listOf(
                StreamSubtitle(
                    url = "https://opensubtitles.example/download/12345",
                    language = "en",
                    name = "English",
                ),
                StreamSubtitle(
                    url = "file:///storage/emulated/0/Movie.en.srt",
                    language = "en",
                    name = "English",
                ),
                StreamSubtitle(
                    url = "content://downloads/captions.vtt",
                    language = "en",
                    name = "English",
                ),
            ),
        )

        assertEquals(2, configs.size)
        assertEquals("file:///storage/emulated/0/Movie.en.srt", configs[0].uri.toString())
        assertEquals(MimeTypes.APPLICATION_SUBRIP, configs[0].mimeType)
        assertEquals("content://downloads/captions.vtt", configs[1].uri.toString())
        assertEquals(MimeTypes.TEXT_VTT, configs[1].mimeType)
    }

    @Test
    fun urlGuessDoesNotRequireANetworkProbe() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://opensubtitles.example/download/12345"),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://example.com/captions.vtt?token=1"),
        )
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.mimeTypeFromUrl("file:///storage/Movie.ass"),
        )
        assertTrue("https://opensubtitles.example/download/12345".isLocalSubtitleUri().not())
        assertTrue("file:///storage/Movie.srt".isLocalSubtitleUri())
    }
}
