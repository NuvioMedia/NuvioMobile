package com.nuvio.app.features.audiosync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedReferenceCacheTest {
    private val dialogue = listOf(Triple(1_000L, 2_000L, "hello"))

    @AfterTest
    fun clear() = SharedReferenceCache.clearForTest()

    @Test
    fun aDownloadedReferenceIsKeptForTheNextPlayer() {
        SharedReferenceCache.put("https://subs.example/en.srt", dialogue)
        assertEquals(dialogue, SharedReferenceCache.get("https://subs.example/en.srt"))
    }

    @Test
    fun aFailedDownloadIsNotKept() {
        // An empty list marks a failure: the next player tries again.
        SharedReferenceCache.put("https://subs.example/broken.srt", emptyList())
        assertNull(SharedReferenceCache.get("https://subs.example/broken.srt"))
    }

    @Test
    fun theLeastRecentlyUsedGoFirst() {
        SharedReferenceCache.put("first", dialogue)
        repeat(7) { SharedReferenceCache.put("other$it", dialogue) }
        assertNotNull(SharedReferenceCache.get("first"))
        SharedReferenceCache.put("one more", dialogue)
        assertNotNull(SharedReferenceCache.get("first"), "used recently: kept")
        assertNull(SharedReferenceCache.get("other0"), "least recently used: dropped")
    }
}
