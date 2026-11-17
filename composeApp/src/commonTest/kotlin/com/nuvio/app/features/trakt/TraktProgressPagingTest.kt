package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraktProgressPagingTest {

    @Test
    fun `reads every page until a short one`() = runTest {
        val pages = mapOf(1 to "a,b", 2 to "c,d", 3 to "e")

        val items = readAllTraktPages(
            pageLimit = 2,
            fetchPage = { page, _ -> pages.getValue(page) },
            decodePage = ::decodeList,
        )

        assertEquals(listOf("a", "b", "c", "d", "e"), items)
    }

    @Test
    fun `a failed page drops the whole list`() = runTest {
        val items = readAllTraktPages(
            pageLimit = 2,
            fetchPage = { page, _ -> if (page == 1) "a,b" else error("HTTP 503") },
            decodePage = ::decodeList,
        )

        assertNull(items)
    }

    @Test
    fun `an undecodable page drops the whole list`() = runTest {
        val items = readAllTraktPages(
            pageLimit = 2,
            fetchPage = { _, _ -> "not json" },
            decodePage = { payload -> if (payload == "not json") error("bad payload") else decodeList(payload) },
        )

        assertNull(items)
    }

    @Test
    fun `keeps the newest episode per show from the history`() = runTest {
        val history = collectRecentTraktEpisodeHistory(
            maxPages = 5,
            cutoffMs = null,
            fetchPage = { response(body = "show-a:3,show-b:2,show-a:1") },
            decodePage = ::decodeList,
            mapItem = ::historyEntry,
        )

        assertEquals(listOf("show-a:3", "show-b:2"), history?.map { it.videoId })
    }

    @Test
    fun `an error status on a later history page drops the whole history`() = runTest {
        // A full first page (every item from one show) makes the walk ask for page 2.
        val fullPage = List(1000) { index -> "show-a:${1000 - index}" }.joinToString(",")

        val history = collectRecentTraktEpisodeHistory(
            maxPages = 5,
            cutoffMs = null,
            fetchPage = { page -> if (page == 1) response(body = fullPage) else response(status = 502, body = "") },
            decodePage = ::decodeList,
            mapItem = ::historyEntry,
        )

        assertNull(history)
    }

    @Test
    fun `an error status or undecodable first history page drops the history`() = runTest {
        val failed = collectRecentTraktEpisodeHistory(
            maxPages = 5,
            cutoffMs = null,
            fetchPage = { response(status = 429, body = "") },
            decodePage = ::decodeList,
            mapItem = ::historyEntry,
        )
        val undecodable = collectRecentTraktEpisodeHistory(
            maxPages = 5,
            cutoffMs = null,
            fetchPage = { response(body = "not json") },
            decodePage = { body -> if (body == "not json") error("bad payload") else decodeList(body) },
            mapItem = ::historyEntry,
        )

        assertNull(failed)
        assertNull(undecodable)
    }

    @Test
    fun `an empty history is a valid result`() = runTest {
        val history = collectRecentTraktEpisodeHistory(
            maxPages = 5,
            cutoffMs = null,
            fetchPage = { response(body = "") },
            decodePage = ::decodeList,
            mapItem = ::historyEntry,
        )

        assertEquals(emptyList(), history)
    }

    private fun decodeList(payload: String): List<String> =
        payload.split(",").filter { it.isNotBlank() }

    private fun response(status: Int = 200, body: String): RawHttpResponse = RawHttpResponse(
        status = status,
        statusText = "",
        url = "https://api.trakt.tv/sync/history/episodes",
        body = body,
        headers = emptyMap(),
    )

    private fun historyEntry(item: String, fallbackIndex: Int): WatchProgressEntry {
        val show = item.substringBefore(":")
        val episode = item.substringAfter(":").toInt()
        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = show,
            parentMetaType = "series",
            videoId = item,
            title = "Show",
            seasonNumber = 1,
            episodeNumber = episode,
            lastPositionMs = 100L,
            durationMs = 1_000L,
            lastUpdatedEpochMs = 1_000_000L - fallbackIndex,
        )
    }
}
