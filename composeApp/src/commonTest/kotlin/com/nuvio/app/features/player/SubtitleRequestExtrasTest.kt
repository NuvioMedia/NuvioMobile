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

    @Test
    fun enrichingExtrasWithComputedHashPreservesExistingFields() {
        val original = SubtitleRequestExtras(
            filename = "Movie.2024.mkv",
            videoSize = 1_500_000L,
        )
        val enriched = original.copy(
            videoHash = "cafebabe01234567",
        )
        assertEquals("cafebabe01234567", enriched.videoHash)
        assertEquals("Movie.2024.mkv", enriched.filename)
        assertEquals(1_500_000L, enriched.videoSize)
        assertEquals(
            "videoHash=cafebabe01234567&videoSize=1500000&filename=Movie.2024.mkv",
            enriched.toPathSegment(),
        )
    }

    @Test
    fun enrichingExtrasPreservesAddonVideoSizeOverComputedFileSize() {
        val addonExtras = SubtitleRequestExtras(
            filename = "Movie.mkv",
            videoSize = 999L,
        )
        // Simulate hash computation returning a different fileSize — addon-provided
        // videoSize takes priority (same logic as fetchAddonSubtitlesWithHashComputation).
        val enriched = addonExtras.copy(
            videoHash = "0011223344556677",
            videoSize = addonExtras.videoSize ?: 123_456L,
        )
        assertEquals(999L, enriched.videoSize)
    }

    @Test
    fun enrichingNullExtrasCreatesNewExtrasFromHashResult() {
        val original: SubtitleRequestExtras? = null
        val enriched = (original ?: SubtitleRequestExtras()).copy(
            videoHash = "aabbccdd",
            videoSize = (original?.videoSize ?: 42L),
        )
        assertEquals("aabbccdd", enriched.videoHash)
        assertEquals(42L, enriched.videoSize)
        assertNull(enriched.filename)
        assertEquals(
            "videoHash=aabbccdd&videoSize=42",
            enriched.toPathSegment(),
        )
    }

    @Test
    fun fromReturnsNullWhenHintsHaveOnlyBlankValues() {
        assertNull(SubtitleRequestExtras.from(StreamBehaviorHints(filename = "  ", videoSize = 0L, videoHash = "")))
    }

    @Test
    fun fromRetainsVideoHashFromHints() {
        val extras = SubtitleRequestExtras.from(
            StreamBehaviorHints(videoHash = "prehashed", videoSize = 100L),
        )
        assertEquals("prehashed", extras?.videoHash)
        assertEquals(100L, extras?.videoSize)
    }

    @Test
    fun subtitleRequestUrlPassesNullExtrasCleanly() {
        assertEquals(
            "https://addon.example.com/subtitles/movie/tt1234567.json",
            buildSubtitleRequestUrl(
                manifestUrl = "https://addon.example.com/manifest.json",
                type = "movie",
                videoId = "tt1234567",
                extras = null,
            ),
        )
    }

    @Test
    fun subtitleRequestUrlPreservesAddonQueryParameters() {
        assertEquals(
            "https://addon.example.com/subtitles/movie/tt1234567/videoHash=abc.json?key=val",
            buildSubtitleRequestUrl(
                manifestUrl = "https://addon.example.com/manifest.json?key=val",
                type = "movie",
                videoId = "tt1234567",
                extras = SubtitleRequestExtras(videoHash = "abc"),
            ),
        )
    }

    // --- filenameFromUrl tests ---

    @Test
    fun filenameFromUrlExtractsLastPathSegment() {
        assertEquals(
            "Movie.2024.1080p.mkv",
            filenameFromUrl("https://cdn.example.com/files/Movie.2024.1080p.mkv"),
        )
    }

    @Test
    fun filenameFromUrlStripsQueryParameters() {
        assertEquals(
            "video.mp4",
            filenameFromUrl("https://cdn.example.com/video.mp4?token=abc&expires=123"),
        )
    }

    @Test
    fun filenameFromUrlReturnsNullWhenNoExtension() {
        assertNull(filenameFromUrl("https://cdn.example.com/stream"))
    }

    @Test
    fun filenameFromUrlReturnsNullForEmptyPath() {
        assertNull(filenameFromUrl("https://cdn.example.com/"))
    }

    @Test
    fun filenameFromUrlHandlesTorrentSentinelUrl() {
        assertNull(filenameFromUrl("torrent://abcdef1234567890"))
    }

    // --- from(hints, sourceUrl) tests ---

    @Test
    fun fromWithUrlFallsBackToUrlFilenameWhenHintsHaveNone() {
        val extras = SubtitleRequestExtras.from(
            StreamBehaviorHints(videoSize = 42L),
            "https://cdn.example.com/files/Movie.2024.mkv",
        )
        assertEquals("Movie.2024.mkv", extras?.filename)
        assertEquals(42L, extras?.videoSize)
    }

    @Test
    fun fromWithUrlPrefersHintFilenameOverUrl() {
        val extras = SubtitleRequestExtras.from(
            StreamBehaviorHints(filename = "RealName.mkv", videoSize = 10L),
            "https://cdn.example.com/files/hash123.mp4",
        )
        assertEquals("RealName.mkv", extras?.filename)
    }

    @Test
    fun fromWithUrlReturnsNullWhenNothingUsable() {
        assertNull(SubtitleRequestExtras.from(StreamBehaviorHints(), "https://cdn.example.com/"))
    }

    @Test
    fun fromWithUrlCreatesExtrasFromUrlFilenameAlone() {
        val extras = SubtitleRequestExtras.from(
            StreamBehaviorHints(),
            "https://cdn.example.com/Episode.S01E05.720p.mkv",
        )
        assertEquals("Episode.S01E05.720p.mkv", extras?.filename)
        assertNull(extras?.videoHash)
        assertNull(extras?.videoSize)
    }
}
