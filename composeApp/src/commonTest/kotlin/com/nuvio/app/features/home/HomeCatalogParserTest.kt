package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogParserTest {

    @Test
    fun `parse catalog response de-duplicates repeated metas but preserves raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                { "id": "mal:62516", "type": "series", "name": "A" },
                { "id": "mal:62516", "type": "series", "name": "A duplicate" },
                { "id": "mal:1", "type": "movie", "name": "B" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, result.rawItemCount)
        assertEquals(
            listOf("series:mal:62516", "movie:mal:1"),
            result.items.map { it.stableKey() },
        )
        assertEquals("A", result.items.first().name)
    }

    @Test
    fun `parse catalog response respects max item cap without losing raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "One" },
                    { "id": "tt1", "type": "movie", "name": "One duplicate" },
                    { "id": "tt2", "type": "movie", "name": "Two" },
                    { "id": "tt3", "type": "movie", "name": "Three" }
                  ]
                }
            """.trimIndent(),
            maxItems = 2,
        )

        assertEquals(4, result.rawItemCount)
        assertEquals(
            listOf("movie:tt1", "movie:tt2"),
            result.items.map { it.stableKey() },
        )
    }

    @Test
    fun `parse catalog response keeps raw released date for unreleased filtering`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "Future Movie",
                      "releaseInfo": "2027",
                      "released": "2027-05-12T00:00:00.000Z"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("2027", result.items.single().releaseInfo)
        assertEquals("2027-05-12T00:00:00.000Z", result.items.single().rawReleaseDate)
    }

    @Test
    fun `parse catalog response reads addon landscape poster`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "One",
                      "background": "https://img/background.jpg",
                      "landscapePoster": "https://img/landscape-1.jpg"
                    },
                    {
                      "id": "tt2",
                      "type": "movie",
                      "name": "Two",
                      "landscapePoster": "https://img/landscape-2.jpg"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val (withBackground, withoutBackground) = result.items
        assertEquals("https://img/landscape-1.jpg", withBackground.landscapePoster)
        assertEquals("https://img/background.jpg", withBackground.banner)
        assertEquals("https://img/landscape-2.jpg", withoutBackground.landscapePoster)
        assertEquals("https://img/landscape-2.jpg", withoutBackground.banner)
    }
}
