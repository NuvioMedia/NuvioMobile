package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoPlaybackEngineResolverTest {

    @Test
    fun nonAutoEngineSettingsAreLeftAlone() {
        assertNull(resolve(setting = AndroidPlaybackEngine.ExoPlayer))
        assertNull(resolve(setting = AndroidPlaybackEngine.Libmpv))
    }

    @Test
    fun explicitEngineSettingIsNotOverriddenByAnimeSignals() {
        assertNull(
            resolve(
                setting = AndroidPlaybackEngine.ExoPlayer,
                videoId = "kitsu:7442",
                genres = listOf("Anime"),
            ),
        )
    }

    // --- HDR / Dolby Vision detection, one field at a time --------------------------------
    //
    // Every case here pairs the HDR signal with an anime signal (an anime id prefix), because
    // ExoPlayer is also the plain fall-through result. Without the anime signal these assertions
    // would pass even if the HDR scan did not exist at all.

    @Test
    fun hdrInFilenameOverridesAnimeDetection() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(videoId = "mal:1", filename = "Show.S01E01.2160p.HDR.mkv"),
        )
    }

    @Test
    fun hdrInStreamNameOverridesAnimeDetection() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(videoId = "mal:1", streamName = "1080p HDR WEB-DL"),
        )
    }

    @Test
    fun hdrInStreamDescriptionOverridesAnimeDetection() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(videoId = "mal:1", description = "Dolby Vision profile 8"),
        )
    }

    @Test
    fun hdrInContentTitleOverridesAnimeDetection() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(videoId = "mal:1", title = "Some Movie HDR"),
        )
    }

    @Test
    fun hdrTokenVariantsAreDetected() {
        listOf(
            "movie.hdr.mkv",
            "movie.hdr10.mkv",
            "movie.hdr10+.mkv",
            "movie.dv.mkv",
            "movie.dolby vision.mkv",
            "movie.DOLBY VISION.mkv",
        ).forEach { filename ->
            assertEquals(
                AndroidPlaybackEngine.ExoPlayer,
                resolve(videoId = "mal:1", filename = filename),
                "expected HDR/DV detection for $filename",
            )
        }
    }

    @Test
    fun nonWordSeparatedAndSuffixedTokensAreNotDetected() {
        // Mirrors NuvioTV exactly: the token must be a whole word, so a dotted separator
        // ("dolby.vision") or a longer word ("hdr10plus") is not detected. Combined with an
        // anime id, these fall through to the anime branch.
        listOf(
            "movie.hdr10plus.mkv",
            "movie.dolby.vision.mkv",
        ).forEach { filename ->
            assertEquals(
                AndroidPlaybackEngine.Libmpv,
                resolve(videoId = "mal:1", filename = filename),
                "expected no HDR/DV detection for $filename",
            )
        }
    }

    @Test
    fun dvSubstringInsideAnotherWordIsNotMistakenForDolbyVision() {
        // "DVDRip" must not satisfy the \bdv\b alternative, so the anime id wins and libmpv is used.
        assertEquals(
            AndroidPlaybackEngine.Libmpv,
            resolve(videoId = "mal:12345", filename = "Show.S01.DVDRip.1080p.mkv"),
        )
    }

    @Test
    fun hdrTakesPrecedenceOverAnimeDetection() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(videoId = "mal:12345", filename = "Anime.Movie.2024.DV.mkv"),
        )
    }

    @Test
    fun hdrTakesPrecedenceOverAnimeGenre() {
        assertEquals(
            AndroidPlaybackEngine.ExoPlayer,
            resolve(genres = listOf("Animation", "Anime"), title = "Anime Movie HDR10"),
        )
    }

    // --- anime id prefixes -----------------------------------------------------------------

    @Test
    fun animeIdPrefixesSelectLibmpv() {
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(videoId = "kitsu:7442"))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(videoId = "mal:12345"))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(videoId = "anilist:21"))
    }

    @Test
    fun animeIdPrefixWithEpisodeSuffixSelectsLibmpv() {
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(videoId = "mal:12345:3"))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(videoId = "kitsu:7442:12"))
    }

    @Test
    fun nonAnimeIdsDoNotSelectLibmpv() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(videoId = "tt0137523"))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(videoId = "tmdb:1396"))
    }

    // --- genres ---------------------------------------------------------------------------

    @Test
    fun animeGenreSelectsLibmpvRegardlessOfCase() {
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("anime")))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("Anime")))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("Action", "ANIME", "Fantasy")))
    }

    @Test
    fun unrelatedGenresFallThroughToExoPlayer() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Action", "Drama")))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = emptyList()))
    }

    // --- animation + country ---------------------------------------------------------------

    @Test
    fun animationFromJapanFullNameSelectsLibmpv() {
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("Animation"), country = "Japan"))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("animation"), country = "japan"))
    }

    @Test
    fun animationFromJapanIsoCodeSelectsLibmpv() {
        // TMDB enrichment rewrites country as ISO codes.
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("Animation"), country = "JP"))
        assertEquals(AndroidPlaybackEngine.Libmpv, resolve(genres = listOf("Animation"), country = "jp"))
    }

    @Test
    fun japanIsMatchedAsAWholeTokenNotASubstring() {
        assertEquals(
            AndroidPlaybackEngine.Libmpv,
            resolve(genres = listOf("Animation"), country = "United States, Japan"),
        )
        assertEquals(
            AndroidPlaybackEngine.Libmpv,
            resolve(genres = listOf("Animation"), country = "US, JP"),
        )
    }

    @Test
    fun animationFromAnotherCountryDoesNotSelectLibmpv() {
        // Regression: TMDB enrichment produces comma-joined ISO codes such as "US, GB".
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = "US, GB"))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = "United States"))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = "JPX"))
    }

    @Test
    fun animationWithMissingCountryDoesNotSelectLibmpv() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = null))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = ""))
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Animation"), country = "   "))
    }

    @Test
    fun japanCountryWithoutAnimationGenreDoesNotSelectLibmpv() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(genres = listOf("Drama"), country = "Japan"))
    }

    // --- precedence ------------------------------------------------------------------------

    @Test
    fun animeIdPrefixWinsOverAnimationCountryCheck() {
        assertEquals(
            AndroidPlaybackEngine.Libmpv,
            resolve(videoId = "kitsu:7442", genres = listOf("Animation"), country = "United States"),
        )
    }

    @Test
    fun animeIdPrefixIsEvaluatedWithoutMetadata() {
        // The id check must not depend on genres/country being loaded yet.
        assertEquals(
            AndroidPlaybackEngine.Libmpv,
            resolve(videoId = "mal:1", genres = emptyList(), country = null),
        )
    }

    @Test
    fun noSignalsAtAllFallThroughToExoPlayer() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve())
        assertEquals(AndroidPlaybackEngine.ExoPlayer, resolve(videoId = "", title = ""))
    }

    private fun resolve(
        setting: AndroidPlaybackEngine = AndroidPlaybackEngine.Auto,
        videoId: String? = null,
        filename: String? = null,
        streamName: String? = null,
        description: String? = null,
        title: String? = null,
        genres: List<String> = emptyList(),
        country: String? = null,
    ): AndroidPlaybackEngine? = resolveAutoPlaybackEngine(
        playbackEngineSetting = setting,
        videoId = videoId,
        streamFilename = filename,
        streamName = streamName,
        streamDescription = description,
        contentTitle = title,
        genres = genres,
        country = country,
    )
}
