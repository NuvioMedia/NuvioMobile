package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSyncPlatformTest {

    @Test
    fun lruCacheEvictsTheLeastRecentlyUsedEntry() {
        val cache = AutoSyncLruCache<String, Int>(maxEntries = 2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"]) // touch "a", so "b" is now the oldest
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(3, cache.remove("c"))
        assertNull(cache["c"])
    }

    @Test
    fun putIfAbsentKeepsTheFirstValueAndReturnsIt() {
        val map = mutableMapOf<Long, Long>()
        assertNull(map.autoSyncPutIfAbsent(1L, 10L))
        assertEquals(10L, map.autoSyncPutIfAbsent(1L, 20L))
        assertEquals(10L, map[1L])
    }

    @Test
    fun monotonicClockMovesForward() {
        val first = autoSyncNanoTime()
        val second = autoSyncNanoTime()
        assertEquals(true, second >= first)
        assertEquals(true, autoSyncElapsedRealtimeMs() >= 0L)
    }
}
