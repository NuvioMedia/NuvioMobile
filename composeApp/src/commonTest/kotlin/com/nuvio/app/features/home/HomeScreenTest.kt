package com.nuvio.app.features.home

import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryItemType
import com.nuvio.app.features.cloud.CloudLibraryProviderState
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import com.nuvio.app.features.watchprogress.resolvedProgressKey
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeScreenTest {

    @Test
    fun `home remains loading while initial addon manifests are pending`() {
        assertTrue(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = true,
                homeCatalogLoading = false,
            ),
        )
    }

    @Test
    fun `home does not show loading over rows or a terminal empty state`() {
        assertFalse(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = true,
                addonManifestsLoading = true,
                homeCatalogLoading = true,
            ),
        )
        assertFalse(
            shouldShowInitialHomeLoading(
                hasRenderableHomeRows = false,
                addonManifestsLoading = false,
                homeCatalogLoading = false,
            ),
        )
    }

    @Test
    fun `home collapses hero space for terminal empty content`() {
        assertFalse(
            shouldShowHomeHeroSlot(
                heroEnabled = true,
                hasHeroItems = false,
                isResolvingHeroSources = false,
                hasRenderableHomeRows = false,
            ),
        )
        assertTrue(
            shouldShowHomeHeroSlot(
                heroEnabled = true,
                hasHeroItems = false,
                isResolvingHeroSources = true,
                hasRenderableHomeRows = false,
            ),
        )
    }

    @Test
    fun `home trakt continue watching candidate limits match TV`() {
        assertEquals(300, HomeContinueWatchingMaxRecentProgressItems)
        assertEquals(32, HomeNextUpInitialResolutionLimit)
    }

    @Test
    fun `home next up resolution keeps candidates beyond initial limit for background resolution`() {
        val candidates = (1..35).map { index -> completedSeriesCandidate(index) }

        val plan = planHomeNextUpResolutionCandidates(candidates)

        assertEquals(HomeNextUpInitialResolutionLimit, plan.initialCandidates.size)
        assertEquals(3, plan.deferredCandidates.size)
        assertEquals("show-1", plan.initialCandidates.first().content.id)
        assertEquals("show-33", plan.deferredCandidates.first().content.id)
    }

    @Test
    fun `build home continue watching items removes duplicate video ids`() {
        val inProgress = progressEntry(
            videoId = "tt0944947:1:4",
            title = "Game of Thrones",
            episodeTitle = "Cripples, Bastards, and Broken Things",
            lastUpdatedEpochMs = 250L,
        )
        val nextUp = continueWatchingItem(
            videoId = "tt0944947:1:4",
            subtitle = "Next Up • S1E4 • Cripples, Bastards, and Broken Things",
        )
        val movie = progressEntry(
            videoId = "movie-1",
            title = "Movie",
            lastUpdatedEpochMs = 100L,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress, movie),
            nextUpItemsBySeries = mapOf("tt0944947" to (200L to nextUp)),
        )

        assertEquals(listOf("tt0944947:1:4", "movie-1"), result.map(ContinueWatchingItem::videoId))
        assertEquals("S1 E4 • Cripples, Bastards, and Broken Things", result.first().subtitle)
    }

    @Test
    fun `build home continue watching items prefers progress entry on timestamp tie`() {
        val inProgress = progressEntry(
            videoId = "show:1:5",
            title = "Show",
            episodeNumber = 5,
            episodeTitle = "The Wolf and the Lion",
            lastUpdatedEpochMs = 500L,
        )
        val nextUp = continueWatchingItem(
            videoId = "show:1:5",
            subtitle = "Next Up • S1E5 • The Wolf and the Lion",
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress),
            nextUpItemsBySeries = mapOf("show" to (500L to nextUp)),
        )

        assertEquals(1, result.size)
        assertEquals("S1 E5 • The Wolf and the Lion", result.single().subtitle)
    }

    @Test
    fun `build home continue watching items keeps deferred next up items with metadata`() {
        val nextUpItems = (1..35).associate { index ->
            val id = "show-$index"
            val item = continueWatchingItem(
                videoId = "$id:1:$index",
                subtitle = "Next Up • S1E$index",
                imageUrl = "https://example.test/$id.jpg",
                logo = "https://example.test/$id-logo.png",
                episodeThumbnail = "https://example.test/$id-thumb.jpg",
            )

            id to ((10_000L - index) to item)
        }

        val result = buildHomeContinueWatchingItems(
            visibleEntries = emptyList(),
            nextUpItemsBySeries = nextUpItems,
        )
        val deferredItem = result.first { item -> item.parentMetaId == "show-33" }

        assertEquals(35, result.size)
        assertEquals("https://example.test/show-33.jpg", deferredItem.imageUrl)
        assertEquals("https://example.test/show-33-logo.png", deferredItem.logo)
        assertEquals("https://example.test/show-33-thumb.jpg", deferredItem.episodeThumbnail)
    }

    @Test
    fun `build home continue watching items suppresses next up when series has in progress resume`() {
        val inProgress = progressEntry(
            videoId = "show:1:4",
            title = "Show",
            episodeNumber = 4,
            episodeTitle = "Current",
            lastUpdatedEpochMs = 200L,
        )
        val nextUp = continueWatchingItem(
            videoId = "show:1:5",
            subtitle = "Next Up • S1E5 • Next",
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress),
            nextUpItemsBySeries = mapOf("show" to (500L to nextUp)),
        )

        assertEquals(listOf("show:1:4"), result.map(ContinueWatchingItem::videoId))
        assertEquals("S1 E4 • Current", result.single().subtitle)
    }

    @Test
    fun `split upcoming mode moves only unaired next up episodes and sorts them by release`() {
        val nowEpochMs = requireNotNull(parseReleaseDateToEpochMs("2026-07-19T12:00:00Z"))
        val inProgress = progressEntry(
            videoId = "movie-1",
            title = "Movie",
            lastUpdatedEpochMs = 500L,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
        ).toContinueWatchingItem()
        val aired = continueWatchingItem(
            videoId = "aired:1:2",
            subtitle = "S1E2 • Aired",
        ).copy(released = "2026-07-19T11:59:59Z")
        val unknownRelease = continueWatchingItem(
            videoId = "unknown:1:2",
            subtitle = "S1E2 • Unknown",
        )
        val laterUpcoming = continueWatchingItem(
            videoId = "later:1:2",
            subtitle = "S1E2 • Later",
        ).copy(released = "2026-07-21T00:00:00Z")
        val soonerUpcoming = continueWatchingItem(
            videoId = "sooner:1:2",
            subtitle = "S1E2 • Sooner",
        ).copy(released = "2026-07-20T00:00:00Z")

        val (main, upcoming) = splitUpcomingItems(
            items = listOf(laterUpcoming, inProgress, aired, unknownRelease, soonerUpcoming),
            mode = ContinueWatchingSortMode.SPLIT_UPCOMING,
            nowEpochMs = nowEpochMs,
        )

        assertEquals(
            listOf("movie-1", "aired:1:2", "unknown:1:2"),
            main.map(ContinueWatchingItem::videoId),
        )
        assertEquals(
            listOf("sooner:1:2", "later:1:2"),
            upcoming.map(ContinueWatchingItem::videoId),
        )
    }

    @Test
    fun `non split sort modes keep upcoming episodes in continue watching`() {
        val futureItem = continueWatchingItem(
            videoId = "future:1:2",
            subtitle = "S1E2 • Future",
        ).copy(released = "2099-01-01T00:00:00Z")

        listOf(
            ContinueWatchingSortMode.DEFAULT,
            ContinueWatchingSortMode.STREAMING_STYLE,
        ).forEach { mode ->
            val (main, upcoming) = splitUpcomingItems(
                items = listOf(futureItem),
                mode = mode,
                nowEpochMs = 0L,
            )

            assertEquals(listOf(futureItem), main)
            assertTrue(upcoming.isEmpty())
        }
    }

    @Test
    fun `build home continue watching items enriches cloud title from library file`() {
        val file = CloudLibraryFile(id = "8", name = "GOAT.2026.2160p.UHD.mkv")
        val cloudItem = CloudLibraryItem(
            providerId = DebridProviders.TORBOX_ID,
            providerName = DebridProviders.Torbox.displayName,
            id = "29773238",
            type = CloudLibraryItemType.Torrent,
            name = "GOAT torrent",
            files = listOf(file),
        )
        val progress = WatchProgressEntry(
            contentType = "cloud",
            parentMetaId = cloudItem.stableKey,
            parentMetaType = "cloud",
            videoId = cloudItem.playbackVideoId(file),
            title = cloudItem.stableKey,
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = 500L,
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress),
            nextUpItemsBySeries = emptyMap(),
            cloudLibraryUiState = CloudLibraryUiState(
                isLoaded = true,
                providers = listOf(
                    CloudLibraryProviderState(
                        provider = DebridProviders.Torbox,
                        items = listOf(cloudItem),
                    ),
                ),
            ),
        )

        assertEquals("GOAT.2026.2160p.UHD.mkv", result.single().title)
    }

    @Test
    fun `build home continue watching items preserves cached in progress identity and metadata`() {
        val progress = progressEntry(
            videoId = "show:1:4",
            title = "Show",
            lastUpdatedEpochMs = 500L,
        ).copy(
            logo = " ",
            poster = "",
            background = "\t",
            episodeTitle = " ",
            episodeThumbnail = "",
            pauseDescription = "  ",
        )
        val cached = ContinueWatchingItem(
            parentMetaId = "show",
            parentMetaType = "series",
            videoId = "stable-show:1:4",
            title = "Cached Show",
            subtitle = "Cached S1E4",
            imageUrl = "https://example.test/cached.jpg",
            logo = "https://example.test/logo.png",
            poster = "https://example.test/poster.jpg",
            background = "https://example.test/backdrop.jpg",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = "Cached Episode",
            episodeThumbnail = "https://example.test/thumb.jpg",
            pauseDescription = "Cached description",
            isNextUp = false,
            resumePositionMs = 120_000L,
            durationMs = 1_000_000L,
            progressFraction = 0.12f,
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress),
            cachedInProgressByProgressKey = mapOf(progress.resolvedProgressKey() to cached),
            nextUpItemsBySeries = emptyMap(),
        )

        assertEquals("stable-show:1:4", result.single().videoId)
        assertEquals("Cached S1E4", result.single().subtitle)
        assertEquals("https://example.test/cached.jpg", result.single().imageUrl)
        assertEquals("https://example.test/logo.png", result.single().logo)
        assertEquals("https://example.test/poster.jpg", result.single().poster)
        assertEquals("https://example.test/backdrop.jpg", result.single().background)
        assertEquals("Cached Episode", result.single().episodeTitle)
        assertEquals("https://example.test/thumb.jpg", result.single().episodeThumbnail)
        assertEquals("Cached description", result.single().pauseDescription)
    }

    @Test
    fun `continue watching artwork selection skips blank values`() {
        val progress = progressEntry(
            videoId = "show:1:4",
            title = "Show",
            lastUpdatedEpochMs = 500L,
        ).copy(
            logo = " ",
            poster = "https://example.test/poster.jpg",
            background = "\t",
            episodeThumbnail = "",
        )

        val item = progress.toContinueWatchingItem()

        assertEquals("https://example.test/poster.jpg", item.imageUrl)
        assertEquals("https://example.test/poster.jpg", item.poster)
        assertNull(item.logo)
        assertNull(item.background)
        assertNull(item.episodeThumbnail)
    }

    @Test
    fun `home in progress snapshot preserves cached metadata through serialization round trip`() {
        val progress = progressEntry(
            videoId = "show:1:4",
            title = " ",
            lastUpdatedEpochMs = 500L,
        ).copy(
            progressKey = "opaque-progress-key",
            logo = "",
            poster = " ",
            background = "\t",
            episodeTitle = "",
            episodeThumbnail = " ",
            pauseDescription = "",
        )
        val cached = CachedInProgressItem(
            contentId = "show",
            contentType = "series",
            name = "Cached Show",
            poster = "https://example.test/poster.jpg",
            backdrop = "https://example.test/backdrop.jpg",
            logo = "https://example.test/logo.png",
            videoId = "show:1:4",
            season = 1,
            episode = 4,
            episodeTitle = "Cached Episode",
            episodeThumbnail = "https://example.test/thumb.jpg",
            pauseDescription = "Cached description",
            position = 10L,
            duration = 20L,
            lastWatched = 30L,
            progressPercent = 50f,
            progressKey = "opaque-progress-key",
        )

        val snapshot = buildHomeInProgressCacheSnapshot(
            visibleEntries = listOf(progress),
            cachedEntries = listOf(cached),
        ).single()
        val encoded = Json.encodeToString(CachedInProgressItem.serializer(), snapshot)
        val restored = Json.decodeFromString(CachedInProgressItem.serializer(), encoded)

        assertEquals("Cached Show", restored.name)
        assertEquals("https://example.test/poster.jpg", restored.poster)
        assertEquals("https://example.test/backdrop.jpg", restored.backdrop)
        assertEquals("https://example.test/logo.png", restored.logo)
        assertEquals("Cached Episode", restored.episodeTitle)
        assertEquals("https://example.test/thumb.jpg", restored.episodeThumbnail)
        assertEquals("Cached description", restored.pauseDescription)
        assertEquals(progress.lastPositionMs, restored.position)
        assertEquals(progress.durationMs, restored.duration)
        assertEquals(progress.lastUpdatedEpochMs, restored.lastWatched)
        assertEquals("opaque-progress-key", restored.progressKey)
        assertNull(restored.progressPercent)
    }

    @Test
    fun `cached artwork does not cross aliases that share a playback video id`() {
        val firstProgress = progressEntry(
            videoId = "shared-video",
            title = "First",
            lastUpdatedEpochMs = 500L,
        ).copy(
            parentMetaId = "show-a",
            progressKey = "opaque-a",
        )
        val secondProgress = firstProgress.copy(
            parentMetaId = "show-b",
            title = "Second",
            lastUpdatedEpochMs = 400L,
            progressKey = "opaque-b",
        )
        val firstCached = firstProgress.toContinueWatchingItem().copy(
            imageUrl = "https://example.test/a.jpg",
            poster = "https://example.test/a.jpg",
        )
        val secondCached = secondProgress.toContinueWatchingItem().copy(
            imageUrl = "https://example.test/b.jpg",
            poster = "https://example.test/b.jpg",
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(firstProgress, secondProgress),
            cachedInProgressByProgressKey = mapOf(
                "opaque-a" to firstCached,
                "opaque-b" to secondCached,
            ),
            nextUpItemsBySeries = emptyMap(),
        ).associateBy(ContinueWatchingItem::parentMetaId)

        assertEquals("https://example.test/a.jpg", result.getValue("show-a").imageUrl)
        assertEquals("https://example.test/b.jpg", result.getValue("show-b").imageUrl)
    }

    @Test
    fun `provider continue watching cutoff filters old progress`() {
        val oldEntry = progressEntry(
            videoId = "old",
            title = "Old",
            lastUpdatedEpochMs = 1_000L,
            seasonNumber = null,
            episodeNumber = null,
        )
        val recentEntry = progressEntry(
            videoId = "recent",
            title = "Recent",
            lastUpdatedEpochMs = 30L * MILLIS_PER_DAY,
            seasonNumber = null,
            episodeNumber = null,
        )
        val entries = listOf(oldEntry, recentEntry)

        val filtered = filterEntriesForContinueWatchingWindow(
            entries = entries,
            cutoffEpochMs = 30L * MILLIS_PER_DAY,
        )
        val sourceWithoutCutoff = filterEntriesForContinueWatchingWindow(
            entries = entries,
            cutoffEpochMs = null,
        )

        assertEquals(listOf("recent"), filtered.map(WatchProgressEntry::videoId))
        assertEquals(listOf("old", "recent"), sourceWithoutCutoff.map(WatchProgressEntry::videoId))
    }

    @Test
    fun `provider without a continue watching cutoff keeps old progress`() {
        val oldEntry = progressEntry(
            videoId = "old",
            title = "Old",
            lastUpdatedEpochMs = 1_000L,
            seasonNumber = null,
            episodeNumber = null,
        )
        val recentEntry = progressEntry(
            videoId = "recent",
            title = "Recent",
            lastUpdatedEpochMs = 30L * MILLIS_PER_DAY,
            seasonNumber = null,
            episodeNumber = null,
        )

        val result = filterEntriesForContinueWatchingWindow(
            entries = listOf(oldEntry, recentEntry),
            cutoffEpochMs = null,
        )

        assertEquals(listOf("old", "recent"), result.map(WatchProgressEntry::videoId))
    }

    @Test
    fun `home next up seed uses completed progress when watched item lags on Nuvio Sync`() {
        val completedProgress = progressEntry(
            videoId = "show:4:14",
            title = "Show",
            seasonNumber = 4,
            episodeNumber = 14,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        )
        val olderWatchedItem = watchedItem(
            id = "show",
            season = 4,
            episode = 10,
            markedAtEpochMs = 1_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(completedProgress),
            watchedItems = listOf(olderWatchedItem),
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
        )

        assertEquals(1, result.size)
        assertEquals("show", result.single().content.id)
        assertEquals(4, result.single().seasonNumber)
        assertEquals(14, result.single().episodeNumber)
    }

    @Test
    fun `home next up seed uses furthest watched item when progress is older`() {
        val olderCompletedProgress = progressEntry(
            videoId = "show:4:10",
            title = "Show",
            seasonNumber = 4,
            episodeNumber = 10,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        )
        val newerWatchedItem = watchedItem(
            id = "show",
            season = 4,
            episode = 14,
            markedAtEpochMs = 1_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(olderCompletedProgress),
            watchedItems = listOf(newerWatchedItem),
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
        )

        assertEquals(4, result.single().seasonNumber)
        assertEquals(14, result.single().episodeNumber)
    }

    @Test
    fun `provider-owned completed history ignores the separate watched projection`() {
        val traktProgress = progressEntry(
            videoId = "show:1:2",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 2,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        ).copy(source = WatchProgressSourceTraktHistory)
        val watchedItem = watchedItem(
            id = "other-show",
            season = 3,
            episode = 8,
            markedAtEpochMs = 3_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(traktProgress),
            watchedItems = listOf(watchedItem),
            providerOwnsCompletedHistory = true,
            preferFurthestEpisode = true,
            nowEpochMs = 4_000L,
        )

        assertEquals(listOf("show"), result.map { it.content.id })
    }

    @Test
    fun `hidden provider content cannot seed next up from progress or watched history`() {
        val progress = progressEntry(
            videoId = "dropped-show:1:2",
            title = "Dropped Show",
            seasonNumber = 1,
            episodeNumber = 2,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        )
        val watched = watchedItem(
            id = "dropped-show",
            season = 1,
            episode = 2,
            markedAtEpochMs = 2_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(progress),
            watchedItems = listOf(watched),
            providerOwnsCompletedHistory = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
            isContentHidden = { contentId -> contentId == "dropped-show" },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `stale live next up item is dropped when current seed advances`() {
        val staleNextUp = continueWatchingItem(
            videoId = "show:4:11",
            subtitle = "Next Up • S4E11",
            seedSeasonNumber = 4,
            seedEpisodeNumber = 10,
        )

        val result = filterNextUpItemsByCurrentSeeds(
            nextUpItemsBySeries = mapOf("show" to (1_000L to staleNextUp)),
            activeSeedContentIds = setOf("show"),
            currentSeedByContentId = mapOf("show" to (4 to 14)),
            shouldDropItemsWithoutActiveSeed = true,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `home next up waits for the selected seed source before resolving or clearing cache`() {
        assertFalse(
            isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = false,
                hasLoadedRemoteProgress = false,
                hasLoadedWatchedItems = true,
                hasLoadedRemoteWatchedItems = true,
            ),
        )
        assertFalse(
            isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = false,
                hasLoadedRemoteProgress = true,
                hasLoadedWatchedItems = false,
                hasLoadedRemoteWatchedItems = true,
            ),
        )
        assertFalse(
            isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = false,
                hasLoadedRemoteProgress = true,
                hasLoadedWatchedItems = true,
                hasLoadedRemoteWatchedItems = false,
            ),
        )
        assertTrue(
            isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = false,
                hasLoadedRemoteProgress = true,
                hasLoadedWatchedItems = true,
                hasLoadedRemoteWatchedItems = true,
            ),
        )
        assertTrue(
            isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = true,
                hasLoadedRemoteProgress = true,
                hasLoadedWatchedItems = false,
                hasLoadedRemoteWatchedItems = false,
            ),
        )
    }

    @Test
    fun `home next up progressively merges live results with unprocessed cache`() {
        val cachedResolved = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "Next Up • S1E2 • Cached",
            seedEpisodeNumber = 1,
            imageUrl = "https://example.test/cached-show.jpg",
        )
        val cachedUnprocessed = continueWatchingItem(
            videoId = "deferred:1:2",
            subtitle = "Next Up • S1E2 • Deferred",
            seedEpisodeNumber = 1,
            imageUrl = "https://example.test/cached-deferred.jpg",
        )
        val liveResolved = continueWatchingItem(
            videoId = "show:1:3",
            subtitle = "Next Up • S1E3 • Live",
            seedEpisodeNumber = 1,
        )

        val result = mergeHomeNextUpItemsWithCache(
            resolvedItems = mapOf("show" to (300L to liveResolved)),
            cachedItems = mapOf(
                "show" to (100L to cachedResolved),
                "deferred" to (90L to cachedUnprocessed),
            ),
            conclusivelyProcessedContentIds = setOf("show"),
        )

        assertEquals(setOf("show", "deferred"), result.keys)
        assertEquals("show:1:3", result.getValue("show").second.videoId)
        assertEquals("https://example.test/cached-show.jpg", result.getValue("show").second.imageUrl)
        assertEquals("deferred:1:2", result.getValue("deferred").second.videoId)
    }

    @Test
    fun `home next up drops conclusive null while retaining transient cache`() {
        val conclusive = continueWatchingItem(
            videoId = "conclusive:1:2",
            subtitle = "Next Up • S1E2",
        )
        val transient = continueWatchingItem(
            videoId = "transient:1:2",
            subtitle = "Next Up • S1E2",
        )

        val result = mergeHomeNextUpItemsWithCache(
            resolvedItems = emptyMap(),
            cachedItems = mapOf(
                "conclusive" to (100L to conclusive),
                "transient" to (90L to transient),
            ),
            conclusivelyProcessedContentIds = setOf("conclusive"),
        )

        assertEquals(setOf("transient"), result.keys)
    }

    @Test
    fun `cached next up recalculates aired state from release timestamp`() {
        val released = "2026-07-12T00:00:00Z"
        val releaseEpochMs = requireNotNull(parseReleaseDateToEpochMs(released))

        assertTrue(
            cachedNextUpHasAired(
                cached = cachedNextUpItem(released = released, hasAired = false),
                nowEpochMs = releaseEpochMs + 1L,
            ),
        )
        assertFalse(
            cachedNextUpHasAired(
                cached = cachedNextUpItem(released = released, hasAired = true),
                nowEpochMs = releaseEpochMs - 1L,
            ),
        )
    }

    @Test
    fun `cached next up invalidates whenever the authoritative seed changes`() {
        assertTrue(
            hasHomeNextUpSeedChangedFromCache(
                currentSeason = 2,
                currentEpisode = 1,
                cachedSeason = 1,
                cachedEpisode = 10,
            ),
        )
        assertTrue(
            hasHomeNextUpSeedChangedFromCache(
                currentSeason = 1,
                currentEpisode = 11,
                cachedSeason = 1,
                cachedEpisode = 10,
            ),
        )
        assertTrue(
            hasHomeNextUpSeedChangedFromCache(
                currentSeason = 1,
                currentEpisode = 9,
                cachedSeason = 1,
                cachedEpisode = 10,
            ),
        )
        assertFalse(
            hasHomeNextUpSeedChangedFromCache(
                currentSeason = 1,
                currentEpisode = 10,
                cachedSeason = 1,
                cachedEpisode = 10,
            ),
        )
    }

    @Test
    fun `home next up does not treat placeholder cache as enriched metadata`() {
        val placeholder = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            imageUrl = " ",
            logo = null,
            episodeThumbnail = null,
        ).copy(
            title = "show",
            poster = "",
            background = "\t",
        )

        assertFalse(hasUsableHomeNextUpMetadata(placeholder))
    }

    @Test
    fun `home next up accepts cache with resolved title and artwork`() {
        val enriched = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            imageUrl = "https://example.test/show.jpg",
        ).copy(title = "Resolved Show")

        assertTrue(hasUsableHomeNextUpMetadata(enriched))
    }

    @Test
    fun `home next up combines fresh title with cached artwork before classifying metadata`() {
        val fresh = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
        ).copy(title = "Fresh Show")
        val cached = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            imageUrl = "https://example.test/cached-show.jpg",
        ).copy(title = "show")

        val decision = classifyHomeNextUpCandidateMetadata(
            freshItem = fresh,
            cachedFallbackItem = cached,
            dismissedNextUpKeys = emptySet(),
        )

        assertEquals(HomeNextUpCandidateMetadataOutcome.Ready, decision.outcome)
        assertEquals("Fresh Show", decision.item.title)
        assertEquals("https://example.test/cached-show.jpg", decision.item.imageUrl)
    }

    @Test
    fun `home next up combines fresh artwork with cached title before classifying metadata`() {
        val fresh = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            imageUrl = "https://example.test/fresh-show.jpg",
        ).copy(title = "show")
        val cached = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
        ).copy(title = "Cached Show")

        val decision = classifyHomeNextUpCandidateMetadata(
            freshItem = fresh,
            cachedFallbackItem = cached,
            dismissedNextUpKeys = emptySet(),
        )

        assertEquals(HomeNextUpCandidateMetadataOutcome.Ready, decision.outcome)
        assertEquals("Cached Show", decision.item.title)
        assertEquals("https://example.test/fresh-show.jpg", decision.item.imageUrl)
    }

    @Test
    fun `home next up does not count logo alone as card artwork`() {
        val logoOnly = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            logo = "https://example.test/show-logo.png",
        ).copy(title = "Resolved Show")

        assertFalse(hasUsableHomeNextUpMetadata(logoOnly))
    }

    @Test
    fun `dismissed placeholder next up is conclusive instead of transient`() {
        val placeholder = continueWatchingItem(
            videoId = "show:1:2",
            subtitle = "S1E2",
            seasonNumber = 1,
            episodeNumber = 2,
            seedSeasonNumber = 1,
            seedEpisodeNumber = 1,
        ).copy(title = "show")
        val dismissKey = nextUpDismissKey("show", 1, 1)

        val decision = classifyHomeNextUpCandidateMetadata(
            freshItem = placeholder,
            cachedFallbackItem = null,
            dismissedNextUpKeys = setOf(dismissKey),
        )

        assertFalse(hasUsableHomeNextUpMetadata(decision.item))
        assertEquals(HomeNextUpCandidateMetadataOutcome.Dismissed, decision.outcome)
    }

    private fun progressEntry(
        videoId: String,
        title: String,
        lastUpdatedEpochMs: Long,
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 4,
        episodeTitle: String? = "Episode",
        isCompleted: Boolean = false,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            parentMetaId = videoId.substringBefore(':'),
            parentMetaType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            videoId = videoId,
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            lastPositionMs = if (seasonNumber != null && episodeNumber != null) 120_000L else 60_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            isCompleted = isCompleted,
        )

    private fun continueWatchingItem(
        videoId: String,
        subtitle: String,
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 4,
        seedSeasonNumber: Int? = seasonNumber,
        seedEpisodeNumber: Int? = episodeNumber,
        imageUrl: String? = null,
        logo: String? = null,
        episodeThumbnail: String? = null,
    ): ContinueWatchingItem =
        ContinueWatchingItem(
            parentMetaId = videoId.substringBefore(':'),
            parentMetaType = "series",
            videoId = videoId,
            title = "Show",
            subtitle = subtitle,
            imageUrl = imageUrl,
            logo = logo,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = subtitle.substringAfterLast(" • ", "Episode"),
            episodeThumbnail = episodeThumbnail,
            isNextUp = true,
            nextUpSeedSeasonNumber = seedSeasonNumber,
            nextUpSeedEpisodeNumber = seedEpisodeNumber,
            resumePositionMs = 0L,
            durationMs = 0L,
            progressFraction = 0f,
        )

    private fun watchedItem(
        id: String,
        season: Int,
        episode: Int,
        markedAtEpochMs: Long,
    ): WatchedItem =
        WatchedItem(
            id = id,
            type = "series",
            name = "Show",
            season = season,
            episode = episode,
            markedAtEpochMs = markedAtEpochMs,
        )

    private fun cachedNextUpItem(
        released: String?,
        hasAired: Boolean,
    ): CachedNextUpItem =
        CachedNextUpItem(
            contentId = "show",
            contentType = "series",
            name = "Show",
            videoId = "show:1:2",
            season = 1,
            episode = 2,
            released = released,
            hasAired = hasAired,
            lastWatched = 1_000L,
            sortTimestamp = 1_000L,
            seedSeason = 1,
            seedEpisode = 1,
        )

    private fun completedSeriesCandidate(index: Int): CompletedSeriesCandidate =
        CompletedSeriesCandidate(
            content = WatchingContentRef(type = "series", id = "show-$index"),
            seasonNumber = 1,
            episodeNumber = index,
            markedAtEpochMs = 10_000L - index,
        )

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
