package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleRequestExtrasTest {

    @Test
    fun buildsExtrasSegmentInStremioFieldOrderWithEncodedValues() {
        val extras = SubtitleRequestExtras(
            filename = "John.Wick.Chapter.4.2023.720p.WEBRip.x264.AAC-[YTS.MX].mp4",
            videoSize = 1_632_335_363L,
            videoHash = "9d8c1e6c3d434f50",
        )

        assertEquals(
            "videoHash=9d8c1e6c3d434f50&videoSize=1632335363&filename=John.Wick.Chapter.4.2023.720p.WEBRip.x264.AAC-%5BYTS.MX%5D.mp4",
            extras.toPathSegment(),
        )
    }

    @Test
    fun includesOnlyProvidedExtras() {
        assertEquals(
            "filename=Movie.2024.1080p.mkv",
            SubtitleRequestExtras(filename = "Movie.2024.1080p.mkv").toPathSegment(),
        )
        assertEquals(
            "videoSize=1632335363",
            SubtitleRequestExtras(videoSize = 1_632_335_363L).toPathSegment(),
        )
        assertEquals(
            "videoHash=9d8c1e6c3d434f50",
            SubtitleRequestExtras(videoHash = "9d8c1e6c3d434f50").toPathSegment(),
        )
    }

    @Test
    fun ignoresBlankAndNonPositiveValues() {
        assertNull(SubtitleRequestExtras().toPathSegment())
        assertNull(SubtitleRequestExtras(filename = "   ", videoHash = "").toPathSegment())
        assertNull(SubtitleRequestExtras(videoSize = 0L).toPathSegment())
        assertEquals(
            "videoSize=1",
            SubtitleRequestExtras(filename = " ", videoSize = 1L).toPathSegment(),
        )
    }

    @Test
    fun buildsExtrasFromStreamBehaviorHints() {
        assertEquals(
            "videoHash=abc123&videoSize=42&filename=Movie.2024.mkv",
            SubtitleRequestExtras.from(
                StreamBehaviorHints(filename = "Movie.2024.mkv", videoSize = 42L, videoHash = "abc123"),
            )?.toPathSegment(),
        )
        assertNull(SubtitleRequestExtras.from(StreamBehaviorHints()))
    }

    @Test
    fun subtitleRequestUrlAppendsExtrasBeforeJsonSuffix() {
        val url = buildSubtitleRequestUrl(
            manifestUrl = "https://addon.example.com/manifest.json",
            type = "tv",
            videoId = "tt37532893:1:5",
            extras = SubtitleRequestExtras(
                filename = "Episode Five.mkv",
                videoSize = 123L,
                videoHash = "deadbeef",
            ),
        )

        assertEquals(
            "https://addon.example.com/subtitles/series/tt37532893%3A1%3A5/videoHash=deadbeef&videoSize=123&filename=Episode%20Five.mkv.json",
            url,
        )
    }

    @Test
    fun subtitleRequestUrlKeepsExistingShapeWhenNoUsableExtrasExist() {
        assertEquals(
            "https://addon.example.com/subtitles/series/tt37532893%3A1%3A5.json",
            buildSubtitleRequestUrl(
                manifestUrl = "https://addon.example.com/manifest.json",
                type = "series",
                videoId = "tt37532893:1:5",
                extras = SubtitleRequestExtras(filename = "  "),
            ),
        )
    }
}
