package com.nuvio.app.features.plugins.runtime

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BinaryFetchTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun rawRequestAndResponseBodiesPreserveExactBytes(): Unit = runBlocking {
        val requestBytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        val responseBytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        server.enqueue(MockResponse().setBody(Buffer().write(responseBytes)))

        val response = httpRequestRaw(
            method = "POST",
            url = server.url("/binary").toString(),
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = "this text must not be sent",
            bodyBytes = requestBytes,
        )

        assertContentEquals(requestBytes, server.takeRequest().body.readByteArray())
        assertContentEquals(responseBytes, response.bodyBytes)
    }
}
