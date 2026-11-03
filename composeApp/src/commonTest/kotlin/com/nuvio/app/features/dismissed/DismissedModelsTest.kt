package com.nuvio.app.features.dismissed

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DismissedModelsTest {

    @Test
    fun dismissedItemKey_formatsCorrectly() {
        assertEquals("movie:tt0816692", dismissedItemKey("movie", "tt0816692"))
        assertEquals("series:tt1396", dismissedItemKey("Series", " tt1396 "))
    }

    @Test
    fun dismissedItemKeys_includesAliasesForMovie() {
        val keys = dismissedItemKeys("movie", "tt0816692")
        assertTrue(keys.contains("movie:tt0816692"))
        assertTrue(keys.contains("film:tt0816692"))
    }

    @Test
    fun dismissedItemKeys_includesAliasesForSeries() {
        val keys = dismissedItemKeys("series", "tt1396")
        assertTrue(keys.contains("series:tt1396"))
        assertTrue(keys.contains("show:tt1396"))
        assertTrue(keys.contains("tv:tt1396"))
        assertTrue(keys.contains("tvshow:tt1396"))
        assertTrue(keys.contains("anime:tt1396"))
    }

    @Test
    fun toDismissedItem_mapsMetaPreview() {
        val preview = MetaPreview(
            id = "tt0816692",
            type = "movie",
            name = "Interstellar",
            poster = "https://image.tmdb.org/poster.jpg",
            posterShape = PosterShape.Poster,
            releaseInfo = "2014",
        )
        val item = preview.toDismissedItem(dismissedAtEpochMs = 1700000000000L)
        assertEquals("tt0816692", item.id)
        assertEquals("movie", item.type)
        assertEquals("Interstellar", item.name)
        assertEquals("https://image.tmdb.org/poster.jpg", item.poster)
        assertEquals("2014", item.releaseInfo)
        assertEquals(1700000000000L, item.dismissedAtEpochMs)
    }
}
