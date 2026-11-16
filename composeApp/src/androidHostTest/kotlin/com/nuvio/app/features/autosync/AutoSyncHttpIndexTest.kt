package com.nuvio.app.features.autosync

import android.app.Application
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared AutoSync HTTP path end to end: container indexes over range requests, subtitle downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoSyncHttpIndexTest {
    private val server = MockWebServer()
    private val files = mutableMapOf<String, ByteArray>()
    private var supportsRanges = true

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = files[request.path?.substringBefore('?')] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
                if (!supportsRanges || range == null) {
                    return MockResponse().setBody(Buffer().write(body)).setHeader("Content-Length", body.size)
                }
                val start = range[0].toInt()
                val end = minOf(range[1].toInt(), body.size - 1)
                if (start >= body.size) return MockResponse().setResponseCode(416)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(start, end + 1)))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serve(path: String, resource: String): String {
        files[path] = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)).use { it.readBytes() }
        return server.url(path).toString()
    }

    @Test
    fun `matroska cue index loads over range requests`() = runBlocking {
        val url = serve("/movie.mkv", "autosync/video_srt.mkv")
        val timeline = EmbeddedSubtitleTimelineLoader.load(url)
        assertNotNull(timeline)
        val track = timeline.tracks.single()
        // The IETF BCP 47 element wins over the legacy ISO 639-2 one.
        assertEquals("pl", track.language)
        assertEquals(40, track.cues.size)
        assertEquals(4_000L, track.cues.first().startTimeMs)
        assertTrue(track.cues.zipWithNext().all { (a, b) -> a.startTimeMs < b.startTimeMs })
        assertTrue(server.requestCount in 1..16)
    }

    @Test
    fun `mp4 sample tables load over range requests and match the mkv timing`() = runBlocking {
        val mkv = EmbeddedSubtitleTimelineLoader.load(serve("/a.mkv", "autosync/video_srt.mkv"))
        val mp4 = EmbeddedSubtitleTimelineLoader.load(serve("/b.mp4", "autosync/video_tx3g.mp4"))
        assertNotNull(mkv)
        assertNotNull(mp4)
        assertEquals("mp4-sample-table", mp4.source)
        assertEquals(
            mkv.tracks.single().cues.map { it.startTimeMs },
            mp4.tracks.single().cues.map { it.startTimeMs },
        )
    }

    @Test
    fun `quicktime text track reports no index`() = runBlocking {
        assertNull(EmbeddedSubtitleTimelineLoader.load(serve("/c.mov", "autosync/video_tx3g.mov")))
    }

    @Test
    fun `subtitle download decodes legacy charset and truncates at the limit`() = runBlocking {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nZażółć gęślą jaźń\n"
        files["/pl.srt"] = text.toByteArray(Charset.forName("windows-1250"))
        val url = server.url("/pl.srt").toString()

        val full = AutoSyncSubtitleHttp.get(url, mapOf("Accept-Encoding" to "gzip"), 1_000_000, languageHint = "pol")
        assertEquals(200, full.status)
        assertEquals(text, full.body)

        val truncated = AutoSyncSubtitleHttp.get(url, emptyMap(), 10, languageHint = "pol")
        assertTrue(truncated.body.endsWith("\n...[truncated]"))
        assertEquals(text.take(10), truncated.body.removeSuffix("\n...[truncated]"))
    }
}
