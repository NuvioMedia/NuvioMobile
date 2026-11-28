package com.nuvio.app.features.usenet

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB answers for a search; each test sets what it needs. */
private class FakeMetadata(
    /** A title's metadata, given a series' details. */
    var title: suspend (UsenetSeriesDetails?) -> UsenetTitleMetadata = { error("metadata unavailable") },
    var seasons: suspend () -> Map<Int, Int>? = { null },
    /** A series' details; by default only the [seasons]' episode counts. */
    var details: (suspend () -> UsenetSeriesDetails?)? = null,
) : UsenetMetadataSource {
    var titleLookups = 0
    var seasonLookups = 0

    override suspend fun title(imdbId: String?, tmdbId: String?, videoId: String, series: Boolean,
        details: UsenetSeriesDetails?): UsenetTitleMetadata {
        titleLookups++
        return title(details).let { it.copy(imdbId = it.imdbId ?: imdbId, tmdbId = it.tmdbId ?: tmdbId) }
    }

    override suspend fun series(tmdbId: String?, videoId: String): UsenetSeriesDetails? {
        seasonLookups++
        details?.let { return it() }
        return UsenetSeriesDetails(tmdbId ?: "1", seasons = seasons().orEmpty().map { (number, count) -> UsenetSeasonSummary(number, count) })
    }
}

/** Metas and Simkl mappings for anime catalog ids, keyed "type:id" and "source:id"; records what was asked. */
private class FakeCatalog(
    val held: Map<String, MetaDetails> = emptyMap(),
    val fetched: Map<String, MetaDetails> = emptyMap(),
    val simkl: Map<String, SimklIdResolver.ResolvedIds> = emptyMap(),
    val episodes: Map<Long, List<SimklIdResolver.EpisodeMapping>> = emptyMap(),
) : UsenetCatalogSource {
    val asked = mutableListOf<String>()

    override suspend fun heldMeta(type: String, id: String) = held["$type:$id"].also { asked += "held $type:$id" }
    override suspend fun fetchedMeta(type: String, id: String) = fetched["$type:$id"].also { asked += "fetched $type:$id" }
    override suspend fun simklIds(source: String, id: String) = simkl["$source:$id"].also { asked += "simkl $source:$id" }
    override suspend fun simklEpisodes(simklId: Long, type: String) = episodes[simklId].orEmpty()
}

class BuiltInUsenetServiceTest {
    private val provider = UsenetProvider(name = "News", host = "news.test")

    private fun release(indexer: UsenetIndexer, title: String = "Movie.2020.1080p") =
        UsenetRelease(title, "${indexer.apiUrl}/nzb/$title", indexerId = indexer.id, indexerName = indexer.name)

    private fun memoryOutcomes() = UnplayableReleases(object : UnplayableReleaseStorage {
        var value: String? = null
        override fun load() = value
        override fun save(value: String) { this.value = value }
    })

    private fun List<UsenetSearchUpdate>.lastGroup(): AddonStreamGroup = mapNotNull { it.group }.last()

    @Test fun `each indexer uses its own fetch limit and the combined list keeps its display limit`() = runTest {
        val hydra = UsenetIndexer(id = "hydra", name = "Hydra", apiUrl = "https://hydra.test/api", fetchResultsLimit = 0)
        val direct = UsenetIndexer(id = "direct", name = "Direct", apiUrl = "https://direct.test/api", fetchResultsLimit = 100)
        val config = UsenetSourceConfiguration(enabled = true, maxResults = 1, providers = listOf(provider), indexers = listOf(hydra, direct))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        for (indexer in config.indexers) {
            coEvery { client.search(indexer, any(), any(), config.indexerResults(indexer)) } returns listOf(
                UsenetRelease("Show.S01E03.1080p", "https://${indexer.id}.test/nzb/1", indexerId = indexer.id))
        }
        val updates = BuiltInUsenetService(client, FakeMetadata()).search(config, "series", "tt123:1:3", null, null).toList()
        assertEquals(1, updates.lastGroup().streams.size)
        coVerify { client.search(hydra, any(), any(), 0) }
        coVerify { client.search(direct, any(), any(), 100) }
    }

    @Test fun `failed indexer does not cancel good results and episode suffixes are resolved`() = runTest {
        val good = UsenetIndexer(id = "good", name = "Good", apiUrl = "https://good.test/api")
        val bad = UsenetIndexer(id = "bad", name = "Bad", apiUrl = "https://bad.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test", username = "user", password = "pass")),
            indexers = listOf(good, bad))
        val client = mockk<NewznabClient>()
        val metadata = FakeMetadata()
        coEvery { client.capabilities(good) } returns NewznabCapabilities()
        coEvery { client.capabilities(bad) } throws IllegalStateException("secret-api-key")
        coEvery { client.search(good, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01E03.1080p", "https://good.test/nzb/1", indexerId = good.id, indexerName = good.name))
        val results = BuiltInUsenetService(client, metadata).search(config, "series", "tt123:1:3", null, null).toList()
        assertEquals(1, results.count { it.failure != null })
        assertFalse(results.mapNotNull { it.failure }.joinToString().contains("secret-api-key"))
        val group = results.lastGroup()
        assertFalse(group.isLoading)
        assertEquals(BUILT_IN_USENET_GROUP_ID, group.addonId)
        val stream = group.streams.single()
        assertTrue(stream.isUsenet)
        assertTrue(stream.needsUsenetPreparation)
        assertEquals(listOf("nntps://user:pass@news.test:563/20?priority=1"), stream.nzbServers)
        coVerify { client.search(good, match { it.season == 1 && it.episode == 3 && it.imdbId == "tt123" }, any(), any()) }
        // The series' metadata failed here (the fake has no answers): its results are listed unchecked.
    }

    @Test fun `an upload's episodes share a binge group, so the next episode keeps to it`() {
        fun group(title: String) = BuiltInUsenetService.bingeGroup(title)
        val e01 = group("Silo.S01E01.Freedom.Day.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb")
        assertNotNull(e01)
        assertEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        assertNotEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-FLUX"))
        assertNotEquals(e01, group("Silo.S02E01.The.Engineer.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        // A season pack plays every episode: it is its own group. An episode-less series release has none.
        assertEquals(group("Silo.S01.2160p.ATVp.WEB-DL-NTb"), group("silo.s01.2160p.atvp.web-dl-ntb"))
        assertNull(group("Movie.2020.1080p.WEB-DL-GRP"))
    }

    @Test fun `every indexer is searched by default and repeated releases are kept unless hidden`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(first, backup))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any(), any()) } returns listOf(release(first, "Movie.2020.1080p-GRP"))
        coEvery { client.search(backup, any(), any(), any()) } returns
            listOf(release(backup, "Movie 2020 1080p-GRP"), release(backup, "Movie.2020.720p-GRP"))
        val service = BuiltInUsenetService(client, FakeMetadata())
        suspend fun names(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams.map { it.title }
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie 2020 1080p-GRP", "Movie.2020.720p-GRP"), names(config))
        // Hidden repeats keep the higher-priority indexer's copy.
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie.2020.720p-GRP"), names(config.copy(hideDuplicates = true)))
    }

    @Test fun `a whole Blu-ray carries its label, and every result its size`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Movie.2019.COMPLETE.UHD.BLURAY-GRP", "https://a.test/nzb/1", size = 60L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Movie.2019.2160p.BluRay.REMUX.HEVC-GRP", "https://a.test/nzb/2", indexerId = "a", indexerName = "A"))
        val streams = BuiltInUsenetService(client, FakeMetadata()).search(config, "movie", "tt1", null, null).toList().lastGroup().streams
        val disc = streams.single { it.title!!.contains("COMPLETE") }
        assertEquals(listOf("UHD BLU-RAY"), disc.badges.map { it.name })
        assertTrue(disc.badges.single().isLabelBadge())
        assertEquals(60L shl 30, disc.behaviorHints.videoSize)
        val remux = streams.single { it.title!!.contains("REMUX") }
        assertTrue(remux.badges.isEmpty())
        assertNull(remux.behaviorHints.videoSize)
    }

    @Test fun `a release that failed for good goes last, labelled, and stays selectable`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.1080p-GRP"))
        val unplayable = memoryOutcomes()
        unplayable.record("https://a.test/api/nzb/Movie.2020.2160p-GRP", null, null, config.servers(), "missing-article")
        val streams = BuiltInUsenetService(client, FakeMetadata(), unplayable)
            .search(config, "movie", "tt1", null, null).toList().lastGroup().streams
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie.2020.2160p-GRP"), streams.map { it.title })
        assertEquals(listOf(USENET_BADGE_UNAVAILABLE), streams.last().badges.map { it.name })
        assertTrue(streams.last().isUsenet)
        assertTrue(streams.first().badges.isEmpty())
    }

    @Test fun `a release that played recently is listed first, labelled, when chosen`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), availableFirst = true)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.720p-GRP"))
        val outcomes = memoryOutcomes()
        outcomes.recordPlayed("https://a.test/api/nzb/Movie.2020.720p-GRP", null, null, config.servers())
        val service = BuiltInUsenetService(client, FakeMetadata(), outcomes)
        suspend fun streams(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams
        val first = streams(config)
        assertEquals(listOf("Movie.2020.720p-GRP", "Movie.2020.2160p-GRP"), first.map { it.title })
        assertEquals(listOf(USENET_BADGE_AVAILABLE), first.first().badges.map { it.name })
        val sorted = streams(config.copy(availableFirst = false))
        assertEquals(listOf("Movie.2020.2160p-GRP", "Movie.2020.720p-GRP"), sorted.map { it.title })
    }

    @Test fun `a season pack is labelled with its whole size and an episode's share`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            maxSizeGb = 5, showReleaseNames = false)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"))
        val metadata = FakeMetadata(seasons = { mapOf(1 to 9) })
        val stream = BuiltInUsenetService(client, metadata).search(config, "series", "tt1:1:2", null, null).toList()
            .lastGroup().streams.single()
        assertEquals(listOf(USENET_BADGE_SEASON_PACK), stream.badges.map { it.name })
        assertEquals("9.00 GB whole season (~1.00 GB per episode) • A • GRP", stream.description)
        assertEquals(1L shl 30, stream.behaviorHints.videoSize)
        assertEquals(1, metadata.seasonLookups)
    }

    @Test fun `a pack of several seasons shares its size among all their episodes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), showReleaseNames = false)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01-S03.1080p-GRP", "https://a.test/nzb/pack", size = 34L shl 30, indexerId = "a", indexerName = "A"))
        val metadata = FakeMetadata(seasons = { mapOf(0 to 4, 1 to 10, 2 to 12, 3 to 12) })
        val stream = BuiltInUsenetService(client, metadata).search(config, "series", "tt1:1:2", null, null).toList()
            .lastGroup().streams.single()
        assertEquals("34.00 GB whole pack (~1.00 GB per episode) • A • GRP", stream.description)
        assertEquals(1L shl 30, stream.behaviorHints.videoSize)
    }

    @Test fun `result names carry the resolution`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.720p-GRP"), release(indexer, "Movie.2020-GRP"))
        val names = BuiltInUsenetService(client, FakeMetadata()).search(config, "movie", "tt1", null, null).toList()
            .lastGroup().streams.map { it.name }
        assertEquals(listOf("4K", "720p", "Usenet"), names)
    }

    @Test fun `results wait for every indexer, briefly, unless shown progressively`() = runTest {
        val fast = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val slow = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(fast, slow))
        val client = mockk<NewznabClient>()
        var slowDelay = 2_000L
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(fast, any(), any(), any()) } returns listOf(release(fast, "Movie.2020.1080p-A"))
        coEvery { client.search(slow, any(), any(), any()) } coAnswers { delay(slowDelay); listOf(release(slow, "Movie.2020.720p-B")) }
        val service = BuiltInUsenetService(client, FakeMetadata())
        suspend fun lists(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.map { it.streams.size to it.isLoading }
        // Both answer within the wait: one complete list.
        assertEquals(listOf(2 to false), lists(config))
        // Progressive: each answer shows, and the group then says it is complete.
        assertEquals(listOf(1 to true, 2 to true, 2 to false), lists(config.copy(progressiveResults = true)))
        // A slow indexer does not hold the list back past the wait.
        slowDelay = BuiltInUsenetService.COMPLETE_RESULTS_WAIT_MS * 3
        assertEquals(listOf(1 to true, 2 to true, 2 to false), lists(config))
    }

    @Test fun `a slow season lookup never holds the list back`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), maxSizeGb = 5)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/ep", size = 2L shl 30, indexerId = "a", indexerName = "A"))
        val metadata = FakeMetadata(seasons = { delay(60_000); emptyMap() })
        val shownAt = mutableListOf<Long>()
        val lists = BuiltInUsenetService(client, metadata).search(config, "series", "tt1:1:2", null, null)
            .onEach { if (it.group != null) shownAt += testScheduler.currentTime }.toList().mapNotNull { it.group }
        // Shown at once; the lookup that times out only ends the loading state.
        assertEquals(0L, shownAt.first())
        // Without the count the 9 GB pack is over the 5 GB limit; the episode shows.
        assertEquals(listOf("Show.S01E02.1080p-GRP"), lists.first().streams.map { it.title })
        assertEquals(lists.first().streams, lists.last().streams)
        assertFalse(lists.last().isLoading)
    }

    @Test fun `the list is arranged again when the episode count comes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), maxSizeGb = 5)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/ep", size = 2L shl 30, indexerId = "a", indexerName = "A"))
        val metadata = FakeMetadata(seasons = { delay(1_000); mapOf(1 to 9) })
        val shownAt = mutableListOf<Long>()
        val lists = BuiltInUsenetService(client, metadata).search(config, "series", "tt1:1:2", null, null)
            .onEach { if (it.group != null) shownAt += testScheduler.currentTime }.toList().mapNotNull { it.group }
        assertEquals(listOf(0L, 1_000L), shownAt)
        assertEquals(listOf("Show.S01E02.1080p-GRP"), lists.first().streams.map { it.title })
        // 1 GB per episode: the pack joins the list once the count came.
        assertEquals(setOf("Show.S01E02.1080p-GRP", "Show.S01.1080p-GRP"), lists.last().streams.map { it.title }.toSet())
    }

    @Test fun `results show their release name unless turned off`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Movie.2020.1080p-GRP", "https://a.test/nzb/1", size = 2L shl 30, indexerId = indexer.id, indexerName = indexer.name))
        val service = BuiltInUsenetService(client, FakeMetadata())
        suspend fun description(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams.single().description
        assertEquals("Movie.2020.1080p-GRP\n2.00 GB • A", description(config))
        // Without the release name, its group ends the details.
        assertEquals("2.00 GB • A • GRP", description(config.copy(showReleaseNames = false)))
    }

    @Test fun `results show what the release name says, the bitrate and the quality tier`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            ranking = StreamRankingSettings(StreamRankingPreset.BEST_QUALITY))
        val title = "The.Movie.2026.2160p.UHD.BluRay.REMUX.DV.HDR10Plus.HEVC.TrueHD.Atmos.7.1-FraMeSToR"
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(UsenetRelease(title, "https://a.test/nzb/1",
            size = 60L shl 30, publishedAt = System.currentTimeMillis() - 3 * 86_400_000L - 60_000, indexerId = "a", indexerName = "A"))
        // The runtime comes from the meta the details page holds.
        val catalog = FakeCatalog(held = mapOf("movie:tt1" to MetaDetails(id = "tt1", type = "movie", name = "The Movie", runtime = "120 min")))
        val service = BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
        suspend fun stream(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams.single()
        val shown = stream(config)
        assertEquals("4K Remux • DV HDR10+ • HEVC • T1", shown.name)
        assertEquals("$title\nTrueHD 7.1 Atmos • 60.00 GB • 72 Mbps • 3d • A", shown.description)
        assertEquals("TrueHD 7.1 Atmos • 60.00 GB • 72 Mbps • 3d • A • FraMeSToR",
            stream(config.copy(showReleaseNames = false)).description)
    }

    @Test fun `with size badges shown, only a pack's details keep its size`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            showReleaseNames = false)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/episode", size = 2L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"))
        suspend fun details(sizes: Boolean) = BuiltInUsenetService(client, FakeMetadata(seasons = { mapOf(1 to 9) }),
            sizeBadges = { sizes }).search(config, "series", "tt1:1:2", null, null).toList().lastGroup().streams
            .associate { it.title to it.description }
        assertEquals(mapOf("Show.S01E02.1080p-GRP" to "A • GRP",
            "Show.S01.1080p-GRP" to "9.00 GB whole season (~1.00 GB per episode) • A • GRP"), details(sizes = true))
        assertEquals("2.00 GB • A • GRP", details(sizes = false)["Show.S01E02.1080p-GRP"])
    }

    @Test fun `with fallback on lower priority indexers are only searched when higher ones find nothing`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val shared = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(backup, first, shared),
            indexerFallback = true)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any(), any()) } returns listOf(release(first))
        coEvery { client.search(shared, any(), any(), any()) } returns emptyList()
        coEvery { client.search(backup, any(), any(), any()) } returns listOf(release(backup))
        val service = BuiltInUsenetService(client, FakeMetadata())
        val group = service.search(config, "movie", "tt1", null, null).toList().lastGroup()
        assertEquals(listOf("Movie.2020.1080p\nA"), group.streams.map { it.description })
        assertFalse(group.isLoading)
        coVerify { client.search(shared, any(), any(), any()) }
        coVerify(exactly = 0) { client.capabilities(backup) }

        // A failed or empty higher priority falls through to the next one; the group keeps loading meanwhile.
        coEvery { client.search(first, any(), any(), any()) } throws IllegalStateException("down")
        val fallback = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }
        assertEquals(listOf("Movie.2020.1080p\nC"), fallback.last().streams.map { it.description })
        assertTrue(fallback.dropLast(1).all { it.isLoading })
    }

    @Test fun `failed metadata lookup only fails indexers that need a title search`() = runTest {
        val ids = UsenetIndexer(id = "ids", name = "Ids", apiUrl = "https://ids.test/api")
        val titles = UsenetIndexer(id = "titles", name = "Titles", apiUrl = "https://titles.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(titles, ids))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(ids) } returns NewznabCapabilities()
        coEvery { client.capabilities(titles) } returns NewznabCapabilities(movieParams = setOf("q"))
        coEvery { client.search(ids, any(), any(), any()) } returns listOf(release(ids))
        val results = BuiltInUsenetService(client, FakeMetadata(title = { error("tmdb down") }))
            .search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("Titles: indexer search failed. Check the API URL, key and limits."), results.mapNotNull { it.failure })
        assertEquals(listOf("Movie.2020.1080p\nIds"), results.lastGroup().streams.map { it.description })
    }

    @Test fun `tvdb id is looked up for indexers that only search tv by tvdb`() = runTest {
        val tvdbOnly = UsenetIndexer(id = "tvdb", name = "Tvdb", apiUrl = "https://tvdb.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(tvdbOnly))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(tvdbOnly) } returns NewznabCapabilities(tvParams = setOf("tvdbid", "season", "ep"))
        coEvery { client.search(tvdbOnly, any(), any(), any()) } returns listOf(release(tvdbOnly, "Show.S01E02.1080p"))
        val metadata = FakeMetadata(title = { UsenetTitleMetadata(tmdbId = "1399", tvdbId = "121361") })
        val results = BuiltInUsenetService(client, metadata).search(config, "series", "tt0944947:1:2", null, null).toList()
        assertEquals(listOf("Show.S01E02.1080p\nTvdb"), results.lastGroup().streams.map { it.description })
        coVerify { client.search(tvdbOnly, match { it.tvdbId == "121361" && it.episode == 2 }, any(), any()) }
    }

    @Test fun `an indexer that needs ids or a title TMDB did not give fails visibly`() = runTest {
        val tvdbOnly = UsenetIndexer(id = "tvdb", name = "Tvdb", apiUrl = "https://tvdb.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(tvdbOnly))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(tvdbOnly) } returns NewznabCapabilities(tvParams = setOf("q", "tvdbid", "season", "ep"))
        // TMDB answered nothing usable, as without an API key.
        val results = BuiltInUsenetService(client, FakeMetadata(title = { UsenetTitleMetadata() }))
            .search(config, "series", "tt10986410:3:1", null, null).toList()
        assertEquals(listOf("Tvdb: indexer search failed. Check the API URL, key and limits."), results.mapNotNull { it.failure })
        assertTrue(results.lastGroup().streams.isEmpty())
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `disabled configuration never contacts indexers`() = runTest {
        val client = mockk<NewznabClient>()
        val service = BuiltInUsenetService(client, FakeMetadata())
        assertTrue(service.search(UsenetSourceConfiguration(), "movie", "tt123", null, null).toList().isEmpty())
        coVerify(exactly = 0) { client.capabilities(any()) }
    }

    @Test fun `an empty id search is asked again by title`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, match { it.imdbId != null }, any(), any()) } returns emptyList()
        coEvery { client.search(indexer, match { it.imdbId == null }, any(), any()) } returns listOf(release(indexer, "The.Movie.2020.1080p"))
        val metadata = FakeMetadata(title = { UsenetTitleMetadata(tmdbId = "10", title = "The Movie", year = 2020) })
        val service = BuiltInUsenetService(client, metadata)
        val streams = service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams
        assertEquals(listOf("The.Movie.2020.1080p"), streams.map { it.title })
        coVerify { client.search(indexer, match { it.imdbId == null && it.tmdbId == null && it.title == "The Movie" && it.year == 2020 }, any(), any()) }

        // A title search that also finds nothing is not repeated, and the group ends empty.
        coEvery { client.search(indexer, match { it.imdbId == null }, any(), any()) } returns emptyList()
        val empty = service.search(config, "movie", "tt1", null, null).toList().lastGroup()
        assertTrue(empty.streams.isEmpty())
        assertFalse(empty.isLoading)
        coVerify(exactly = 2) { client.search(indexer, match { it.imdbId == null }, any(), any()) }
    }

    @Test fun `the original title is searched when the id and the title find nothing`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, any(), any(), any()) } returns emptyList()
        coEvery { client.search(indexer, match { it.title == "La casa de papel" }, any(), any()) } returns
            listOf(release(indexer, "La.Casa.de.Papel.2017.1080p"))
        var original = "La casa de papel"
        val metadata = FakeMetadata(title = {
            UsenetTitleMetadata(tmdbId = "10", title = "Money Heist", originalTitle = original, year = 2017)
        })
        val service = BuiltInUsenetService(client, metadata)
        val streams = service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams
        assertEquals(listOf("La.Casa.de.Papel.2017.1080p"), streams.map { it.title })
        coVerify { client.search(indexer, match { it.imdbId == null && it.title == "Money Heist" }, any(), any()) }
        coVerify { client.search(indexer, match { it.title == "La casa de papel" && it.originalTitle == "Money Heist" }, any(), any()) }

        // Indexers search Latin text, so an original title in another script is not searched.
        original = "ペーパー・ハウス"
        assertTrue(service.search(config, "movie", "tt1", null, null).toList().lastGroup().streams.isEmpty())
        coVerify(exactly = 0) { client.search(indexer, match { it.title == "ペーパー・ハウス" }, any(), any()) }
    }

    @Test fun `a movie's id results named after another film are dropped unless its names are unknown`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, match { it.imdbId != null }, any(), any()) } returns listOf(
            release(indexer, "The.Lord.of.the.Rings.The.Two.Towers.2002.1080p"), release(indexer, "The.Ring.2002.1080p"))
        var known = true
        val metadata = FakeMetadata(title = {
            check(known) { "offline" }
            UsenetTitleMetadata(tmdbId = "121", title = "The Lord of the Rings: The Two Towers", otherTitles = emptyList(), year = 2002)
        })
        val service = BuiltInUsenetService(client, metadata)
        suspend fun titles() = service.search(config, "movie", "tt0167261", null, null).toList()
            .mapNotNull { it.group }.lastOrNull()?.streams.orEmpty().map { it.title }.toSet()
        assertEquals(setOf("The.Lord.of.the.Rings.The.Two.Towers.2002.1080p"), titles())

        // Without the film's names and year, the indexer's tags are trusted.
        known = false
        assertEquals(2, titles().size)
    }

    @Test fun `a movie's release is its own binge group, so reusing the group opens it again`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            release(indexer, "Movie.2020.2160p.WEB-DL-GRP"), release(indexer, "Movie.2020.1080p.BluRay-OTHER"))
        val streams = BuiltInUsenetService(client, FakeMetadata()).search(config, "movie", "tt1", null, null).toList()
            .lastGroup().streams
        val played = streams.single { it.title == "Movie.2020.1080p.BluRay-OTHER" }
        assertEquals("Built-in Usenet|movie.2020.1080p.bluray-other", played.behaviorHints.bingeGroup)
        // Opened again with the binge group reused, the release that played is picked, not the first listed.
        val picked = StreamAutoPlaySelector.selectAutoPlayStream(streams, StreamAutoPlayMode.MANUAL, "",
            StreamAutoPlaySource.ALL_SOURCES, emptySet(), emptySet(), emptySet(),
            preferredBingeGroup = played.behaviorHints.bingeGroup, preferBingeGroupInSelection = true, bingeGroupOnly = true)
        assertEquals(played.title, picked?.title)
    }

    private val animeIndexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
    private val animeConfig = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(animeIndexer))

    private fun animeClient(title: String): NewznabClient = mockk<NewznabClient>().also { client ->
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(animeIndexer, any(), any(), any()) } returns listOf(
            UsenetRelease(title, "https://geek.test/nzb/1", indexerId = animeIndexer.id))
    }

    private fun animeMeta(id: String, imdbId: String?, vararg videos: MetaVideo) =
        MetaDetails(id = id, type = "series", name = "Show", imdbId = imdbId, videos = videos.toList())

    @Test fun `an anime catalog's episode is searched by the IMDb id and numbering its meta gives`() = runTest {
        val client = animeClient("Show.S02E01.1080p")
        val catalog = FakeCatalog(held = mapOf("series:kitsu:49240" to animeMeta("kitsu:49240", "tt22248376",
            MetaVideo(id = "kitsu:49240:1", title = "1", season = 1, episode = 1, imdbSeason = 2, imdbEpisode = 1))))
        // The catalog numbers the second season's first episode S1E1; the player passes that.
        val stream = BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "kitsu:49240:1", 1, 1).toList().lastGroup().streams.single()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt22248376" && it.season == 2 && it.episode == 1 }, any(), any()) }
        coVerify(exactly = 0) { client.search(animeIndexer, match { it.season == 1 }, any(), any()) }
        // Its file in a pack is picked by the same numbering, whatever the player asks for.
        assertEquals(2 to 1, engineEpisode(stream, 1, 1))
    }

    @Test fun `an IMDb id is searched as before, without looking at metas`() = runTest {
        val client = animeClient("Show.S01E03.1080p")
        val catalog = FakeCatalog()
        val stream = BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "tt123:1:3", 1, 3, contentId = "tt123").toList().lastGroup().streams.single()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt123" && it.season == 1 && it.episode == 3 }, any(), any()) }
        assertEquals(emptyList<String>(), catalog.asked)
        assertNull(stream.usenetSeason)
        assertNull(stream.usenetEpisode)
    }

    @Test fun `a catalog episode of a title with its own IMDb id is searched by that id and the title's numbering`() = runTest {
        val client = animeClient("Show.S03E02.1080p")
        val catalog = FakeCatalog()
        BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "kitsu:9:40", 3, 2, contentId = "tt777").toList()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt777" && it.season == 3 && it.episode == 2 }, any(), any()) }
        // Neither the catalog's addon nor Simkl is asked.
        assertEquals(listOf("held series:tt777"), catalog.asked)
    }

    @Test fun `without IMDb numbering in the meta, Simkl's TVDB numbering is searched with the meta's IMDb id`() = runTest {
        val client = animeClient("Show.S02E01.1080p")
        val catalog = FakeCatalog(
            held = mapOf("series:mal:52991" to animeMeta("mal:52991", "tt22248376",
                MetaVideo(id = "mal:52991:1", title = "1", season = 1, episode = 1))),
            simkl = mapOf("mal:52991" to SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt999")),
            episodes = mapOf(7L to listOf(SimklIdResolver.EpisodeMapping(animeEpisode = 1, tvdbSeason = 2, tvdbEpisode = 1))))
        val stream = BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "mal:52991:1", 1, 1, contentId = "mal:52991").toList().lastGroup().streams.single()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt22248376" && it.season == 2 && it.episode == 1 }, any(), any()) }
        assertEquals(2 to 1, engineEpisode(stream, 1, 1))
    }

    @Test fun `a meta without a usable IMDb id takes Simkl's`() = runTest {
        val client = animeClient("Show.S01E05.1080p")
        val catalog = FakeCatalog(
            held = mapOf("series:kitsu:46474" to animeMeta("kitsu:46474", "not-an-id")),
            simkl = mapOf("kitsu:46474" to SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt999")),
            episodes = mapOf(7L to listOf(SimklIdResolver.EpisodeMapping(animeEpisode = 5, tvdbSeason = 1, tvdbEpisode = 5))))
        BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "kitsu:46474:5", 1, 5).toList()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt999" && it.season == 1 && it.episode == 5 }, any(), any()) }
    }

    @Test fun `a catalog's meta is asked of its addon when the details page no longer holds it`() = runTest {
        val client = animeClient("Show.S02E01.1080p")
        val catalog = FakeCatalog(fetched = mapOf("series:kitsu:49240" to animeMeta("kitsu:49240", "tt22248376",
            MetaVideo(id = "kitsu:49240:1", title = "1", season = 1, episode = 1, imdbSeason = 2, imdbEpisode = 1))))
        BuiltInUsenetService(client, FakeMetadata(), catalog = catalog)
            .search(animeConfig, "series", "kitsu:49240:1", 1, 1).toList()
        coVerify { client.search(animeIndexer, match { it.imdbId == "tt22248376" && it.season == 2 && it.episode == 1 }, any(), any()) }
        assertEquals(listOf("held series:kitsu:49240", "fetched series:kitsu:49240"), catalog.asked)
    }

    @Test fun `a catalog episode nothing maps is not searched with the catalog's own numbers`() = runTest {
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        // The real client sends nothing for a request without an ID or a title.
        coEvery { client.search(animeIndexer, any(), any(), any()) } returns emptyList()
        val catalog = FakeCatalog(
            held = mapOf("series:kitsu:49240" to animeMeta("kitsu:49240", "tt22248376")),
            simkl = mapOf("kitsu:49240" to SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt22248376")))
        val results = BuiltInUsenetService(client, FakeMetadata(title = { UsenetTitleMetadata() }), catalog = catalog)
            .search(animeConfig, "series", "kitsu:49240:1", 1, 1, contentId = "kitsu:49240").toList()
        assertTrue(results.mapNotNull { it.group }.all { it.streams.isEmpty() })
        coVerify(exactly = 0) { client.search(any(), match { it.imdbId != null || it.tvdbId != null || it.tmdbId != null }, any(), any()) }
        coVerify(exactly = 0) { client.search(any(), match { it.title != null }, any(), any()) }
    }

    // The Korean Suits (2018), which shares its name with the American one (2011).
    private fun koreanSuits() = UsenetSeriesDetails("77548", name = "Suits", firstAirDate = "2018-04-25", votes = 24,
        countries = setOf("KR"), seasons = listOf(UsenetSeasonSummary(1, 16, "2018-04-25")))
    private fun suitsSearch() = listOf(
        UsenetTitledSeries("37680", "Suits", "Suits", 2011, setOf("US"), 6055),
        UsenetTitledSeries("77548", "Suits", "슈츠", 2018, setOf("KR"), 24),
        UsenetTitledSeries("259453", "Suits LA", "Suits LA", 2025, setOf("US"), 66))

    /** TMDB as it answers for the Korean Suits: [details] and [search] stand for its details and its TV search. */
    private fun suitsMetadata(
        details: suspend () -> UsenetSeriesDetails? = { koreanSuits() },
        search: () -> List<UsenetTitledSeries>? = { suitsSearch() },
    ) = FakeMetadata(details = details, title = { found ->
        val show = found ?: error("TMDB id unavailable")
        UsenetTitleMetadata(imdbId = "tt8084058", tmdbId = show.tmdbId, tvdbId = "336811", title = show.name,
            year = show.firstAirDate?.take(4)?.toIntOrNull(), countries = show.countries, votes = show.votes,
            sameTitleSeries = show.name?.let { search() })
    })

    @Test fun `a series' results of another series of its name are dropped once its metadata comes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(release(indexer, "Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"),
            release(indexer, "Suits.S01E02.1080p.BluRay-GERUDO"), release(indexer, "Suits.S01E02.2011.1080p.WEB-DL-DBTV"))
        suspend fun titles(metadata: FakeMetadata) = BuiltInUsenetService(client, metadata)
            .search(config, "series", "tt8084058:1:2", null, null).toList().lastGroup().streams.map { it.title }.toSet()
        assertEquals(setOf("Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"), titles(suitsMetadata()))
        // Without the series of its name, only names with another year are another series'.
        assertEquals(setOf("Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG", "Suits.S01E02.1080p.BluRay-GERUDO"),
            titles(suitsMetadata(search = { null })))
        // Without its year, every result is listed.
        assertEquals(3, titles(suitsMetadata(details = { UsenetSeriesDetails("77548") })).size)
    }

    @Test fun `a series' list never waits for its metadata and is arranged again when it comes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(release(indexer, "Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"),
            release(indexer, "Suits.S01E02.1080p.BluRay-GERUDO"))
        val shownAt = mutableListOf<Long>()
        val lists = BuiltInUsenetService(client, suitsMetadata(details = { delay(1_000); koreanSuits() }))
            .search(config, "series", "tt8084058:1:2", null, null)
            .onEach { if (it.group != null) shownAt += testScheduler.currentTime }.toList().mapNotNull { it.group }
        assertEquals(listOf(0L, 1_000L), shownAt)
        assertEquals(2, lists.first().streams.size)
        assertTrue(lists.first().isLoading)
        assertEquals(listOf("Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"), lists.last().streams.map { it.title })

        // Metadata later than its few seconds changes nothing: the list only stops loading.
        val late = BuiltInUsenetService(client, suitsMetadata(details = { delay(60_000); koreanSuits() }))
            .search(config, "series", "tt8084058:1:2", null, null).toList().mapNotNull { it.group }
        assertEquals(late.first().streams, late.last().streams)
        assertEquals(2, late.last().streams.size)
        assertFalse(late.last().isLoading)
    }

    @Test fun `an id search that found only another series of its name is asked again by title`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(tvParams = setOf("imdbid", "q", "season", "ep"))
        // The indexer filed the American series' release under the Korean one's id, and answers after TMDB.
        coEvery { client.search(indexer, match { it.imdbId != null }, any(), any()) } coAnswers {
            delay(100); listOf(release(indexer, "Suits.S01E02.2011.1080p.WEB-DL-DBTV"))
        }
        coEvery { client.search(indexer, match { it.imdbId == null }, any(), any()) } returns
            listOf(release(indexer, "Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"))
        val streams = BuiltInUsenetService(client, suitsMetadata()).search(config, "series", "tt8084058:1:2", null, null)
            .toList().lastGroup().streams
        assertEquals(listOf("Suits.2018.S01E02.1080p.NF.WEB-DL-TSRG"), streams.map { it.title })
        coVerify { client.search(indexer, match { it.imdbId == null && it.title == "Suits" && it.episode == 2 }, any(), any()) }
    }
}
