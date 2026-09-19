package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import com.nuvio.app.features.watchprogress.WatchProgressSourceSimklPlayback
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklProjectionsTest {
    @Test
    fun `library presentation uses status names without a provider prefix`() {
        assertEquals(
            listOf("Watching", "Plan to Watch", "On Hold", "Completed", "Dropped"),
            simklLibraryStatusDefinitions.map { definition -> definition.title },
        )
    }

    @Test
    fun `library projection exposes every populated status with attribution`() {
        val plan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
            slug = "terminator-3-rise-of-the-machines",
            addedAt = "2023-11-14T22:13:20Z",
        )
        val completed = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            id = 53434,
            imdb = "tt0068646",
        )
        val watching = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
        )

        val projection = SimklSyncSnapshot(entries = listOf(plan, completed, watching)).toSimklLibraryProjection()
        val watchingDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.WATCHING
        }
        val planDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.PLAN_TO_WATCH
        }
        val completedDefinition = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.COMPLETED
        }

        assertEquals(
            listOf(watchingDefinition.key, planDefinition.key, completedDefinition.key),
            projection.sections.map { section -> section.type },
        )
        assertEquals(3, projection.items.size)
        val item = projection.items.single { candidate -> candidate.id == "tt0181852" }
        assertEquals("tt0181852", item.id)
        assertEquals(setOf(planDefinition.key), item.listKeys)
        assertEquals("simkl", item.trackingProviderId)
        assertEquals("simkl:53536", item.trackingProviderItemId)
        assertEquals(
            "https://simkl.com/movies/53536/terminator-3-rise-of-the-machines",
            item.trackingSourceUrl,
        )
        assertTrue(item.poster.orEmpty().contains("simkl.in/posters/12/poster_m.webp"))
        assertFalse(item.poster.orEmpty().contains("_w.webp"))
        assertEquals(1_700_000_000_000L, item.savedAtEpochMs)
        assertEquals(
            setOf(completedDefinition.key),
            projection.items.single { candidate -> candidate.id == "tt0068646" }.listKeys,
        )
        assertEquals(
            setOf(watchingDefinition.key),
            projection.items.single { candidate -> candidate.id == "tt1520211" }.listKeys,
        )
        assertTrue(watchingDefinition.isMembershipDestination)
        assertTrue(planDefinition.isMembershipDestination)
        assertFalse(completedDefinition.isMembershipDestination)
    }

    @Test
    fun `watched projection includes movie events rich episodes and completed series marker`() {
        val movie = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            id = 53536,
            imdb = "tt0181852",
            lastWatchedAt = "2023-11-14T22:13:20Z",
        )
        val richShow = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(number = 1, watchedAt = "2023-11-14T23:13:20Z"),
                        SimklEpisode(number = 2, watchedAt = null),
                    ),
                ),
            ),
        )
        val summaryOnlyCompletedShow = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.COMPLETED,
            id = 39687,
            imdb = "tt2560140",
            lastWatchedAt = "2023-11-15T00:13:20Z",
        )

        val projection = SimklSyncSnapshot(
            entries = listOf(movie, richShow, summaryOnlyCompletedShow),
        ).toSimklWatchedProjection()

        assertEquals(3, projection.items.size)
        val movieEvent = assertNotNull(projection.items.singleOrNull { it.type == "movie" })
        assertEquals("simkl", movieEvent.trackingProviderId)
        assertEquals("simkl:53536", movieEvent.trackingProviderItemId)
        assertEquals("https://simkl.com/movies/53536", movieEvent.trackingSourceUrl)
        val episode = projection.items.single { it.season == 1 && it.episode == 1 }
        assertEquals("tt1520211", episode.id)
        assertFalse(projection.items.any { it.episode == 2 })
        assertTrue(projection.items.any { it.id == "tt2560140" && it.season == null })
        assertTrue(projection.fullyWatchedSeriesKeys.any { "tt2560140" in it })
    }

    @Test
    fun `summary counters do not fabricate exact episode markers`() {
        val summary = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            id = 2090,
            imdb = "tt1520211",
            lastWatchedAt = "2023-11-14T23:13:20Z",
        ).copy(
            lastWatched = "S01E03",
            nextToWatch = "S01E04",
            watchedEpisodesCount = 3,
            totalEpisodesCount = 6,
        )

        val projection = SimklSyncSnapshot(entries = listOf(summary)).toSimklWatchedProjection()

        assertTrue(projection.items.isEmpty())
        assertTrue(projection.fullyWatchedSeriesKeys.isEmpty())
    }

    @Test
    fun `anime watched projection prefers mapped tvdb coordinates`() {
        val anime = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            id = 439744,
            imdb = "tt2560140",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(
                            number = 4,
                            watchedAt = "2023-11-14T23:13:20Z",
                            tvdb = SimklEpisodeMapping(season = 2, episode = 4),
                        ),
                    ),
                ),
            ),
        )

        val watched = SimklSyncSnapshot(entries = listOf(anime))
            .toSimklWatchedProjection()
            .items
            .single()

        assertEquals(2, watched.season)
        assertEquals(4, watched.episode)
    }

    @Test
    fun `playback projection preserves Simkl session identity and percentage`() {
        val session = SimklPlaybackSession(
            id = 12345,
            progress = 42.2,
            pausedAt = "2024-04-30T22:13:00.250Z",
            type = "episode",
            episode = SimklPlaybackEpisode(
                season = 1,
                number = 3,
                title = "Chapter Three",
            ),
            show = media(id = 39687, imdb = "tt4574334", runtime = 50),
        )

        val entry = SimklSyncSnapshot(playback = listOf(session)).toSimklProgressEntries().single()

        assertEquals("tt4574334", entry.parentMetaId)
        assertEquals(1, entry.seasonNumber)
        assertEquals(3, entry.episodeNumber)
        assertEquals(42.2f, entry.progressPercent)
        // No duration is invented from the show runtime, so the resume goes through the percentage:
        // the player scales it by the duration of the episode it really opened.
        assertEquals(0L, entry.durationMs)
        assertEquals(0L, entry.lastPositionMs)
        assertEquals(0.422f, entry.progressFraction, 0.0005f)
        assertEquals("simkl-playback:12345", entry.progressKey)
        assertEquals(WatchProgressSourceSimklPlayback, entry.source)
        assertEquals("simkl", entry.trackingProviderId)
        assertEquals("simkl:39687", entry.trackingProviderItemId)
        assertEquals("https://simkl.com/tv/39687", entry.trackingSourceUrl)
        assertTrue(entry.poster.orEmpty().contains("simkl.in/posters/12/poster_m.webp"))
        assertFalse(entry.isCompleted)
        assertEquals(1_714_515_180_250L, entry.lastUpdatedEpochMs)
    }

    @Test
    fun `a paused playback above the account threshold is still a position to resume`() {
        val session = SimklPlaybackSession(
            id = 12345,
            progress = 81.0,
            pausedAt = "2024-04-30T22:13:00.250Z",
            type = "episode",
            episode = SimklPlaybackEpisode(
                season = 1,
                number = 3,
                title = "Chapter Three",
            ),
            show = media(id = 39687, imdb = "tt4574334", runtime = 50),
        )

        val entry = SimklSyncSnapshot(playback = listOf(session)).toSimklProgressEntries().single()

        // The app reports a playback the credits marker has not been reached in as a pause, and Simkl
        // keeps it here with the percentage it was given. The row stays a resume point, so Continue
        // Watching keeps showing where the user stopped instead of offering the next episode.
        assertFalse(entry.isCompleted)
        assertFalse(entry.isEffectivelyCompleted)
        assertEquals(81.0f, entry.progressPercent)
        assertEquals(0.81f, entry.progressFraction, 0.0005f)
    }

    @Test
    fun `a paused playback inside the credits is still a position to resume`() {
        val session = SimklPlaybackSession(
            id = 12345,
            progress = 96.0,
            pausedAt = "2024-04-30T22:13:00.250Z",
            type = "episode",
            episode = SimklPlaybackEpisode(
                season = 1,
                number = 3,
                title = "Chapter Three",
            ),
            show = media(id = 39687, imdb = "tt4574334", runtime = 50),
        )

        val entry = SimklSyncSnapshot(playback = listOf(session)).toSimklProgressEntries().single()

        // A high percentage alone is not a finished watch either: what ends a playback is the credits
        // marker, and a watch the account recorded reaches Continue Watching through the watched
        // history, which supersedes this row.
        assertFalse(entry.isCompleted)
        assertEquals(96.0f, entry.progressPercent)
    }

    @Test
    fun `media reference retains anime catalog and all accepted ids`() {
        val anime = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            id = 39687,
            imdb = "tt2560140",
            mal = 16498,
        )
        val snapshot = SimklSyncSnapshot(entries = listOf(anime))

        val reference = snapshot.mediaReference(
            contentId = "tt2560140",
            contentType = "series",
            season = 2,
            episode = 4,
            posterUrl = "https://catalog.example/anime.webp",
        )

        assertEquals(TrackingMediaKind.ANIME, reference.kind)
        assertEquals(39687L, reference.ids.simkl)
        assertEquals(16498L, reference.ids.mal)
        assertEquals(2, reference.episode?.season)
        assertEquals(4, reference.episode?.number)
        assertEquals("https://catalog.example/anime.webp", reference.posterUrl)
    }

    @Test
    fun `clean plan to watch removal needs no destructive confirmation`() {
        val plan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
        )

        val confirmation = SimklSyncSnapshot(entries = listOf(plan))
            .membershipRemovalConfirmation("tt0181852")

        assertNull(confirmation)
    }

    @Test
    fun `watched or rated Simkl removal requires destructive confirmation`() {
        val watchedPlan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53536,
            imdb = "tt0181852",
            lastWatchedAt = "2023-11-14T22:13:20Z",
        )
        val ratedPlan = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 53434,
            imdb = "tt0068646",
        ).copy(userRating = 9)
        val episodePlan = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.PLAN_TO_WATCH,
            id = 2090,
            imdb = "tt1520211",
            seasons = listOf(
                SimklSeason(
                    number = 1,
                    episodes = listOf(
                        SimklEpisode(number = 1, watchedAt = "2023-11-14T22:13:20Z"),
                    ),
                ),
            ),
        )

        val confirmation = assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt0181852"),
        )
        assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt0068646"),
        )
        assertNotNull(
            SimklSyncSnapshot(entries = listOf(watchedPlan, ratedPlan, episodePlan))
                .membershipRemovalConfirmation("tt1520211"),
        )

        assertEquals(
            setOf(
                TrackingMembershipRemovalImpact.WATCHED_HISTORY,
                TrackingMembershipRemovalImpact.RATING,
            ),
            confirmation.impacts,
        )
    }

    @Test
    fun `Simkl default membership toggles only its mutually exclusive status`() {
        val planKey = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.PLAN_TO_WATCH
        }.key
        val watchingKey = simklLibraryStatusDefinitions.single { definition ->
            definition.status == SimklListStatus.WATCHING
        }.key
        val emptyMembership = simklLibraryStatusDefinitions.associate { definition ->
            definition.key to false
        }

        val added = SimklTrackingLibraryProvider.toggledDefaultMembership(emptyMembership)
        val removed = SimklTrackingLibraryProvider.toggledDefaultMembership(
            emptyMembership + (watchingKey to true),
        )

        assertTrue(added[planKey] == true)
        assertTrue(added.filterKeys { key -> key != planKey }.values.none { it })
        assertTrue(removed.values.none { it })
    }

    @Test
    fun `timestamp parser accepts UTC fractions and rejects invalid calendar values`() {
        assertEquals(0L, parseSimklUtcEpochMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_123L, parseSimklUtcEpochMs("2000-02-29T00:00:00.123Z"))
        assertNull(parseSimklUtcEpochMs("2023-02-29T00:00:00Z"))
        assertNull(parseSimklUtcEpochMs("2024-01-01T00:00:00+01:00"))
    }

    private fun entry(
        type: SimklMediaType,
        status: SimklListStatus,
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        slug: String? = null,
        addedAt: String? = null,
        lastWatchedAt: String? = null,
        seasons: List<SimklSeason> = emptyList(),
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = type,
        status = status,
        addedToWatchlistAt = addedAt,
        lastWatchedAt = lastWatchedAt,
        seasons = seasons,
        movie = if (type == SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null,
        show = if (type != SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null,
    )

    private fun media(
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        runtime: Int? = null,
        slug: String? = null,
    ): SimklMedia = SimklMedia(
        title = "Title $id",
        poster = "12/poster",
        year = 2020,
        runtime = runtime,
        ids = buildJsonObject {
            put("simkl", id)
            imdb?.let { put("imdb", it) }
            mal?.let { put("mal", it) }
            slug?.let { put("slug", it) }
        },
    )
}
