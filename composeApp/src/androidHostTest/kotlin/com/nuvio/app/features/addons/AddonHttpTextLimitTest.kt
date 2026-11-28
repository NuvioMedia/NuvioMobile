package com.nuvio.app.features.addons

import com.nuvio.app.core.network.API_RESPONSE_MAX_BYTES
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddonHttpTextLimitTest {

    @Test
    fun text_responses_above_the_limit_are_truncated_instead_of_buffered_whole() {
        val oversizedBody = "a".repeat(API_RESPONSE_MAX_BYTES + 4_096)

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(oversizedBody),
            )

            val body = runBlocking { httpGetText(server.url("/manifest.json").toString()) }

            assertTrue(body.endsWith("[truncated]"), "oversized addon responses must be cut off")
            assertTrue(
                body.length <= API_RESPONSE_MAX_BYTES + 32,
                "kept ${body.length} chars, expected at most ${API_RESPONSE_MAX_BYTES + 32}",
            )
        }
    }

    @Test
    fun responses_below_the_limit_are_returned_unchanged() {
        val manifest = """{"id":"demo.addon","name":"Demo"}"""

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(manifest),
            )

            assertEquals(manifest, runBlocking { httpGetText(server.url("/manifest.json").toString()) })
        }
    }
}
