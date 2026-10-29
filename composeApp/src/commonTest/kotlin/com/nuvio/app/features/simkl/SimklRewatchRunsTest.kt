package com.nuvio.app.features.simkl

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.tracking.RewatchRunPosition
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the account's rewatch sessions are read as: which session is read, which episodes form a run,
 * and when the account is not read at all.
 */
class SimklRewatchRunsTest {

    @Test
    fun `two consecutive rewatched episodes are a run`() {
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 7, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals("tt5753856", run.contentId)
        assertEquals(2, run.seasonNumber)
        assertEquals(7, run.episodeNumber)
        assertEquals(parseZonedIsoDateTimeToEpochMs(NEWER)!!, run.markedAtEpochMs)
        assertTrue(run.matchKeys.contains("imdb:tt5753856"))
        assertTrue(run.matchKeys.contains("simkl:39687"))
        assertTrue(run.matches("simkl:39687"))
        assertTrue(run.matches("TT5753856"))
        assertFalse(run.matches("tmdb:1"))
        assertFalse(run.matches(null))
    }

    @Test
    fun `a gap does not join the chain, and the newest episode still runs`() {
        // A skipped episode is its own chain, and the run is the chain the newest episode is in: a
        // rewatch the user is in is always offered, the row does not wait for two in a row.
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 9, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals(2, run.seasonNumber)
        assertEquals(9, run.episodeNumber)
    }

    @Test
    fun `a season boundary breaks the chain, and the newest episode still runs`() {
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(Marked(season = 1, episode = 5), Marked(season = 2, episode = 1, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals(2, run.seasonNumber)
        assertEquals(1, run.episodeNumber)
    }

    @Test
    fun `the newest rewatched episode decides which chain is the run`() {
        // An old pair from season 1 and a single, recent episode from season 3: the run follows the
        // recent episode even though the other chain is longer.
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(
                Marked(season = 1, episode = 1),
                Marked(season = 1, episode = 2),
                Marked(season = 3, episode = 4, watchedAt = NEWER),
            ),
        )

        val run = runs.single()
        assertEquals(3, run.seasonNumber)
        assertEquals(4, run.episodeNumber)
    }

    @Test
    fun `the last episode of the run is where the run stands`() {
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(
                Marked(season = 2, episode = 6, watchedAt = OLD),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertEquals(7, runs.single().episodeNumber)
        assertEquals(parseZonedIsoDateTimeToEpochMs(NEWER)!!, runs.single().markedAtEpochMs)
    }

    @Test
    fun `several running rows of the same series are read together`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 1)), isRewatch = true),
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 2, watchedAt = NEWER)), isRewatch = true),
        )

        val runs = deriveSimklRewatchRuns(
            entries = rows,
            animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
            offerRuns = true,
        )

        assertEquals(1, runs.size)
        assertEquals(2, runs.single().episodeNumber)
    }

    @Test
    fun `a session the account closed is not the run`() {
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(
                    Marked(season = 1, episode = 1),
                    Marked(season = 1, episode = 2, watchedAt = NEWER),
                ),
                isRewatch = true,
                status = "closed",
            ),
        )

        assertTrue(
            deriveSimklRewatchRuns(
                entries = rows,
                animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
                offerRuns = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `the running session decides, a closed one beside it is not read`() {
        // Simkl closes the session and opens a fresh one when the same episode is rewatched days later,
        // so the older row holds more episodes than the one the user is actually in. The row follows the
        // running session, never the longer chain of the closed one.
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(
                    Marked(season = 1, episode = 1),
                    Marked(season = 1, episode = 2),
                    Marked(season = 1, episode = 3),
                    Marked(season = 1, episode = 4),
                    Marked(season = 1, episode = 5, watchedAt = OLD),
                ),
                isRewatch = true,
                status = "closed",
            ),
            rewatchRow(
                rewatched = listOf(Marked(season = 1, episode = 1, watchedAt = NEWER)),
                isRewatch = true,
                status = "active",
            ),
        )

        val run = deriveSimklRewatchRuns(
            entries = rows,
            animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
            offerRuns = true,
        ).single()

        assertEquals(1, run.seasonNumber)
        assertEquals(1, run.episodeNumber)
        assertEquals(parseZonedIsoDateTimeToEpochMs(NEWER)!!, run.markedAtEpochMs)
    }

    @Test
    fun `rewatches are not read while the user keeps them off`() {
        val runs = runsFor(
            offerRuns = false,
            rewatched = listOf(
                Marked(season = 2, episode = 5),
                Marked(season = 2, episode = 6),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a canonical row is not a rewatch`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = false),
        )

        assertTrue(
            deriveSimklRewatchRuns(
                entries = rows,
                animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
                offerRuns = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `only the running rewatch sessions are kept from a read`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 1)), isRewatch = true),
            // A session the user closed is a run they left, so it is not kept.
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 2)), isRewatch = true, status = "closed"),
            // And a canonical row is not a session either.
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 3)), isRewatch = false),
        )

        val kept = rows.runningRewatchSessions()

        assertEquals(1, kept.size)
        assertEquals(1, kept.single().seasons.single().episodes.single().number)
    }

    @Test
    fun `the running session tells which episodes a run has rewatched`() {
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 7, watchedAt = NEWER)),
                isRewatch = true,
            ),
            // A session the user closed is not the run and marks nothing: its episode is not in the set.
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 5)), isRewatch = true, status = "closed"),
            // And a canonical row is not a session either.
            rewatchRow(rewatched = listOf(Marked(season = 3, episode = 1)), isRewatch = false),
        )
        val contentId = rows.firstNotNullOf { row -> row.media?.canonicalContentId() }

        assertEquals(setOf(2 to 6, 2 to 7), rows.rewatchedEpisodesOf(contentId))
        // Another item has nothing of its own here.
        assertTrue(rows.rewatchedEpisodesOf("imdb:tt0000000").isEmpty())
    }

    @Test
    fun `the running session of the item is the one a write joins`() {
        val sessions = listOf(
            session(id = 41, status = "completed"),
            session(id = 42, status = "closed"),
            session(id = 43, status = " ACTIVE "),
        )

        // Only the active one is pinned: a finished or closed run is not what a new viewing joins.
        assertEquals(43L, sessions.activeRewatchSessionId(episode()))
    }

    @Test
    fun `a session that is not running, not this item or not a session is never joined`() {
        val target = episode()

        // Nothing is running, so the write goes without an id and the account opens the session.
        assertNull(listOf(session(id = 44, status = "closed")).activeRewatchSessionId(target))
        assertNull(listOf(session(id = 45, status = null)).activeRewatchSessionId(target))
        // A session of another show belongs to that show.
        assertNull(
            listOf(session(id = 46, status = "active", ids = mapOf("imdb" to jsonValue("tt0000001"))))
                .activeRewatchSessionId(target)
        )
        // A canonical row carries no session, whatever else it holds.
        assertNull(
            listOf(rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = false))
                .activeRewatchSessionId(target)
        )
    }

    @Test
    fun `a dated read replaces the rows of the items it mentions and keeps the rest`() {
        val storedDark = session(id = 1, status = "active")
        val storedOther = session(id = 2, status = "active", ids = otherIds)
        val readDarkCanonical = rewatchRow(rewatched = listOf(Marked(season = 2, episode = 7)), isRewatch = false)
        val readDarkClosed = session(id = 1, status = "closed")
        val readNewSession = session(id = 3, status = "active", ids = newIds)

        val merged = mergeRewatchSessions(
            read = listOf(readDarkCanonical, readDarkClosed, readNewSession),
            stored = listOf(storedDark, storedOther),
        )

        // Dark changed (the session closed), the new show joined, and the untouched item held on.
        assertEquals(listOf(3L, 2L), merged.mapNotNull(SimklLibraryEntry::rewatchId))
        assertEquals("shows:simkl:39688", merged.first().stableKey())
    }

    @Test
    fun `a delta that mentions an item without a running session drops the stored session`() {
        val merged = mergeRewatchSessions(
            read = listOf(rewatchRow(rewatched = listOf(Marked(season = 2, episode = 7)), isRewatch = false)),
            stored = listOf(session(id = 1, status = "active")),
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `a read that mentions every item replaces the whole stored set`() {
        val merged = mergeRewatchSessions(
            read = listOf(
                session(id = 1, status = "active"),
                rewatchRow(rewatched = listOf(Marked(season = 2, episode = 7)), isRewatch = false, ids = otherIds),
            ),
            stored = listOf(
                session(id = 1, status = "active"),
                session(id = 2, status = "active", ids = otherIds),
            ),
        )

        assertEquals(listOf(1L), merged.mapNotNull(SimklLibraryEntry::rewatchId))
    }

    /** A second show, and a third one, so a read can mention items the stored set does not. */
    private val otherIds: Map<String, JsonElement>
        get() = mapOf("simkl" to jsonValue("39690"), "imdb" to jsonValue("tt039690"))

    private val newIds: Map<String, JsonElement>
        get() = mapOf("simkl" to jsonValue("39688"), "imdb" to jsonValue("tt039688"))

    private val darkIds: Map<String, JsonElement> = mapOf(
        "simkl" to jsonValue("39687"),
        "imdb" to jsonValue("tt5753856"),
    )

    @Test
    fun `only a title the account has watched reads as a possible repeat`() {
        val watchedByStatus = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            status = SimklListStatus.COMPLETED,
            show = SimklMedia(title = "Dark", year = 2017, ids = darkIds),
        )
        val watchedByEpisode = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            show = SimklMedia(title = "Dark", year = 2017, ids = darkIds),
            seasons = listOf(
                SimklSeason(number = 2, episodes = listOf(SimklEpisode(number = 7, watchedAt = OLD))),
            ),
        )
        val watchedOnlyByTheRowDate = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            lastWatchedAt = OLD,
            show = SimklMedia(title = "Dark", year = 2017, ids = darkIds),
        )
        val neverWatched = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            show = SimklMedia(title = "Dark", year = 2017, ids = darkIds),
        )
        val anotherShow = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            status = SimklListStatus.COMPLETED,
            show = SimklMedia(
                title = "Something else",
                year = 2001,
                ids = mapOf("imdb" to jsonValue("tt0000001")),
            ),
        )

        assertTrue(listOf(watchedByStatus).hasWatchedTitle(episode()))
        assertTrue(listOf(watchedByEpisode).hasWatchedTitle(episode()))
        assertTrue(listOf(watchedOnlyByTheRowDate).hasWatchedTitle(episode()))
        assertFalse(listOf(neverWatched).hasWatchedTitle(episode()))
        assertFalse(listOf(anotherShow).hasWatchedTitle(episode()))
    }

    private fun runsFor(
        offerRuns: Boolean,
        rewatched: List<Marked>,
    ): List<RewatchRunPosition> = deriveSimklRewatchRuns(
        entries = listOf(rewatchRow(rewatched = rewatched, isRewatch = true)),
        animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
        offerRuns = offerRuns,
    )

    private fun rewatchRow(
        rewatched: List<Marked>,
        isRewatch: Boolean,
        status: String? = "active",
        ids: Map<String, JsonElement> = mapOf("simkl" to jsonValue("39687"), "imdb" to jsonValue("tt5753856")),
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.COMPLETED,
        lastWatchedAt = rewatched.maxByOrNull(Marked::watchedAt)?.watchedAt,
        show = SimklMedia(
            title = "Dark",
            year = 2017,
            ids = ids,
        ),
        seasons = rewatched
            .groupBy(Marked::season)
            .map { (season, episodes) ->
                SimklSeason(
                    number = season,
                    episodes = episodes.map { marked ->
                        SimklEpisode(number = marked.episode, watchedAt = marked.watchedAt)
                    },
                )
            },
        isRewatch = isRewatch,
        rewatchId = if (isRewatch) 1L else null,
        rewatchStatus = status.takeIf { isRewatch },
    )

    /** One rewatch session of the account, as the read of it returns the row. */
    private fun session(
        id: Long?,
        status: String?,
        ids: Map<String, JsonElement> = mapOf(
            "simkl" to jsonValue("39687"),
            "imdb" to jsonValue("tt5753856"),
        ),
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.COMPLETED,
        lastWatchedAt = OLD,
        show = SimklMedia(title = "Dark", year = 2017, ids = ids),
        seasons = listOf(
            SimklSeason(
                number = 2,
                episodes = listOf(SimklEpisode(number = 7, watchedAt = OLD)),
            ),
        ),
        isRewatch = true,
        rewatchId = id,
        rewatchStatus = status,
    )

    /** The item the sessions are looked for: the same show, at the episode the sessions hold. */
    private fun episode() = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856"),
        episode = TrackingEpisode(season = 2, number = 7),
    )

    private fun jsonValue(value: String): JsonElement = Json.parseToJsonElement("\"$value\"")

    private data class Marked(
        val season: Int,
        val episode: Int,
        val watchedAt: String = OLD,
    )

    private companion object {
        const val OLD = "2026-03-01T20:00:00Z"
        const val NEWER = "2026-08-01T20:00:00Z"
    }
}
