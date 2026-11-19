package com.nuvio.app.features.usenet

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class NewznabClientTest {
    private class MemoryStorage(var value: String? = null) : NewznabStateStorage {
        var saves = 0
        override fun load() = value
        override fun save(value: String) { this.value = value; saves++ }
    }

    private val capsXml = """<caps><limits max="1"/><searching><movie-search available="yes" supportedParams="imdbid"/></searching></caps>"""
    private fun MockWebServer.page(title: String, id: Int, total: Int = 1) =
        """<rss xmlns:n="urn:newznab"><channel><n:response total="$total"/><item><title>$title</title><enclosure type="application/x-nzb" url="${url("/get?id=$id")}" length="100"/></item></channel></rss>"""
    private val emptyPage = """<rss><channel></channel></rss>"""
    private fun MockWebServer.indexer(key: String = "secret") =
        UsenetIndexer(name = "Test", apiUrl = url("/1/api?custom=1").toString(), apiKey = key)

    /** Season and episode searches run at once: each request is answered by what it asks, and kept. */
    private fun MockWebServer.answer(respond: (HttpUrl) -> MockResponse): MutableList<HttpUrl> {
        val asked = Collections.synchronizedList(mutableListOf<HttpUrl>())
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = request.requestUrl!!.also { asked += it }.let(respond)
        }
        return asked
    }

    private fun List<HttpUrl>.episodes() = map { it.queryParameter("ep") }.sortedBy { it ?: "" }

    @Test fun `caps are cached and bounded pagination retains complete endpoint parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1, total = 4)))
            server.enqueue(MockResponse().setBody(server.page("Movie.2160p", 2, total = 4)))
            val indexer = server.indexer()
            val client = NewznabClient(MemoryStorage())
            val caps = client.capabilities(indexer)
            assertEquals(caps, client.capabilities(indexer))
            val releases = client.search(indexer, UsenetSearchRequest(imdbId = "tt123"), caps, results = 2)
            assertEquals(2, releases.size)
            assertEquals(3, server.requestCount)
            assertEquals("caps", server.takeRequest().requestUrl!!.queryParameter("t"))
            val first = server.takeRequest().requestUrl!!
            assertEquals("/1/api", first.encodedPath)
            assertEquals("1", first.queryParameter("custom"))
            assertEquals("0", first.queryParameter("offset"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
        }
    }


    @Test fun `unlimited reads beyond the old bound and listing still applies its own limit`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                val offset = url.queryParameter("offset")!!.toInt()
                val limit = url.queryParameter("limit")!!.toInt()
                val items = (offset until minOf(offset + limit, 1205)).joinToString("") {
                    """<item><title>Movie.2020.${if (it == 1204) "2160p" else "1080p"}-G$it</title><link>${server.url("/get?id=$it")}</link></item>"""
                }
                MockResponse().setBody("""<rss xmlns:n="urn:newznab"><channel><n:response total="1205"/>$items</channel></rss>""")
            }
            val indexer = server.indexer().copy(fetchResultsLimit = 0)
            val config = UsenetSourceConfiguration(indexers = listOf(indexer))
            val found = NewznabClient(MemoryStorage()).search(indexer, UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(), results = config.indexerResults(indexer))
            assertEquals(1205, found.size)
            assertEquals((0..1200 step 100).toList(), asked.map { it.queryParameter("offset")!!.toInt() })
            val shown = NewznabProtocol.arrange(found, config)
            assertEquals(50, shown.size)
            assertTrue(shown.first().title.contains("2160p"))
        }
    }

    @Test fun `finite fetching uses the remaining allowance for the last page`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                val offset = url.queryParameter("offset")!!.toInt()
                val limit = url.queryParameter("limit")!!.toInt()
                val items = (offset until offset + limit).joinToString("") {
                    """<item><title>Movie.2020.1080p-G$it</title><link>${server.url("/get?id=$it")}</link></item>"""
                }
                MockResponse().setBody("""<rss xmlns:n="urn:newznab"><channel><n:response total="1000"/>$items</channel></rss>""")
            }
            val found = NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(limit = 75), results = 200)
            assertEquals(200, found.size)
            assertEquals(listOf("0", "75", "150"), asked.map { it.queryParameter("offset") })
            assertEquals(listOf("75", "75", "50"), asked.map { it.queryParameter("limit") })
        }
    }

    @Test fun `unlimited follows full pages without a reported total`() = runBlocking {
        MockWebServer().use { server ->
            server.answer { url ->
                val offset = url.queryParameter("offset")!!.toInt()
                MockResponse().setBody(if (offset >= 3) emptyPage else
                    server.page("Movie.2020.1080p-G$offset", offset).replace("<n:response total=\"1\"/>", ""))
            }
            val found = NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(limit = 1), results = 0)
            assertEquals(3, found.size)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `unlimited stops on a repeated page even when response metadata changes`() = runBlocking {
        MockWebServer().use { server ->
            server.answer { url ->
                MockResponse().setBody(server.page("Movie.2020.1080p", 1, total = 10000) +
                    "<!-- requested offset ${url.queryParameter("offset")} -->")
            }
            val found = NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(), results = 0)
            assertEquals(1, found.size)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `unlimited stops on empty and repeated unusable pages and preserves results on failure`() = runBlocking {
        for (ending in listOf(emptyPage, """<rss><channel><item><title>No download</title></item></channel></rss>""", null)) {
            MockWebServer().use { server ->
                server.answer { url ->
                    if (url.queryParameter("offset") == "0") MockResponse().setBody(server.page("Movie.1080p", 1, total = 10000))
                    else if (ending == null) MockResponse().setResponseCode(503)
                    else MockResponse().setBody(ending.replace("<channel>", "<channel><response total=\"10000\"/>"))
                }
                val found = NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"),
                    NewznabCapabilities(), results = 0)
                assertEquals(1, found.size)
                assertTrue(server.requestCount in 2..3)
            }
        }
    }

    @Test fun `changing the fetch limit refreshes the season cache`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                val offset = url.queryParameter("offset")!!.toInt()
                MockResponse().setBody(if (url.queryParameter("ep") != null) emptyPage
                    else server.page("Show.S01E02.1080p-G$offset", offset, total = 3))
            }
            val client = NewznabClient(MemoryStorage())
            val indexer = server.indexer()
            val request = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 2)
            val caps = NewznabCapabilities(limit = 1)
            assertEquals(1, client.search(indexer, request, caps, results = 1).size)
            asked.clear()
            assertEquals(3, client.search(indexer, request, caps, results = 0).size)
            assertEquals(3, asked.count { it.queryParameter("ep") == null })
            asked.clear()
            assertEquals(3, client.search(indexer, request, caps, results = 0).size)
            assertEquals(0, asked.count { it.queryParameter("ep") == null })
        }
    }

    @Test fun `unlimited fetching remains cancellable`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.answer { url ->
                MockResponse().setBody(server.page("Movie.1080p", url.queryParameter("offset")!!.toInt(), total = Int.MAX_VALUE))
            }
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    withTimeout(300) {
                        NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"),
                            NewznabCapabilities(), results = 0)
                    }
                }
            }
        }
    }

    @Test fun `API requests use the search agent independently of the download agent`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(emptyPage))
            server.enqueue(MockResponse().setBody(emptyPage))
            var settings = UsenetConfiguration()
            val client = NewznabClient(MemoryStorage()) { settings.searchAgent }
            client.capabilities(server.indexer(), live = true)
            settings = settings.copy(downloadUserAgent = "Downloader/2.0")
            val caps = client.capabilities(server.indexer(), live = true)
            settings = settings.copy(searchUserAgent = "MyClient/1.0")
            client.verify(server.indexer())
            client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt123"), caps)
            assertEquals(NUVIO_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            assertTrue(NUVIO_USER_AGENT.startsWith("Nuvio/"))
            assertEquals(NUVIO_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            assertEquals(5, server.requestCount)
            repeat(3) { assertEquals("MyClient/1.0", server.takeRequest().getHeader("User-Agent")) }
        }
    }

    @Test fun `a key check needs a newznab answer, not a login page served after public caps`() = runBlocking {
        MockWebServer().use { server ->
            val caps = """<caps><searching><search available="yes"/></searching></caps>"""
            server.enqueue(MockResponse().setBody(caps))
            server.enqueue(MockResponse().setBody("<html><body>Sign in required</body></html>"))
            assertTrue(runCatching { NewznabClient(MemoryStorage()).verify(server.indexer()) }.isFailure)
            // A search refused only for its parameters still accepted the key.
            server.enqueue(MockResponse().setBody(caps))
            server.enqueue(MockResponse().setBody("""<error code="201" description="Incorrect parameter"/>"""))
            NewznabClient(MemoryStorage()).verify(server.indexer())
            server.enqueue(MockResponse().setBody(caps))
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)))
            NewznabClient(MemoryStorage()).verify(server.indexer())
        }
    }

    @Test fun `releases reused from the season cache name the indexer that asked`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                MockResponse().setBody(if (url.queryParameter("ep") == null) server.page("Show.S01E02.1080p", 1) else emptyPage)
            }
            // Another profile's indexer, or a renamed one, with the same endpoint and key.
            val first = server.indexer().copy(id = "one", name = "Old name")
            val current = first.copy(id = "two", name = "Current name")
            val client = NewznabClient(MemoryStorage())
            val request = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 2)
            client.search(first, request, NewznabCapabilities())
            val reused = client.search(current, request, NewznabCapabilities()).single()
            // The season once, the episode each time.
            assertEquals(listOf(null, "2", "2"), asked.episodes())
            assertEquals("two", reused.indexerId)
            assertEquals("Current name", reused.indexerName)
        }
    }

    @Test fun `only a user agent the user chose is kept, and only in plain text`() {
        assertEquals(NUVIO_USER_AGENT, UsenetConfiguration(searchUserAgent = "  ").searchAgent)
        assertEquals(NUVIO_USER_AGENT, UsenetConfiguration(downloadUserAgent = "  ").downloadAgent)
        assertEquals("MyClient/1.0", UsenetConfiguration(searchUserAgent = " MyClient/1.0 ").searchAgent)
        assertEquals("Downloader/2.0", UsenetConfiguration(downloadUserAgent = " Downloader/2.0 ").downloadAgent)
        assertTrue(isValidUserAgent("MyClient/1.0 (Linux; x86_64)"))
        // A line break would inject headers; HTTP clients refuse other characters outright.
        for (agent in listOf("Nuvio\r\nX-Injected: 1", "Nüvio", "x".repeat(257))) assertFalse(agent, isValidUserAgent(agent))
    }

    @Test fun `caps survive a restart, refetch for an edited key and fall back when expired and unreachable`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            server.enqueue(MockResponse().setBody(capsXml))
            val caps = NewznabClient(storage).capabilities(server.indexer())
            assertFalse(storage.value!!.contains("secret"))
            // A new client stands in for an app restart.
            assertEquals(caps, NewznabClient(storage).capabilities(server.indexer()))
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setBody(capsXml))
            NewznabClient(storage).capabilities(server.indexer(key = "edited"))
            assertEquals(2, server.requestCount)

            val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 3600 * 1000
            NewznabIndexerStates(storage) { eightDaysAgo }.update(server.indexer()) { it.copy(capsFetchedAt = eightDaysAgo) }
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(caps, NewznabClient(storage).capabilities(server.indexer()))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `throttled indexer is skipped without requests until retry after, even across a restart`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            val client = NewznabClient(storage)
            val indexer = server.indexer()
            val caps = NewznabCapabilities()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val first = assertThrows(IndexerCooldownException::class.java) {
                runBlocking { client.search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertTrue(first.until - System.currentTimeMillis() in 100_000L..120_000L)
            assertThrows(IndexerCooldownException::class.java) {
                runBlocking { NewznabClient(storage).search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertEquals(1, server.requestCount)
            // Testing an indexer is an explicit request and ignores the cooldown.
            server.enqueue(MockResponse().setBody(capsXml))
            client.capabilities(indexer, live = true)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `every episode is searched on its own and one season search adds its packs`() = runBlocking {
        MockWebServer().use { server ->
            fun items(vararg titles: String) = titles.mapIndexed { i, title ->
                """<item><title>$title</title><enclosure type="application/x-nzb" url="${server.url("/get?id=$title$i")}" length="100"/></item>"""
            }.joinToString("", "<rss><channel>", "</channel></rss>")
            val asked = server.answer { url ->
                MockResponse().setBody(when (url.queryParameter("ep")) {
                    // The season's pages hold only some of an episode's releases, as in a long-running series.
                    null -> items("Show.S01E01.1080p", "Show.S01.2160p", "Show.S01E02.1080p", "a8f3k2b9")
                    "1" -> items("Show.S01E01.1080p", "Show.S01E01.2160p")
                    else -> items("Show.S01E02.2160p")
                })
            }
            val client = NewznabClient(MemoryStorage())
            val indexer = server.indexer()
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = n)
            val caps = NewznabCapabilities()

            // Season results need explicit numbering: an obfuscated name could be any episode.
            // A release both searches found is listed once.
            assertEquals(listOf("Show.S01E01.1080p", "Show.S01E01.2160p", "Show.S01.2160p"),
                client.search(indexer, episode(1), caps).map { it.title })
            assertEquals(listOf("Show.S01E02.2160p", "Show.S01.2160p", "Show.S01E02.1080p"),
                client.search(indexer, episode(2), caps).map { it.title })
            assertEquals(listOf(null, "1", "2"), asked.episodes())
            assertTrue(asked.all { it.queryParameter("season") == "1" })
        }
    }

    @Test fun `a failed episode search keeps what the season search found`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                if (url.queryParameter("ep") == null) MockResponse().setBody(server.page("Show.S01.1080p", 1))
                else MockResponse().setResponseCode(500)
            }
            val request = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 4)
            assertEquals(listOf("Show.S01.1080p"),
                NewznabClient(MemoryStorage()).search(server.indexer(), request, NewznabCapabilities()).map { it.title })
            assertEquals(listOf(null, "4"), asked.episodes())
        }
    }

    @Test fun `season search falls back to the episode when the indexer rejects it`() = runBlocking {
        MockWebServer().use { server ->
            val asked = server.answer { url ->
                when (val episode = url.queryParameter("ep")) {
                    null -> MockResponse().setResponseCode(400)
                    else -> MockResponse().setBody(server.page("Show.S02E0$episode.1080p", episode.toInt()))
                }
            }
            val client = NewznabClient(MemoryStorage())
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 2, episode = n)
            assertEquals(listOf("Show.S02E05.1080p"), client.search(server.indexer(), episode(5), NewznabCapabilities()).map { it.title })
            assertEquals(listOf("Show.S02E06.1080p"), client.search(server.indexer(), episode(6), NewznabCapabilities()).map { it.title })
            // The rejected season search is not retried for the next episode.
            assertEquals(listOf(null, "5", "6"), asked.episodes())
        }
    }

    @Test fun `pages follow the total the indexer reports, up to the results asked for`() = runBlocking {
        MockWebServer().use { server ->
            val reported = AtomicInteger(9)
            val held = AtomicInteger(9)
            val asked = server.answer { url ->
                val offset = url.queryParameter("offset")!!.toInt()
                // The first page is short, as an indexer sends it when it leaves an item out.
                val count = if (offset == 0) 1 else (held.get() - offset).coerceIn(0, 2)
                val items = (offset until offset + count).joinToString("") {
                    """<item><title>Movie.2020.1080p-G$it</title><enclosure type="application/x-nzb" url="${server.url("/get?id=$it")}" length="100"/></item>"""
                }
                MockResponse().setBody("""<rss xmlns:n="urn:newznab"><channel><n:response total="${reported.get()}"/>$items</channel></rss>""")
            }
            val client = NewznabClient(MemoryStorage())
            val caps = NewznabCapabilities(limit = 2)
            val movie = UsenetSearchRequest(imdbId = "tt1")
            fun offsets() = asked.map { it.queryParameter("offset")!!.toInt() }.also { asked.clear() }

            assertEquals(8, client.search(server.indexer(), movie, caps, results = 100).size)
            assertEquals(listOf(0, 2, 4, 6, 8), offsets())
            // The last request asks for one item, and an oversized answer is truncated.
            assertEquals(4, client.search(server.indexer(), movie, caps, results = 5).size)
            assertEquals(listOf(0, 2, 4), offsets())
            // A total far above what the indexer holds ends at its first empty page.
            reported.set(10_000)
            held.set(4)
            assertEquals(3, client.search(server.indexer(), movie, caps, results = 1000).size)
            assertEquals(listOf(0, 2, 4), offsets())
        }
    }

    @Test fun `status records the last failure by category until a request succeeds`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            val client = NewznabClient(storage)
            val indexer = server.indexer()
            val caps = NewznabCapabilities()
            suspend fun search() = runCatching { client.search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            assertEquals(UsenetIndexerStatus(), client.status(indexer))

            server.enqueue(MockResponse().setBody("""<error code="100" description="Incorrect user credentials"/>"""))
            search()
            assertEquals(UsenetIndexerProblem.AUTH, client.status(indexer).problem)
            server.enqueue(MockResponse().setResponseCode(403))
            search()
            assertEquals(UsenetIndexerProblem.AUTH, client.status(indexer).problem)
            server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
            search()
            assertEquals(UsenetIndexerProblem.RESPONSE, client.status(indexer).problem)

            val revision = client.statusChanges.value
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)))
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)))
            search(); val saves = storage.saves; search()
            assertNull(client.status(indexer).problem)
            assertTrue(client.statusChanges.value > revision)
            assertEquals("an unchanged outcome must not rewrite the store", saves, storage.saves)
            // The status survives a restart.
            server.enqueue(MockResponse().setBody("""<error code="101" description="Account suspended"/>"""))
            search()
            assertEquals(UsenetIndexerProblem.AUTH, NewznabClient(storage).status(indexer).problem)
        }
        val closed = MockWebServer().apply { start() }
        val gone = closed.indexer()
        closed.shutdown()
        val client = NewznabClient(MemoryStorage())
        runCatching { client.search(gone, UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities()) }
        assertEquals(UsenetIndexerProblem.UNREACHABLE, client.status(gone).problem)
    }

    @Test fun `a pause shows in the status`() = runBlocking {
        MockWebServer().use { server ->
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            runCatching { client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities()) }
            assertTrue(client.status(server.indexer()).pausedUntil > System.currentTimeMillis())
        }
    }

    @Test fun `verifying an indexer checks the API key, not only the public caps`() = runBlocking {
        MockWebServer().use { server ->
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody("""<error code="100" description="Incorrect user credentials"/>"""))
            assertThrows(NewznabAuthException::class.java) { runBlocking { client.verify(server.indexer(key = "wrong")) } }
            assertEquals("caps", server.takeRequest().requestUrl!!.queryParameter("t"))
            val search = server.takeRequest().requestUrl!!
            assertEquals("search", search.queryParameter("t"))
            assertEquals("1", search.queryParameter("limit"))
            assertEquals("wrong", search.queryParameter("apikey"))
            assertEquals(UsenetIndexerProblem.AUTH, client.status(server.indexer(key = "wrong")).problem)

            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(server.page("Latest.Release", 1)))
            client.verify(server.indexer())
            // A search refused for its parameters still accepted the key.
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody("""<error code="201" description="Incorrect parameter"/>"""))
            client.verify(server.indexer())
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun `api hits and grabs left are kept for a day, across restarts`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            val indexer = server.indexer()
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1))
                .setHeader("x-api-remaining", "42").setHeader("x-grab-remaining", "1"))
            NewznabClient(storage).search(indexer, UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities())
            // A new client stands in for an app restart.
            val status = NewznabClient(storage).status(indexer)
            assertEquals(42, status.apiHitsLeft)
            assertEquals(1, status.grabsLeft)
            assertEquals(0L, status.pausedUntil)
            assertTrue(System.currentTimeMillis() - status.leftAt < 60_000)
            assertFalse(storage.value!!.contains("secret"))

            // Daily counts from before the indexer's last reset are not shown.
            val yesterday = System.currentTimeMillis() - 25L * 3600 * 1000
            NewznabIndexerStates(storage) { yesterday }.update(indexer) { it.copy(leftAt = yesterday) }
            val stale = NewznabClient(storage).status(indexer)
            assertNull(stale.apiHitsLeft)
            assertNull(stale.grabsLeft)

            // Each report replaces the last: one with only API hits shows no grab count.
            val client = NewznabClient(storage)
            server.enqueue(MockResponse().setBody(server.page("Movie.2160p", 3)).setHeader("X-RateLimit-Daily-Remaining", "7"))
            client.search(indexer, UsenetSearchRequest(imdbId = "tt2"), NewznabCapabilities())
            assertEquals(7, client.status(indexer).apiHitsLeft)
            assertNull(client.status(indexer).grabsLeft)
        }
    }

    @Test fun `spent quota from an error document or headers pauses the indexer`() = runBlocking {
        MockWebServer().use { server ->
            val caps = NewznabCapabilities()
            server.enqueue(MockResponse().setBody("""<error code="500" description="Request limit reached"/>"""))
            val quota = assertThrows(IndexerCooldownException::class.java) {
                runBlocking { NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertTrue(quota.until - System.currentTimeMillis() > 25 * 60_000L)

            // The last allowed request still returns its results; the next one is skipped.
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)).setHeader("x-api-remaining", "0"))
            assertEquals(1, client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps).size)
            assertThrows(IndexerCooldownException::class.java) {
                runBlocking { client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `results per indexer bound the pages an episode search reads`() = runBlocking {
        for ((results, pagesEach) in listOf(100 to 1, 200 to 2)) {
            MockWebServer().use { server ->
                // Every page holds one release and reports 500 in all, so only the bound stops the reading.
                val asked = server.answer { url ->
                    val offset = url.queryParameter("offset") ?: "0"
                    MockResponse().setBody(server.page("Show.S01E01.1080p.$offset.${url.queryParameter("ep")}", offset.toInt(), total = 500))
                }
                val request = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 1)
                NewznabClient(MemoryStorage()).search(server.indexer(), request, NewznabCapabilities(), results = results)
                assertEquals("episode pages for $results", pagesEach, asked.count { it.queryParameter("ep") == "1" })
                assertEquals("season pages for $results", pagesEach, asked.count { it.queryParameter("ep") == null })
            }
        }
    }
}
