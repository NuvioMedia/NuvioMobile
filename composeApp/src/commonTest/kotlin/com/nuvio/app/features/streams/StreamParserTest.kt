package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamParserTest {

    @Test
    fun `parse keeps bingeGroup and explicit notWebReady`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "name": "1080p",
                      "behaviorHints": {
                        "bingeGroup": "addon-1080p",
                        "notWebReady": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertEquals("addon-1080p", stream.behaviorHints.bingeGroup)
        assertTrue(stream.behaviorHints.notWebReady)
    }

    @Test
    fun `parse forces notWebReady when proxyHeaders are provided`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.m3u8",
                      "name": "Proxy stream",
                      "behaviorHints": {
                        "proxyHeaders": {
                          "request": {
                            "User-Agent": "Stremio"
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertTrue(stream.behaviorHints.notWebReady)
        val requestHeaders = stream.behaviorHints.proxyHeaders?.request
        assertNotNull(requestHeaders)
        assertEquals("Stremio", requestHeaders["User-Agent"])
    }

    @Test
    fun `parse keeps notWebReady false when no proxyHeaders and no hint`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "name": "Direct"
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertFalse(stream.behaviorHints.notWebReady)
    }

    @Test
    fun `parse keeps proxy response headers`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "behaviorHints": {
                        "proxyHeaders": {
                          "response": {
                            "content-type": "video/mp4",
                            "x-test": "ok"
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val responseHeaders = streams.single().behaviorHints.proxyHeaders?.response
        assertNotNull(responseHeaders)
        assertEquals("video/mp4", responseHeaders["content-type"])
        assertEquals("ok", responseHeaders["x-test"])
    }

    @Test
    fun `parse keeps client resolve metadata without direct URL`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "name": "Instant",
                      "clientResolve": {
                        "type": "debrid",
                        "infoHash": "abc123",
                        "fileIdx": 4,
                        "sources": ["udp://tracker.example"],
                        "torrentName": "Movie Pack",
                        "filename": "Movie.2024.2160p.mkv",
                        "service": "torbox",
                        "isCached": true,
                        "stream": {
                          "raw": {
                            "size": 1610612736,
                            "indexer": "Indexer",
                            "parsed": {
                              "parsed_title": "Movie",
                              "year": 2024,
                              "resolution": "2160p",
                              "hdr": ["DV"],
                              "audio": ["Atmos"],
                              "episodes": [1, 2],
                              "bit_depth": "10bit"
                            }
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Debrid Fixture",
            addonId = "debrid:torbox",
        )

        val stream = streams.single()
        assertTrue(stream.isDirectDebridStream)
        assertFalse(stream.isTorrentStream)
        assertEquals("abc123", stream.clientResolve?.infoHash)
        assertEquals(4, stream.clientResolve?.fileIdx)
        assertEquals("udp://tracker.example", stream.clientResolve?.sources?.single())
        assertEquals("2160p", stream.clientResolve?.stream?.raw?.parsed?.resolution)
        assertEquals(listOf(1, 2), stream.clientResolve?.stream?.raw?.parsed?.episodes)
    }

    @Test
    fun `parse keeps addon-declared streamType`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://cdn.example.com/playlist?token=abc",
                      "name": "1080p",
                      "type": "hls"
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        assertEquals("hls", streams.single().streamType)
    }

    @Test
    fun `parse normalizes streamType casing and whitespace`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://cdn.example.com/playlist?token=abc",
                      "name": "1080p",
                      "type": " HLS "
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        assertEquals("hls", streams.single().streamType)
    }

    @Test
    fun `normalizeStreamType trims lowercases and blanks to null`() {
        assertEquals("hls", normalizeStreamType(" Hls "))
        assertEquals("dash", normalizeStreamType("DASH"))
        assertEquals(null, normalizeStreamType("   "))
        assertEquals(null, normalizeStreamType(""))
        assertEquals(null, normalizeStreamType(null))
    }

    @Test
    fun `parse leaves streamType null when addon omits it`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "name": "1080p"
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        assertEquals(null, streams.single().streamType)
    }

    @Test
    fun `parse keeps a stream whose only source is ytId`() {
        val stream = StreamParser.parse(
            payload = """{"streams":[{"ytId":"dQw4w9WgXcQ","name":"YouTube"}]}""",
            addonName = "Addon",
            addonId = "addon.id",
        ).single()

        assertEquals("dQw4w9WgXcQ", stream.ytId)
        assertEquals("dQw4w9WgXcQ", stream.youTubeIdToResolve)
        assertEquals("YouTube", stream.name)
        assertEquals(null, stream.url)
        assertTrue(stream.isSelectableForPlayback(debridEnabled = false))
    }

    @Test
    fun `parse trims ytId and drops a blank one`() {
        val streams = StreamParser.parse(
            payload = """{"streams":[{"ytId":"  dQw4w9WgXcQ "},{"ytId":"   "},{"ytId":""}]}""",
            addonName = "Addon",
            addonId = "addon.id",
        )

        assertEquals(listOf("dQw4w9WgXcQ"), streams.map { it.ytId })
    }

    @Test
    fun `parse keeps ytId alongside a url without resolving it`() {
        val stream = StreamParser.parse(
            payload = """{"streams":[{"url":"https://example.com/video.mp4","ytId":"dQw4w9WgXcQ"}]}""",
            addonName = "Addon",
            addonId = "addon.id",
        ).single()

        assertEquals("dQw4w9WgXcQ", stream.ytId)
        assertEquals(null, stream.youTubeIdToResolve)
        assertEquals("https://example.com/video.mp4", stream.playableDirectUrl)
    }
}
