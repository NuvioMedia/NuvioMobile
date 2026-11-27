package com.nuvio.app.features.streams

import com.nuvio.app.features.player.clearKeyLavfOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamClearKeysTest {

    private val kid = "00112233445566778899aabbccddeeff"
    private val key = "ffeeddccbbaa99887766554433221100"

    @Test
    fun `addon streams accept the object, array and string shapes`() {
        val streams = StreamParser.parse(
            payload = """
                {"streams": [
                  {"url": "https://a/1.mpd", "behaviorHints": {"clearKeys": {"${kid.uppercase()}": "$key"}}},
                  {"url": "https://a/2.mpd", "behaviorHints": {"clearKeys": [
                    {"kid": "ABEiM0RVZneImaq7zN3u_w", "k": "_-7dzLuqmYh3ZlVEMyIRAA"},
                    {"kid": "broken", "k": "$key"}
                  ]}},
                  {"url": "https://a/3.mpd", "behaviorHints": {"clearKeys": "00112233-4455-6677-8899-aabbccddeeff:/+7dzLuqmYh3ZlVEMyIRAA=="}},
                  {"url": "https://a/4.mp4"}
                ]}
            """.trimIndent(),
            addonName = "Addon",
            addonId = "addon",
        )
        assertEquals(listOf("$kid:$key", "$kid:$key", "$kid:$key", null), streams.map { it.behaviorHints.clearKeys })
    }

    @Test
    fun `invalid or duplicate pairs are dropped`() {
        assertEquals("$kid:$key", JsonPrimitive(" $kid:$key , $kid:$key;nokey ").toStreamClearKeys())
        assertNull(Json.parseToJsonElement("""{"$kid": "0011"}""").toStreamClearKeys())
        assertNull(JsonPrimitive(42).toStreamClearKeys())
        assertNull(null.toStreamClearKeys())
    }

    @Test
    fun `libmpv gets the pairs as an FFmpeg dictionary in mpv length quoting`() {
        val dict = "$kid=$key:${key}=$kid"
        val segment = "decryption_keys=$kid\\=$key\\:$key\\=$kid"
        assertEquals(
            "cenc_decryption_keys=%${dict.length}%$dict,decryption_keys=%${dict.length}%$dict," +
                "seg_format_options=%${segment.length}%$segment",
            clearKeyLavfOptions("$kid:$key,$key:$kid"),
        )
    }

    @Test
    fun `a single pair is also given as the one-key options older FFmpeg builds read`() {
        val options = clearKeyLavfOptions("$kid:$key")
        assertEquals(true, options.endsWith(",cenc_decryption_key=$key,decryption_key=$key"))
        assertEquals(false, clearKeyLavfOptions("$kid:$key,$key:$kid").contains("decryption_key="))
    }
}
