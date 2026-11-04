package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.FlatJsonScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlatJsonScannerTest {
    private fun scan(text: String, chunk: Int = 7): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        val s = FlatJsonScanner { out += it }
        val bytes = text.encodeToByteArray()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            s.feed(bytes.copyOfRange(i, i + n), n)
            i += n
        }
        s.finish()
        return out
    }

    private val sample = """
        [ {"stream_id": 12, "name": "Café \"Noir\" ✨ 🎬", "rating": 7.5, "tmdb": null,
           "info": {"name": "nested, skipped", "x": [1, {"y": "}"}]}, "adult": false, "cover": "http:\/\/a\/b.jpg"},
          {"stream_id":"13","name":"Ünïcödé — 日本","category_id":"4"},
          5, "loose",
          {} ]
    """.trimIndent()

    private val expected = listOf(
        mapOf("stream_id" to "12", "name" to "Café \"Noir\" ✨ 🎬", "rating" to "7.5", "adult" to "false", "cover" to "http://a/b.jpg"),
        mapOf("stream_id" to "13", "name" to "Ünïcödé — 日本", "category_id" to "4"),
        emptyMap(),
    )

    @Test
    fun readsAnArrayInAnyChunkSize() {
        for (chunk in listOf(1, 2, 3, 5, 64, 100_000)) assertEquals(expected, scan(sample, chunk), "chunk $chunk")
    }

    @Test
    fun readsAnObjectOfObjects() {
        val text = """{"1": {"series_id": "1", "name": "A"}, "2": {"series_id": 2, "name": "B\nC"}}"""
        assertEquals(listOf(mapOf("series_id" to "1", "name" to "A"), mapOf("series_id" to "2", "name" to "B\nC")), scan(text, 3))
    }

    @Test
    fun emptyListAndNonListGiveNothing() {
        assertEquals(emptyList(), scan("[]"))
        assertEquals(emptyList(), scan("\"nope\""))
    }

    @Test
    fun aListCutOffPartWayFails() {
        assertFailsWith<IllegalStateException> { scan("""[{"stream_id": 1, "name": "A"}, {"stream_id": 2, "na""") }
    }
}
