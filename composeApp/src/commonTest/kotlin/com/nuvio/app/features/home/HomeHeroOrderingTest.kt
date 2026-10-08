package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroOrderingTest {

    @Test
    fun `ordering is deterministic for a given seed`() {
        val items = listOf("series:a", "movie:b", "series:c", "movie:d", "series:e")

        assertEquals(
            items.orderedForHero(seed = 42) { it },
            items.orderedForHero(seed = 42) { it },
        )
    }

    @Test
    fun `ordering does not depend on the input order`() {
        val seed = 7
        val ascending = listOf("a", "b", "c", "d", "e").orderedForHero(seed) { it }
        val descending = listOf("e", "d", "c", "b", "a").orderedForHero(seed) { it }

        assertEquals(ascending, descending)
    }

    @Test
    fun `adding more items keeps the relative order of already-loaded items`() {
        // Regression guard for the hero carousel reshuffle bug: the hero pool is rebuilt as more
        // catalog batches load, so the ordering must keep already-shown items in place instead of
        // re-permuting the whole carousel. The previous Random.shuffled() implementation failed
        // this because shuffling lists of different sizes produces unrelated orders.
        val seed = 123
        val firstBatch = listOf("s1", "s2", "s3", "s4")
        val grownPool = firstBatch + listOf("s5", "s6", "s7", "s8", "s9")

        val firstBatchOrder = firstBatch.orderedForHero(seed) { it }
        val grownOrderFilteredToFirstBatch = grownPool
            .orderedForHero(seed) { it }
            .filter { it in firstBatch }

        assertEquals(firstBatchOrder, grownOrderFilteredToFirstBatch)
    }

    @Test
    fun `ordering is shuffled rather than sorted alphabetically`() {
        val items = (1..20).map { "series:tt$it" }
        val ordered = items.orderedForHero(seed = 99) { it }

        assertTrue(ordered != items.sortedBy { it }, "hero order should not be a plain sort")
        assertEquals(items.toSet(), ordered.toSet(), "ordering must not drop or add items")
    }
}
