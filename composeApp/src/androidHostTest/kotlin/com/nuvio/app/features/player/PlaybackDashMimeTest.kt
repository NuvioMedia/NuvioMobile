package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackDashMimeTest {
    @Test
    fun percentEncodedDashMimeHintsConfigureDashWithoutChangingTheUrl() {
        for (key in listOf("format", "mime", "mime_type", "content_type", "type")) {
            val url = "https://example.com/playback?$key=application%2Fdash%2Bxml"
            val configuration = playbackMediaItemFromUrl(url).localConfiguration

            assertEquals(MimeTypes.APPLICATION_MPD, configuration?.mimeType, url)
            assertEquals(url, configuration?.uri.toString())
        }
    }

    @Test
    fun rawMimePlusSignsAndExplicitStreamTypesKeepWorking() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            playbackMediaItemFromUrl("https://example.com/playback?mime=application/dash+xml")
                .localConfiguration?.mimeType,
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            playbackMediaItemFromUrl(
                "https://example.com/playback?mime=application%2Fdash%2Bxml",
                streamType = "hls",
            ).localConfiguration?.mimeType,
        )
    }

    @Test
    fun invalidPercentEscapesDoNotCrashMediaItemCreation() {
        assertNull(
            playbackMediaItemFromUrl("https://example.com/playback?mime=%ZZ")
                .localConfiguration?.mimeType,
        )
    }
}
