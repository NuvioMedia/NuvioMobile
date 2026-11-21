package com.nuvio.app.features.usenet

import com.nuvio.app.features.player.playerSourceIdentityKey
import com.nuvio.app.features.streams.StreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetStreamsTest {
    @Test
    fun addonNzbStreamsAreParsedWithTheirServers() {
        val streams = StreamParser.parse(
            payload = """
                {"streams":[
                  {"name":"NZB","title":"Movie.2020.1080p","nzbUrl":"https://indexer.test/getnzb/1",
                   "servers":["nntps://user:pass@news.test:563/20"],"fileMustInclude":"Part1"},
                  {"name":"Plain","url":"https://cdn.test/movie.mkv"}
                ]}
            """.trimIndent(),
            addonName = "Addon",
            addonId = "addon:test",
        )
        assertEquals(2, streams.size)
        val nzb = streams.first()
        assertEquals("https://indexer.test/getnzb/1", nzb.nzbUrl)
        assertEquals(listOf("nntps://user:pass@news.test:563/20"), nzb.nzbServers)
        assertEquals("Part1", nzb.fileMustInclude)
        assertTrue(nzb.isUsenet)
        assertTrue(nzb.needsUsenetPreparation)
        assertTrue(nzb.hasPlayableSource)
        assertFalse(nzb.isTorrentStream)
        assertFalse(nzb.shouldOpenExternally)
        assertNull(nzb.playableDirectUrl)
        assertFalse(streams.last().isUsenet)
    }

    @Test
    fun aPreparedStreamPlaysItsEngineUrlAndKeepsItsIdentity() {
        val listed = StreamParser.parse(
            payload = """{"streams":[{"name":"NZB","nzbUrl":"https://indexer.test/getnzb/1","servers":["nntp://news.test:119/4"]}]}""",
            addonName = "Addon",
            addonId = "addon:test",
        ).single()
        val prepared = listed.copy(url = "http://127.0.0.1:40000/stream/abc/movie.mkv", nzbServers = emptyList())
        assertFalse(prepared.isUsenet)
        assertFalse(prepared.needsUsenetPreparation)
        assertEquals("http://127.0.0.1:40000/stream/abc/movie.mkv", prepared.playableDirectUrl)
        // Fallback must not try the same release again after the engine opened it.
        assertEquals(listed.playerSourceIdentityKey(), prepared.playerSourceIdentityKey())
    }

    @Test
    fun providerUrlsEncodeCredentialsAndCarryPriority() {
        val provider = UsenetProvider(
            name = "News", host = "news.test", port = 563, tls = true,
            username = "me@example.com", password = "p a:s/s", connections = 30, priority = 2,
        )
        assertEquals("nntps://me%40example.com:p%20a%3As%2Fs@news.test:563/30?priority=2", provider.serverUrl())
        assertEquals("nntp://[::1]:119/20?priority=1", UsenetProvider(host = "::1", port = 119, tls = false).serverUrl())
        assertEquals("nntps://news.test:563/20?priority=1", UsenetProvider(host = "news.test").serverUrl())
    }

    @Test
    fun onlySearchableTitlesAreSearched() {
        assertTrue(UsenetStreamSource.canSearch("movie", "tt1", null, null))
        assertTrue(UsenetStreamSource.canSearch("series", "tt1:1:2", null, null))
        assertTrue(UsenetStreamSource.canSearch("series", "tmdb:10:0:3", null, null))
        assertTrue(UsenetStreamSource.canSearch("series", "tt1", 1, 2))
        assertFalse(UsenetStreamSource.canSearch("series", "tt1", null, null))
        assertFalse(UsenetStreamSource.canSearch("series", "tt1:1:0", null, null))
        assertFalse(UsenetStreamSource.canSearch("channel", "tt1", null, null))
    }

    @Test
    fun configurationReadinessNeedsAnEnabledProviderAndIndexer() {
        val provider = UsenetProvider(name = "News", host = "news.test")
        val indexer = UsenetIndexer(name = "Indexer", apiUrl = "https://indexer.test")
        assertFalse(UsenetSourceConfiguration(enabled = true, providers = listOf(provider)).ready)
        assertFalse(UsenetSourceConfiguration(enabled = false, providers = listOf(provider), indexers = listOf(indexer)).ready)
        assertTrue(UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer)).ready)
        assertFalse(
            UsenetSourceConfiguration(enabled = true, providers = listOf(provider.copy(enabled = false)), indexers = listOf(indexer)).ready,
        )
    }

    @Test
    fun engineSettingsAreKeptWithinTheEngineRanges() {
        val sanitized = UsenetConfiguration(
            profile = "turbo", readAhead = 9_999, maxConnections = -3, maxMissingArticles = 80, maxConsecutiveMissing = 40,
            searchUserAgent = NUVIO_USER_AGENT, downloadUserAgent = "bad\nagent",
        ).sanitized()
        assertEquals(UsenetConfiguration.PROFILE_BALANCED, sanitized.profile)
        assertEquals(512, sanitized.readAhead)
        assertEquals(0, sanitized.maxConnections)
        assertEquals(50, sanitized.maxMissingArticles)
        assertEquals(10, sanitized.maxConsecutiveMissing)
        // Nuvio's own User-Agent stays automatic, so it follows app updates; an invalid one is dropped.
        assertEquals("", sanitized.searchUserAgent)
        assertEquals("", sanitized.downloadUserAgent)
    }
}
