package com.nuvio.app.features.details

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonPosterParsingTest {
    @Test
    fun `null specials placeholder does not shift regular season posters`() {
        assertEquals(
            mapOf(1 to "season-1.jpg", 2 to "season-2.jpg", 3 to "season-3.jpg"),
            parsePosters(listOf(null, "season-1.jpg", "season-2.jpg", "season-3.jpg"), listOf(1, 2, 3)),
        )
    }

    @Test
    fun `null regular season poster keeps its slot after specials placeholder`() {
        assertEquals(
            mapOf(1 to "season-1.jpg", 3 to "season-3.jpg"),
            parsePosters(listOf(null, "season-1.jpg", null, "season-3.jpg"), listOf(1, 2, 3)),
        )
    }

    @Test
    fun `null first season poster stays missing when counts match`() {
        assertEquals(
            mapOf(2 to "season-2.jpg", 3 to "season-3.jpg"),
            parsePosters(listOf(null, "season-2.jpg", "season-3.jpg"), listOf(1, 2, 3)),
        )
    }

    @Test
    fun `null first poster does not imply specials without episode metadata`() {
        assertEquals(
            mapOf(2 to "season-2.jpg"),
            parsePosters(listOf(null, "season-2.jpg"), emptyList()),
        )
    }

    @Test
    fun `matching posters follow nonconsecutive episode seasons`() {
        assertEquals(
            mapOf(1 to "season-1.jpg", 3 to "season-3.jpg"),
            parsePosters(listOf("season-1.jpg", "season-3.jpg"), listOf(3, 1, 3)),
        )
    }

    @Test
    fun `extra poster without a null placeholder keeps one based fallback`() {
        assertEquals(
            mapOf(1 to "season-1.jpg", 2 to "season-2.jpg", 3 to "season-3.jpg"),
            parsePosters(listOf("season-1.jpg", "season-2.jpg", "season-3.jpg"), listOf(1, 2)),
        )
    }

    private fun parsePosters(posters: List<String?>, seasons: List<Int>): Map<Int, String> {
        val posterArray = JsonArray(posters.map { it?.let(::JsonPrimitive) ?: JsonNull })
        val videos = seasons.mapIndexed { index, season ->
            """{"id":"show:$season:$index","title":"Episode","season":$season,"episode":$index}"""
        }.joinToString(",")
        return MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": { "seasonPosters": $posterArray },
                "videos": [$videos]
              }
            }
            """.trimIndent(),
        ).seasonPosters
    }
}
