package com.nuvio.app.features.simkl

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A show-level mark makes Simkl mark every episode of a series watched, so nothing but a film may
 * leave the app without episode coordinates.
 */
class SimklHistoryPushGuardTest {
    private fun mark(
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ) = WatchedItem(
        id = "tt2861424",
        type = type,
        name = "Rick and Morty",
        season = season,
        episode = episode,
        markedAtEpochMs = 1_700_000_000_000,
    )

    @Test
    fun `a mark without an episode never reaches Simkl for a series`() {
        assertTrue(simklHistoryPushItems(listOf(mark("series"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("show"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("tv"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("anime"))).isEmpty())
        // An unexpected type must fail safe as well: dropping a suspicious mark costs nothing,
        // pushing it can wipe a whole series.
        assertTrue(simklHistoryPushItems(listOf(mark("documentary-series"))).isEmpty())
    }

    @Test
    fun `episode marks always reach Simkl`() {
        val pushed = simklHistoryPushItems(listOf(mark("series", season = 1, episode = 1)))
        assertEquals(1, pushed.size)
        assertEquals(1, pushed.single().season)
        assertEquals(1, pushed.single().episode)
    }

    @Test
    fun `films need no episode`() {
        assertEquals(1, simklHistoryPushItems(listOf(mark("movie"))).size)
        assertEquals(1, simklHistoryPushItems(listOf(mark("film"))).size)
        assertEquals(1, simklHistoryPushItems(listOf(mark("Movie"))).size)
    }

    @Test
    fun `the episodes of a whole-series action still travel`() {
        val pushed = simklHistoryPushItems(
            listOf(
                mark("series"),
                mark("series", season = 1, episode = 1),
                mark("series", season = 9, episode = 10),
            ),
        )

        assertEquals(listOf(1 to 1, 9 to 10), pushed.map { it.season to it.episode })
    }

    @Test
    fun `a mixed push keeps the films and the episodes`() {
        val pushed = simklHistoryPushItems(
            listOf(
                mark("movie"),
                mark("series"),
                mark("series", season = 2, episode = 6),
            ),
        )

        assertEquals(2, pushed.size)
        assertTrue(pushed.any { it.type == "movie" })
        assertTrue(pushed.any { it.season == 2 && it.episode == 6 })
    }
}
