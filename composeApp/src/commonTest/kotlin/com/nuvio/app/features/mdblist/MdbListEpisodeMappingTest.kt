package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.trakt.EpisodeMappingEntry
import com.nuvio.app.features.watched.watchedItemKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MdbListEpisodeMappingTest {
    @Test
    fun `episodes move between one TMDB season and the addon's two seasons`() = runTest {
        val service = mdbListTestEpisodeMappings(backgroundScope, addonSeasons = listOf(24, 23), tmdbSeasons = listOf(47))
        val mapping = assertNotNull(service.resolve("tt1", MdbListProgressProjection.Empty))
        assertEquals(EpisodeMappingEntry(2, 11, videoId = "tt1:2:11"), mapping.toAddon(1, 35))
        assertEquals(EpisodeMappingEntry(1, 35), mapping.toTmdb(2, 11))
        assertEquals(EpisodeMappingEntry(1, 35), mapping.toTmdb(9, 9, videoId = "tt1:2:11"))
        assertEquals(EpisodeMappingEntry(1, 24), mapping.toTmdb(1, 24))
        assertSame(mapping, service.cached("tt1"))
    }

    @Test
    fun `episodes match by title before position`() {
        val mapping = MdbListEpisodeMapping.between(
            listOf(episode(1, 1, "Arrival"), episode(1, 2, "Departure"), episode(2, 1, "Return"), episode(2, 2, "Farewell")),
            listOf(episode(1, 1, "Arrival"), episode(1, 2, "Return"), episode(1, 3, "Farewell")),
        )
        assertEquals(2 to 1, mapping.toAddon(1, 2)?.let { it.season to it.episode })
        assertEquals(1 to 3, mapping.toTmdb(2, 2)?.let { it.season to it.episode })
    }

    @Test
    fun `shows with the same seasons keep their numbers without loading TMDB seasons`() = runTest {
        val tmdb = MdbListTestTmdbEpisodes(listOf(23, 24))
        val service = mdbListTestEpisodeMappings(backgroundScope, addonSeasons = listOf(24, 24), tmdb = tmdb)
        val reference = episodeReference(2, 1)
        assertSame(reference, service.toMdbListNumbering(reference, MdbListProgressProjection.Empty))
        assertFalse(assertNotNull(service.cached("tt1")).remaps)
        assertTrue(tmdb.seasonRequests.isEmpty())
    }

    @Test
    fun `missing addon episodes leave the mapping unresolved`() = runTest {
        val service = mdbListTestEpisodeMappings(backgroundScope, addonSeasons = emptyList(), tmdbSeasons = listOf(47))
        assertNull(service.resolve("tt1", MdbListProgressProjection.Empty))
        assertNull(service.cached("tt1"))
    }

    @Test
    fun `watched episodes and next-up seeds come in the addon's numbering once the mapping is known`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seed(h.snapshot().copy(watched = listOf(mdbListTestEpisode(1, 35))))
        h.repository.ensureLoaded()
        val mappings = mdbListTestEpisodeMappings(backgroundScope, addonSeasons = listOf(24, 23), tmdbSeasons = listOf(47))
        val progress = progressProvider(h, mappings)
        val watched = MdbListWatchedSyncAdapter(h.repository, MdbListHistoryService(h.http.api, h.repository), mappings, h.http.store, h.activeProfile)
        assertEquals("tt1:1:35", watched.pull(1, 100).single().videoId)

        progress.refreshEpisodeProgress("tt1", forceRefresh = false)

        assertEquals("tt1:2:11", watched.pull(1, 100).single().videoId)
        val keys = watched.pullExtraWatchedKeys(1)
        assertTrue(watchedItemKey("series", "tmdb:1", 2, 11) in keys)
        assertFalse(watchedItemKey("series", "tt1", 1, 35) in keys)
        val seed = progress.snapshot().entries.single()
        assertEquals(2 to 11, seed.seasonNumber to seed.episodeNumber)
    }

    @Test
    fun `specials keep their numbers in remapped shows`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seed(h.snapshot().copy(watched = listOf(mdbListTestEpisode(0, 1))))
        h.repository.ensureLoaded()
        val mappings = mdbListTestEpisodeMappings(backgroundScope, addonSeasons = listOf(24, 23), tmdbSeasons = listOf(47))
        val watched = MdbListWatchedSyncAdapter(h.repository, MdbListHistoryService(h.http.api, h.repository), mappings, h.http.store, h.activeProfile)
        progressProvider(h, mappings).refreshEpisodeProgress("tt1", forceRefresh = false)

        assertTrue(assertNotNull(mappings.cached("tt1")).remaps)
        assertEquals("tt1:0:1", watched.pull(1, 100).single().videoId)
    }

    @Test
    fun `next-up entries move to the addon's numbering when the mapping was not known`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seed(h.snapshot().copy(watched = listOf(mdbListTestEpisode(1, 35))))
        h.repository.ensureLoaded()
        val progress = progressProvider(h, mdbListTestEpisodeMappings(backgroundScope, listOf(24, 23), listOf(47)))
        val entries = progress.snapshot().entries
        assertEquals(1 to 35, entries.single().let { it.seasonNumber to it.episodeNumber })

        val prepared = progress.prepareNextUpProgressEntries(entries, "tt1").single()

        assertEquals(2 to 11, prepared.seasonNumber to prepared.episodeNumber)
        assertEquals("tt1:2:11", prepared.videoId)
    }

    @Test
    fun `marking an addon episode watched writes TMDB's episode`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.http.reply(body = """{"updated":{"episodes":1}}""")
        val writer = MdbListTrackingWrites(
            h.repository, MdbListHistoryService(h.http.api, h.repository), MdbListScrobbleService(h.http.api, h.repository),
            mdbListTestEpisodeMappings(backgroundScope, addonSeasons = listOf(24, 23), tmdbSeasons = listOf(47)),
        )

        writer.addToHistory(1, listOf(TrackingHistoryItem(episodeReference(2, 11), mdbListTimestamp(MDBLIST_TEST_TIME))))

        val show = mdbListResponseElement(h.http.engine.requests.single().body).objectValue().arrayValue("shows").single().objectValue()
        val season = show.arrayValue("seasons").single().objectValue()
        assertEquals(1, season.integer("number"))
        assertEquals(35, season.arrayValue("episodes").single().objectValue().integer("number"))
    }

    private fun progressProvider(h: MdbListSyncTestHarness, mappings: MdbListEpisodeMappingService) =
        MdbListTrackingProgressProvider(h.repository, MdbListScrobbleService(h.http.api, h.repository), mappings, h.http.store, h.activeProfile, {})

    private fun episodeReference(season: Int, episode: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(imdb = "tt1", tmdb = 1),
        episode = TrackingEpisode(season = season, number = episode),
        catalog = TrackingCatalogReference("tt1", "series", "tt1:$season:$episode"),
    )

    private fun episode(season: Int, episode: Int, title: String) = EpisodeMappingEntry(season, episode, title)
}

internal class MdbListTestTmdbEpisodes(private val seasons: List<Int>) : MdbListTmdbEpisodes {
    val seasonRequests = mutableListOf<Int>()

    override suspend fun showId(contentId: String): Long = 1
    override suspend fun seasonCounts(showId: Long): Map<Int, Int> =
        seasons.withIndex().associate { (index, count) -> index + 1 to count }
    override suspend fun episodes(showId: Long, season: Int): List<EpisodeMappingEntry> {
        seasonRequests += season
        return (1..seasons[season - 1]).map { EpisodeMappingEntry(season, it) }
    }
}

internal fun mdbListTestEpisodeMappings(
    scope: CoroutineScope,
    addonSeasons: List<Int> = emptyList(),
    tmdbSeasons: List<Int> = emptyList(),
    tmdb: MdbListTmdbEpisodes = MdbListTestTmdbEpisodes(tmdbSeasons),
) = MdbListEpisodeMappingService(
    scope,
    addonEpisodes = { contentId ->
        addonSeasons.flatMapIndexed { index, count ->
            (1..count).map { EpisodeMappingEntry(index + 1, it, videoId = "$contentId:${index + 1}:$it") }
        }
    },
    tmdb = tmdb,
)
