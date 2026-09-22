package com.nuvio.app.core.poster

import com.nuvio.app.core.poster.CustomPosterUrlResolver.ContentIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomPosterUrlResolverTest {

    // -- extractIds --

    @Test
    fun extractIds_parses_IMDb_id() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        assertEquals("tt0137523", ids.id)
        assertEquals("tt0137523", ids.imdbId)
        assertNull(ids.tmdbId)
    }

    @Test
    fun extractIds_parses_TMDB_id() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        assertEquals("tmdb:1396", ids.id)
        assertEquals("1396", ids.tmdbId)
        assertNull(ids.imdbId)
    }

    @Test
    fun extractIds_parses_Kitsu_id() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        assertEquals("kitsu:7442", ids.id)
        assertEquals("7442", ids.kitsuId)
        assertNull(ids.imdbId)
        assertNull(ids.tmdbId)
    }

    @Test
    fun extractIds_parses_AniList_id() {
        val ids = CustomPosterUrlResolver.extractIds("anilist:21")
        assertEquals("21", ids.anilistId)
    }

    @Test
    fun extractIds_parses_MAL_id() {
        val ids = CustomPosterUrlResolver.extractIds("mal:1535")
        assertEquals("1535", ids.malId)
    }

    @Test
    fun extractIds_uses_explicit_imdbId_over_parsed() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        assertEquals("tmdb:1396", ids.id)
        assertEquals("1396", ids.tmdbId)
        assertEquals("tt0903747", ids.imdbId)
    }

    @Test
    fun extractIds_explicit_imdbId_fills_when_stremio_id_is_not_imdb() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442", explicitImdbId = "tt0137523")
        assertEquals("7442", ids.kitsuId)
        assertEquals("tt0137523", ids.imdbId)
    }

    // -- Nuvio native placeholders --

    @Test
    fun resolve_nuvio_native_pattern_with_IMDb_id() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?id=tt0137523&id_type=imdb&type=movie", url)
    }

    @Test
    fun resolve_nuvio_native_pattern_with_TMDB_id() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?id=tmdb:1396&id_type=tmdb&type=series", url)
    }

    @Test
    fun resolve_nuvio_native_pattern_with_Kitsu_id() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?id=kitsu:7442&id_type=kitsu&type=series", url)
    }

    // -- RPDB standard pattern --

    @Test
    fun resolve_RPDB_pattern_with_IMDb_id() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "movie"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/tt0137523.jpg?fallback=true",
            url
        )
    }

    @Test
    fun resolve_RPDB_pattern_falls_back_to_TMDB_when_IMDb_id_missing() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/tmdb/poster-default/series-1396.jpg?fallback=true",
            url
        )
    }

    @Test
    fun resolve_RPDB_pattern_falls_back_to_TVDB_when_IMDb_and_TMDB_missing() {
        val ids = ContentIds(id = "tvdb:81189", tvdbId = "81189")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/tvdb/poster-default/series-81189.jpg?fallback=true",
            url
        )
    }

    @Test
    fun resolve_RPDB_with_both_IMDb_and_TMDB_prefers_IMDb() {
        val ids = ContentIds(id = "tt0903747", imdbId = "tt0903747", tmdbId = "1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/tt0903747.jpg?fallback=true",
            url
        )
    }

    // -- aioratings --

    @Test
    fun resolve_aioratings_pattern_falls_back_to_TMDB() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/mykey/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "movie"
        )
        assertEquals(
            "https://api.aioratings.com/mykey/tmdb/poster-default/movie-550.jpg?fallback=true",
            url
        )
    }

    // -- BetterPosters --

    @Test
    fun resolve_BetterPosters_pattern_with_IMDb_id() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://btttr.cc/poster/imdb/poster-default/{imdb_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://btttr.cc/poster/imdb/poster-default/tt0137523.jpg", url)
    }

    @Test
    fun resolve_BetterPosters_falls_back_to_TMDB_when_IMDb_id_missing() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://btttr.cc/poster/imdb/poster-default/{imdb_id}.jpg",
            ids, "series"
        )
        assertEquals(
            "https://btttr.cc/poster/tmdb/poster-default/series-1396.jpg",
            url
        )
    }

    // -- PostersPlus --

    @Test
    fun resolve_PostersPlus_pattern_with_TMDB_id() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://postersplus.example.com/poster?tmdb_id=1396&type=series", url)
    }

    @Test
    fun resolve_PostersPlus_with_optional_imdb_id() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=1396&imdb_id=tt0903747&type=series",
            url
        )
    }

    @Test
    fun resolve_PostersPlus_optional_imdb_id_resolves_to_empty_when_missing() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=1396&imdb_id=&type=series",
            url
        )
    }

    @Test
    fun resolve_PostersPlus_returns_null_when_tmdb_id_missing() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&type={type}",
            ids, "movie"
        )
        assertNull(url)
    }

    @Test
    fun resolve_PostersPlus_with_stremio_id_for_anime() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id?}&stremio_id={id}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=&stremio_id=kitsu:7442&type=series",
            url
        )
    }

    @Test
    fun resolve_full_PostersPlus_URL_with_static_params() {
        val ids = ContentIds(id = "tmdb:1396", tmdbId = "1396", imdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.stremio.ru/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}" +
                "&primary_client=stremio_tv_nuvio&fallback_to_imdb=true" +
                "&movie_weights=letterboxd%3A0.99",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.stremio.ru/poster?tmdb_id=1396&imdb_id=tt0903747&type=series" +
                "&primary_client=stremio_tv_nuvio&fallback_to_imdb=true" +
                "&movie_weights=letterboxd%3A0.99",
            url
        )
    }

    // -- Pipe syntax --

    @Test
    fun resolve_pipe_picks_first_available_ID() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|tmdb_id|kitsu_id}&type={type}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?content_id=tt0137523&type=movie", url)
    }

    @Test
    fun resolve_pipe_skips_unavailable_and_picks_second() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|kitsu_id|tvdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?content_id=7442&type=series", url)
    }

    @Test
    fun resolve_pipe_returns_null_when_no_declared_ID_available() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|kitsu_id}&type={type}",
            ids, "series"
        )
        assertNull(url)
    }

    @Test
    fun resolve_pipe_with_shape() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/art?id={imdb_id|tmdb_id}&type={type}&shape={shape}",
            ids, "movie", shape = "landscape"
        )
        assertEquals("https://example.com/art?id=550&type=movie&shape=landscape", url)
    }

    @Test
    fun resolve_pipe_with_explicit_imdbId_enrichment() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|tmdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?content_id=tt0903747&type=series", url)
    }

    // -- typed_id --

    @Test
    fun resolve_typed_id_for_IMDb_stays_as_is() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://api.aioratings.com/KEY/imdb/poster-default/tt0137523.jpg", url)
    }

    @Test
    fun resolve_typed_id_for_TMDB_adds_movie_prefix() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://api.aioratings.com/KEY/tmdb/poster-default/movie-550.jpg", url)
    }

    @Test
    fun resolve_typed_id_for_TMDB_series_adds_series_prefix() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "series"
        )
        assertEquals("https://api.aioratings.com/KEY/tmdb/poster-default/series-1396.jpg", url)
    }

    @Test
    fun resolve_typed_id_for_TVDB_adds_series_prefix() {
        val ids = ContentIds(id = "tvdb:81189", tvdbId = "81189")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "series"
        )
        assertEquals("https://api.aioratings.com/KEY/tvdb/poster-default/series-81189.jpg", url)
    }

    @Test
    fun resolve_universal_aioratings_URL_works_for_both_IMDb_and_TMDB() {
        val pattern = "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg?fallback=true"

        val imdbIds = CustomPosterUrlResolver.extractIds("tt0137523")
        assertEquals(
            "https://api.aioratings.com/KEY/imdb/poster-default/tt0137523.jpg?fallback=true",
            CustomPosterUrlResolver.resolve(pattern, imdbIds, "movie")
        )

        val tmdbIds = CustomPosterUrlResolver.extractIds("tmdb:1396")
        assertEquals(
            "https://api.aioratings.com/KEY/tmdb/poster-default/series-1396.jpg?fallback=true",
            CustomPosterUrlResolver.resolve(pattern, tmdbIds, "series")
        )
    }

    // -- Shape --

    @Test
    fun resolve_with_shape_placeholder_for_landscape() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}&shape={shape}",
            ids, "series", shape = "landscape"
        )
        assertEquals(
            "https://example.com/poster?id=tmdb:1396&id_type=tmdb&type=series&shape=landscape",
            url
        )
    }

    @Test
    fun resolve_shape_defaults_to_poster() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&shape={shape}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?id=tt0137523&shape=poster", url)
    }

    @Test
    fun resolve_optional_shape_placeholder() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&type={type}&shape={shape?}",
            ids, "movie", shape = "square"
        )
        assertEquals("https://example.com/poster?id=tt0137523&type=movie&shape=square", url)
    }

    // -- Edge cases --

    @Test
    fun resolve_returns_null_for_blank_pattern() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        assertNull(CustomPosterUrlResolver.resolve("", ids, "movie"))
        assertNull(CustomPosterUrlResolver.resolve("   ", ids, "movie"))
    }

    @Test
    fun resolve_pattern_without_any_placeholders_returns_as_is() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/static-poster.jpg",
            ids, "movie"
        )
        assertEquals("https://example.com/static-poster.jpg", url)
    }

    @Test
    fun resolve_OpenPosterDB_backdrop_pattern_with_tmdb_type_prefix() {
        val ids = ContentIds(id = "tt0137523", imdbId = "tt0137523", tmdbId = "550")
        val url = CustomPosterUrlResolver.resolve(
            "https://opdb.example.com/key123/tmdb/backdrop-default/{type}-{tmdb_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://opdb.example.com/key123/tmdb/backdrop-default/movie-550.jpg", url)
    }
}
