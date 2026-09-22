package com.nuvio.app.core.poster

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomPosterOverlayTest {

    private val rpdbPattern = "https://api.ratingposterdb.com/key/imdb/poster-default/{imdb_id}.jpg"
    private val universalPattern = "https://example.com/{id_type}/poster-default/{typed_id}.jpg"
    private val shapePattern = "https://example.com/{id_type}/{shape}/{typed_id}.jpg"

    // -- MetaPreview overlay --

    @Test
    fun withCustomPosterUrl_replaces_poster_and_preserves_rawPosterUrl() {
        val item = MetaPreview(
            id = "tt0137523",
            type = "movie",
            name = "Fight Club",
            poster = "https://original.com/poster.jpg",
        )
        val result = item.withCustomPosterUrl(rpdbPattern)
        assertEquals("https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg", result.poster)
        assertEquals("https://original.com/poster.jpg", result.rawPosterUrl)
    }

    @Test
    fun withCustomPosterUrl_blank_pattern_returns_unchanged() {
        val item = MetaPreview(id = "tt0137523", type = "movie", name = "Test", poster = "https://original.com/poster.jpg")
        val result = item.withCustomPosterUrl("")
        assertEquals("https://original.com/poster.jpg", result.poster)
        assertNull(result.rawPosterUrl)
    }

    @Test
    fun withCustomPosterUrl_unresolvable_id_returns_unchanged() {
        val item = MetaPreview(id = "kitsu:7442", type = "series", name = "Anime", poster = "https://original.com/poster.jpg")
        val result = item.withCustomPosterUrl(rpdbPattern)
        assertEquals("https://original.com/poster.jpg", result.poster)
        assertNull(result.rawPosterUrl)
    }

    @Test
    fun withCustomPosterUrl_shape_pattern_sets_landscapePoster() {
        val item = MetaPreview(id = "tt0137523", type = "movie", name = "Test", poster = "https://original.com/poster.jpg")
        val result = item.withCustomPosterUrl(shapePattern)
        assertEquals("https://example.com/imdb/poster/tt0137523.jpg", result.poster)
        assertEquals("https://example.com/imdb/landscape/tt0137523.jpg", result.landscapePoster)
        assertEquals("https://original.com/poster.jpg", result.rawPosterUrl)
    }

    @Test
    fun withCustomPosterUrl_non_poster_shape_without_shape_placeholder_returns_unchanged() {
        val item = MetaPreview(
            id = "tt0137523", type = "movie", name = "Test",
            poster = "https://original.com/poster.jpg",
            posterShape = PosterShape.Landscape,
        )
        val result = item.withCustomPosterUrl(rpdbPattern)
        assertEquals("https://original.com/poster.jpg", result.poster)
    }

    @Test
    fun withCustomPosterUrl_non_poster_shape_with_shape_placeholder_resolves() {
        val item = MetaPreview(
            id = "tt0137523", type = "movie", name = "Test",
            poster = "https://original.com/poster.jpg",
            posterShape = PosterShape.Landscape,
        )
        val result = item.withCustomPosterUrl(shapePattern)
        assertEquals("https://example.com/imdb/landscape/tt0137523.jpg", result.poster)
    }

    // -- reapplyCustomPosterUrl --

    @Test
    fun reapplyCustomPosterUrl_restores_original_then_applies_new_pattern() {
        val item = MetaPreview(
            id = "tt0137523", type = "movie", name = "Test",
            poster = "https://old-service.com/custom.jpg",
            rawPosterUrl = "https://original.com/poster.jpg",
        )
        val result = item.reapplyCustomPosterUrl(universalPattern)
        assertEquals("https://example.com/imdb/poster-default/tt0137523.jpg", result.poster)
        assertEquals("https://original.com/poster.jpg", result.rawPosterUrl)
    }

    @Test
    fun reapplyCustomPosterUrl_blank_pattern_restores_original() {
        val item = MetaPreview(
            id = "tt0137523", type = "movie", name = "Test",
            poster = "https://custom-service.com/custom.jpg",
            rawPosterUrl = "https://original.com/poster.jpg",
        )
        val result = item.reapplyCustomPosterUrl("")
        assertEquals("https://original.com/poster.jpg", result.poster)
    }

    @Test
    fun reapplyCustomPosterUrl_no_rawPosterUrl_applies_normally() {
        val item = MetaPreview(
            id = "tt0137523", type = "movie", name = "Test",
            poster = "https://original.com/poster.jpg",
        )
        val result = item.reapplyCustomPosterUrl(universalPattern)
        assertEquals("https://example.com/imdb/poster-default/tt0137523.jpg", result.poster)
        assertEquals("https://original.com/poster.jpg", result.rawPosterUrl)
    }

    // -- List overlay --

    @Test
    fun withCustomPosterUrls_applies_to_all_items() {
        val items = listOf(
            MetaPreview(id = "tt0137523", type = "movie", name = "Fight Club", poster = "https://a.com/1.jpg"),
            MetaPreview(id = "tt0903747", type = "series", name = "Breaking Bad", poster = "https://a.com/2.jpg"),
        )
        val result = items.withCustomPosterUrls(rpdbPattern)
        assertEquals("https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg", result[0].poster)
        assertEquals("https://api.ratingposterdb.com/key/imdb/poster-default/tt0903747.jpg", result[1].poster)
        assertEquals("https://a.com/1.jpg", result[0].rawPosterUrl)
        assertEquals("https://a.com/2.jpg", result[1].rawPosterUrl)
    }

    @Test
    fun withCustomPosterUrls_blank_pattern_returns_same_list() {
        val items = listOf(
            MetaPreview(id = "tt0137523", type = "movie", name = "Test", poster = "https://a.com/1.jpg"),
        )
        val result = items.withCustomPosterUrls("")
        assertEquals(items, result)
    }
}
