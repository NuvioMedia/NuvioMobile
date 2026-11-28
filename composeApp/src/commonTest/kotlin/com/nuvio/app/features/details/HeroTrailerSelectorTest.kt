package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroTrailerSelectorTest {

    @Test
    fun `selects official non-season YouTube trailer first`() {
        val trailers = listOf(
            trailer(id = "teaser", type = "Teaser", official = true, publishedAt = "2026-02-01"),
            trailer(id = "season", type = "Trailer", official = true, seasonNumber = 2, publishedAt = "2026-03-01"),
            trailer(id = "official", type = "Trailer", official = true, publishedAt = "2026-01-01"),
        )

        assertEquals("official", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `falls back to addon-style YouTube trailer when official flag is absent`() {
        val trailers = listOf(
            trailer(id = "clip", type = "Clip", official = false, publishedAt = "2026-02-01"),
            trailer(id = "addon", type = "Trailer", official = false, publishedAt = "2026-01-01"),
        )

        assertEquals("addon", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `uses season trailer when only season trailers are available`() {
        val trailers = listOf(
            trailer(id = "season-one", type = "Trailer", official = false, seasonNumber = 1),
            trailer(id = "season-two", type = "Trailer", official = true, seasonNumber = 2),
        )

        assertEquals("season-two", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `filters out non YouTube trailers`() {
        val trailers = listOf(
            trailer(id = "vimeo", site = "Vimeo", type = "Trailer", official = true),
            trailer(id = "youtube", site = "YouTube", type = "Teaser", official = false),
        )

        assertEquals("youtube", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `prefers a direct url trailer over youtube trailers`() {
        val trailers = listOf(
            trailer(id = "official", type = "Trailer", official = true),
            trailer(id = "direct", site = "Direct", url = "https://cdn.example/trailer.mp4"),
        )

        assertEquals("direct", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `prefers a tmdb trailer over a direct url trailer`() {
        val trailers = listOf(
            trailer(id = "direct", site = "Direct", url = "https://cdn.example/trailer.mp4"),
            trailer(id = "tmdb", type = "Teaser", fromTmdb = true),
        )

        assertEquals("tmdb", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `returns null for empty or unsupported trailer lists`() {
        assertNull(selectHeroTrailer(emptyList()))
        assertNull(selectHeroTrailer(listOf(trailer(id = "blank", key = ""))))
        assertNull(selectHeroTrailer(listOf(trailer(id = "other", site = "Vimeo"))))
    }

    private fun trailer(
        id: String,
        key: String = id,
        site: String = "YouTube",
        type: String = "Trailer",
        official: Boolean = false,
        publishedAt: String? = null,
        seasonNumber: Int? = null,
        url: String? = null,
        fromTmdb: Boolean = false,
    ): MetaTrailer =
        MetaTrailer(
            id = id,
            key = key,
            name = id,
            site = site,
            type = type,
            official = official,
            publishedAt = publishedAt,
            seasonNumber = seasonNumber,
            url = url,
            fromTmdb = fromTmdb,
        )
}
