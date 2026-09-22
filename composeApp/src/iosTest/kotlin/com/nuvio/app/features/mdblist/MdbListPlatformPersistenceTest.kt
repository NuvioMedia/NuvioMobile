package com.nuvio.app.features.mdblist

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdbListPlatformPersistenceTest {
    @Test
    fun keychainUpdatesAndDeletesOnlyTheRequestedProfile() {
        val storage = PlatformMdbListAuthPersistence
        val first = 1_900_001
        val second = 1_900_002
        val originalFirst = storage.read(first)
        val originalSecond = storage.read(second)
        try {
            storage.write(first, "initial credential fixture")
            storage.write(second, "another profile fixture")
            assertEquals("initial credential fixture", storage.read(first))
            storage.write(first, "rotated credential fixture")
            assertEquals("rotated credential fixture", storage.read(first))
            assertEquals("another profile fixture", storage.read(second))
            storage.write(first, null)
            assertNull(storage.read(first))
            assertEquals("another profile fixture", storage.read(second))
        } finally {
            storage.write(first, originalFirst)
            storage.write(second, originalSecond)
        }
    }

    @Test
    fun nativeCachePersistsPerProfileAndRejectsStaleWrites() = runTest {
        val storage = PlatformMdbListSyncStorage
        val first = 1_900_001
        val second = 1_900_002
        val originalFirst = storage.load(first)
        val originalSecond = storage.load(second)
        try {
            storage.save(first, "first cached snapshot") {}
            storage.save(second, "second cached snapshot") {}
            expectMdbListFailure<IllegalStateException> {
                storage.save(first, "stale cached snapshot") { error("Profile changed") }
            }
            assertEquals("first cached snapshot", storage.load(first))
            storage.remove(first) {}
            assertNull(storage.load(first))
            assertEquals("second cached snapshot", storage.load(second))
        } finally {
            if (originalFirst == null) storage.remove(first) {} else storage.save(first, originalFirst) {}
            if (originalSecond == null) storage.remove(second) {} else storage.save(second, originalSecond) {}
        }
    }
}
