package com.nuvio.app.features.details

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaDetailsParserTest {

    @Test
    fun `parse rejects null meta object without json object cast crash`() {
        assertFailsWith<IllegalStateException> {
            MetaDetailsParser.parse("""{"meta":null}""")
        }
    }

    @Test
    fun `parse accepts bare meta object response`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "id": "mal:62516",
              "type": "series",
              "name": "The Fragrant Flower Blooms with Dignity"
            }
            """.trimIndent(),
        )

        assertEquals("mal:62516", result.id)
        assertEquals("series", result.type)
        assertEquals("The Fragrant Flower Blooms with Dignity", result.name)
    }

    @Test
    fun `parse preserves explicit video availability`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "mal:52991",
                "type": "series",
                "name": "Show",
                "videos": [
                  {
                    "id": "show:3:1",
                    "title": "Episode 1",
                    "season": 3,
                    "episode": 1,
                    "released": null,
                    "available": false
                  },
                  {
                    "id": "show:1:1",
                    "title": "Episode 1",
                    "season": 1,
                    "episode": 1
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertFalse(result.videos[0].available)
        assertTrue(result.videos[1].available)
    }

    @Test
    fun `parse converts addon episode runtimes to minutes`() {
        val runtimes = listOf(
            JsonPrimitive(45) to 45,
            JsonPrimitive("45") to 45,
            JsonPrimitive(" 45 min ") to 45,
            JsonPrimitive("45 minutes") to 45,
            JsonPrimitive("2h 5m") to 125,
            JsonPrimitive("1 hr 30 min") to 90,
            JsonPrimitive("1 hour 30 minutes") to 90,
            JsonPrimitive("2 HOURS") to 120,
            JsonPrimitive("1:30") to 90,
        )

        runtimes.forEach { (runtime, expected) ->
            assertEquals(expected, parseEpisode(runtime).runtime, "Runtime: $runtime")
        }
    }

    @Test
    fun `parse keeps episodes with missing or invalid runtimes`() {
        val runtimes = listOf(
            null,
            JsonNull,
            JsonPrimitive(""),
            JsonPrimitive(" "),
            JsonPrimitive("unknown"),
            JsonPrimitive(true),
            JsonObject(emptyMap()),
            JsonArray(emptyList()),
        )

        runtimes.forEach { runtime ->
            val video = parseEpisode(runtime)

            assertEquals("show:1:1", video.id)
            assertNull(video.runtime, "Runtime: $runtime")
        }
    }

    @Test
    fun `parse reads defaultVideoId from behavior hints`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "behaviorHints": {
                  "defaultVideoId": "show:1:2"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("show:1:2", result.defaultVideoId)
    }

    @Test
    fun `parse reads AIOMetadata season posters from app extras`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "seasonPosters": [
                    "https://example.com/season-1.jpg",
                    null,
                    "https://example.com/season-3.jpg"
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                1 to "https://example.com/season-1.jpg",
                3 to "https://example.com/season-3.jpg",
            ),
            result.seasonPosters,
        )
    }

    @Test
    fun `parse maps AIOMetadata specials poster to season zero`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "seasonPosters": [
                    "https://example.com/specials.jpg",
                    "https://example.com/season-1.jpg"
                  ]
                },
                "videos": [
                  {
                    "id": "show:0:1",
                    "title": "Special 1",
                    "season": 0,
                    "episode": 1
                  },
                  {
                    "id": "show:1:1",
                    "title": "Episode 1",
                    "season": 1,
                    "episode": 1
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                0 to "https://example.com/specials.jpg",
                1 to "https://example.com/season-1.jpg",
            ),
            result.seasonPosters,
        )
    }

    @Test
    fun `parse keeps season one mapping when specials poster is omitted`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "seasonPosters": [
                    "https://example.com/season-1.jpg"
                  ]
                },
                "videos": [
                  {
                    "id": "show:0:1",
                    "title": "Special 1",
                    "season": 0,
                    "episode": 1
                  },
                  {
                    "id": "show:1:1",
                    "title": "Episode 1",
                    "season": 1,
                    "episode": 1
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf(1 to "https://example.com/season-1.jpg"),
            result.seasonPosters,
        )
    }

    @Test
    fun `parse reads localized AIOMetadata certification`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "certificationLocal": " 16 ",
                  "certification": "TV-MA"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("16", result.ageRating)
    }

    @Test
    fun `parse falls back to AIOMetadata default certification`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "movie",
                "type": "movie",
                "name": "Movie",
                "app_extras": {
                  "certificationLocal": " ",
                  "certification": "PG-13"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("PG-13", result.ageRating)
    }

    private fun parseEpisode(runtime: JsonElement?): MetaVideo {
        val payload = buildJsonObject {
            put("id", "show")
            put("type", "series")
            put("name", "Show")
            put("videos", buildJsonArray {
                add(buildJsonObject {
                    put("id", "show:1:1")
                    put("title", "Episode 1")
                    if (runtime != null) put("runtime", runtime)
                })
            })
        }
        return MetaDetailsParser.parse(payload.toString()).videos.single()
    }
}
