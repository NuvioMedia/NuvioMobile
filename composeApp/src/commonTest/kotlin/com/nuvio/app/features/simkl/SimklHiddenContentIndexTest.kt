package com.nuvio.app.features.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimklHiddenContentIndexTest {
    @Test
    fun `hidden index preserves scan matching for every status and external id alias`() {
        val media = SimklMedia(
            ids = buildJsonObject {
                put("imdb", "tt1234567")
                put("tmdb", "00123")
                put("tvdb", "00SeriesI")
                put("simkl_id", "00456")
                put("mal", "00789")
                put("anidb", "00101")
                put("anilist", "00202")
                put("kitsu", "00303")
            },
        )
        val matchingIds = listOf(
            "tt1234567", "TT1234567", " tt1234567:1:2 ", "IMDB:TT1234567:1:2",
            "tmdb:123", "TMDB:000123:1:2", "tmdb:+123",
            "tvdb:00SeriesI", "TVDB:00seriesı:1:2", "tvdb:00seriesİ",
            "simkl:456", "SIMKL:000456:1:2", "simkl:+456",
            "mal:789", "MAL:000789:3", "anidb:101", "ANIDB:00101:3",
            "anilist:202", "ANILIST:00202:3", "kitsu:303", "KITSU:00303:3",
        )
        val unmatchedIds = listOf(
            "", "123", "456", "trakt:456", "tt7654321", "imdb:1234567",
            "tmdb:456", "tvdb:SeriesI", "mal:303", "kitsu:789", "simkl:invalid",
        )
        val statuses: List<SimklListStatus?> = SimklListStatus.entries + listOf(null)
        for (status in statuses) {
            val snapshot = SimklSyncSnapshot(
                entries = listOf(
                    SimklLibraryEntry(mediaType = SimklMediaType.ANIME, status = status, show = media),
                    SimklLibraryEntry(status = SimklListStatus.DROPPED),
                ),
            )
            val hidden = status == SimklListStatus.ON_HOLD || status == SimklListStatus.DROPPED
            for (contentId in matchingIds + unmatchedIds) {
                val scanned = snapshot.entries.any { entry ->
                    entry.status.hidesContinueWatching() && entry.matchesContentId(contentId)
                }
                assertEquals(scanned, snapshot.isHiddenFromContinueWatching(contentId), "$status: $contentId")
            }
            matchingIds.forEach { contentId ->
                assertEquals(hidden, snapshot.isHiddenFromContinueWatching(contentId), "$status: $contentId")
            }
            unmatchedIds.forEach { contentId ->
                assertFalse(snapshot.isHiddenFromContinueWatching(contentId), "$status: $contentId")
            }
        }
    }

    @Test
    fun `canonical ids retain exact matching when their external value cannot be parsed`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    status = SimklListStatus.DROPPED,
                    show = SimklMedia(ids = buildJsonObject { put("tmdb", "invalid-id") }),
                ),
                SimklLibraryEntry(
                    status = SimklListStatus.ON_HOLD,
                    show = SimklMedia(ids = buildJsonObject { put("imdb", "tt7654321:custom") }),
                ),
            ),
        )
        val contentIds = listOf(
            "tmdb:invalid-id", "TMDB:INVALID-ID", "tmdb:invalid-id:1:2", " tmdb:invalid-id ",
            "tt7654321:custom", "TT7654321:CUSTOM", "imdb:tt7654321:custom", "tt7654321",
        )

        contentIds.forEach { contentId ->
            val scanned = snapshot.entries.any { entry ->
                entry.status.hidesContinueWatching() && entry.matchesContentId(contentId)
            }
            assertEquals(scanned, snapshot.isHiddenFromContinueWatching(contentId), contentId)
        }
        assertTrue(snapshot.isHiddenFromContinueWatching("TMDB:INVALID-ID"))
        assertFalse(snapshot.isHiddenFromContinueWatching("imdb:tt7654321:custom"))
    }

    @Test
    fun `new snapshots replace hidden status and profile identities`() {
        val dropped = SimklSyncSnapshot(entries = listOf(hiddenEntry("tt1234567")))
        assertTrue(dropped.isHiddenFromContinueWatching("tt1234567"))
        val cachedIds = dropped.hiddenFromContinueWatchingContentIds()
        assertSame(cachedIds, dropped.hiddenFromContinueWatchingContentIds())

        val watching = dropped.copy(entries = dropped.entries.map { it.copy(status = SimklListStatus.WATCHING) })
        assertFalse(watching.isHiddenFromContinueWatching("tt1234567"))
        assertTrue(watching.hiddenFromContinueWatchingContentIds().isEmpty())

        val onHold = watching.copy(entries = watching.entries.map { it.copy(status = SimklListStatus.ON_HOLD) })
        assertTrue(onHold.isHiddenFromContinueWatching("tt1234567"))

        val otherProfile = SimklSyncSnapshot(entries = listOf(hiddenEntry("tt7654321")))
        assertFalse(otherProfile.isHiddenFromContinueWatching("tt1234567"))
        assertTrue(otherProfile.isHiddenFromContinueWatching("tt7654321"))
        assertFalse(SimklSyncSnapshot().isHiddenFromContinueWatching("tt7654321"))
    }

    @Test
    fun `repeated hidden checks do not revisit the library entries`() {
        val source = (1..2_000).map { hiddenEntry("tt$it") }
        var entryReads = 0
        val entries = object : AbstractList<SimklLibraryEntry>() {
            override val size: Int get() = source.size

            override fun get(index: Int): SimklLibraryEntry {
                entryReads++
                return source[index]
            }
        }
        val snapshot = SimklSyncSnapshot(entries = entries)
        val initialIds = snapshot.hiddenFromContinueWatchingContentIds()
        val initialReads = entryReads

        for (id in 1..2_000) {
            assertTrue(snapshot.isHiddenFromContinueWatching("IMDB:TT$id:1:2"))
            assertFalse(snapshot.isHiddenFromContinueWatching("mal:$id"))
        }
        assertSame(initialIds, snapshot.hiddenFromContinueWatchingContentIds())
        assertEquals(2_000, initialReads)
        assertEquals(initialReads, entryReads)

        assertTrue(snapshot.copy().isHiddenFromContinueWatching("tt1"))
        assertEquals(initialReads * 2, entryReads)
    }

    private fun hiddenEntry(contentId: String): SimklLibraryEntry = SimklLibraryEntry(
        status = SimklListStatus.DROPPED,
        show = SimklMedia(ids = buildJsonObject { put("imdb", contentId) }),
    )
}
