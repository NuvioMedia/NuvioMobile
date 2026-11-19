package com.nuvio.app.features.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NewznabIndexerStatesTest {
    private class ClearableStorage : NewznabStateStorage {
        var value: String? = null
        override var generation = 0
        override fun load() = value
        override fun save(value: String) { this.value = value }
        fun clear() { value = null; generation += 1 }
    }

    @Test fun `an account reset drops the state already loaded in memory`() {
        val storage = ClearableStorage()
        val states = NewznabIndexerStates(storage) { 1_000L }
        val old = UsenetIndexer(name = "Old", apiUrl = "https://old.test/api", apiKey = "old-key")
        states.update(old) { it.copy(blockedUntil = 5_000L, apiHitsLeft = 3) }
        assertEquals(5_000L, states.get(old)?.blockedUntil)

        storage.clear()
        assertNull(states.get(old))
        // The next write must not bring the previous account's indexers back.
        val new = UsenetIndexer(name = "New", apiUrl = "https://new.test/api", apiKey = "new-key")
        states.update(new) { it.copy(apiHitsLeft = 9) }
        assertFalse(storage.value!!.contains("5000"))
        assertNull(states.get(old))
        assertEquals(9, states.get(new)?.apiHitsLeft)
    }
}
