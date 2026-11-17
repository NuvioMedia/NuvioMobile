package com.nuvio.app.features.servers

import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerItemRefTest {
    @Test
    fun roundTripsOpaqueIds() {
        listOf("5f1c0d2e9a8b4c3d", "12345", "a:b%c", "movie/42").forEach { itemId ->
            val ref = ServerItemRef("c1a2b3", itemId)
            assertEquals(ref, ServerItemRef.parse(ref.encode()))
        }
    }

    @Test
    fun equalItemIdsOnDifferentConnectionsDoNotCollide() {
        val first = ServerItemRef("c111", "42").encode()
        val second = ServerItemRef("c222", "42").encode()
        assertNotEquals(first, second)
    }

    @Test
    fun ignoresNonServerIds() {
        assertNull(ServerItemRef.parse("tt0111161"))
        assertNull(ServerItemRef.parse("tmdb:550"))
        assertNull(ServerItemRef.parse("srv1:"))
        assertNull(ServerItemRef.parse("srv1:c1:"))
        assertFalse(ServerItemRef.isServerId("tt0111161"))
        assertTrue(ServerItemRef.isServerId(ServerItemRef("c1", "x").encode()))
    }

    @Test
    fun encodedIdsCarryNoExternalIdentity() {
        val encoded = ServerItemRef("c1", "12345").encode()
        assertFalse(parseTrackingExternalIds(encoded).hasAny)
    }

    @Test
    fun encodedEpisodeIdsDoNotParseAsSeasonAndEpisode() {
        val parts = ServerItemRef("c9f", "7").encode().split(":")
        assertNull(parts[parts.size - 2].toIntOrNull())
    }
}
