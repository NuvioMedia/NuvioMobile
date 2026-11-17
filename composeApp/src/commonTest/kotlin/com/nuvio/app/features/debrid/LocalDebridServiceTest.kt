package com.nuvio.app.features.debrid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDebridServiceTest {
    private val canonical = "7db44b3713094e622246b715de410bb9b6d91230"
    private val requested = "b72ca5570c56c2387526b6aab184f0cf0075cff0"

    @Test
    fun exactMatchesStayUntouchedWithoutExtraRequests() = runTest {
        val cached = mapOf("abc123" to LocalDebridCachedItem(name = "exact.mkv", size = 1L))

        val resolved = resolveTorboxAliasMatches(cached, listOf("abc123")) {
            error("no extra request expected")
        }

        assertEquals(cached, resolved)
    }

    @Test
    fun singleAliasedAnswerMapsBackToTheRequestedHash() = runTest {
        val item = LocalDebridCachedItem(name = "aliased.mkv", size = 2L)

        val resolved = resolveTorboxAliasMatches(mapOf(canonical to item), listOf(requested)) {
            error("no extra request expected")
        }

        assertEquals(mapOf(requested to item), resolved)
    }

    @Test
    fun onlyTheHashesMissingFromABatchAreAskedAgain() = runTest {
        val exact = LocalDebridCachedItem(name = "exact.mkv", size = 3L)
        val aliased = LocalDebridCachedItem(name = "aliased.mkv", size = 2L)
        val asked = mutableListOf<String>()

        val resolved = resolveTorboxAliasMatches(
            cached = mapOf("abc123" to exact, canonical to aliased),
            requestedHashes = listOf("abc123", requested),
        ) { hash ->
            asked += hash
            mapOf(canonical to aliased)
        }

        assertEquals(listOf(requested), asked)
        assertEquals(mapOf("abc123" to exact, canonical to aliased, requested to aliased), resolved)
    }
}
