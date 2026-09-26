package com.nuvio.app.features.servers

import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerSyncTest {
    @AfterTest
    fun tearDown() = removeFakeServer()

    private fun synced(id: String, server: String, token: String = "token-$id") = SyncedServer(
        id = id,
        providerId = "fake",
        name = "Box",
        address = "https://fake.example",
        remoteServerId = server,
        remoteUserId = "user-1",
        userName = "viewer",
        token = token,
        libraries = listOf(SyncedLibrary("10", "Movies", "movie", selected = true)),
    )

    @Test
    fun firstMergeKeepsLocalIdsAndUnsyncedLocalServers() {
        val merged = mergeSyncedServers(
            local = listOf(synced("local-a", "s1", token = "old"), synced("local-c", "s3")),
            remote = listOf(synced("remote-a", "s1", token = "new"), synced("remote-b", "s2")),
            syncedKeys = null,
        )

        assertEquals(listOf("local-a", "remote-b", "local-c"), merged.map { it.id })
        assertEquals("new", merged.first().token)
    }

    @Test
    fun laterMergesDropServersRemovedElsewhere() {
        val local = listOf(synced("a", "s1"), synced("b", "s2"), synced("c", "s3"))
        val syncedKeys = setOf(synced("a", "s1").key, synced("b", "s2").key)

        val merged = mergeSyncedServers(local, remote = listOf(synced("a", "s1")), syncedKeys)

        assertEquals(listOf("a", "c"), merged.map { it.id })
    }

    @Test
    fun localChangesAreQueuedWithTheirTokens() {
        installFakeServer()

        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        assertTrue(snapshot.pendingPush)
        assertEquals("token", snapshot.servers.single().token)
        assertEquals(listOf("10", "20"), snapshot.servers.single().libraries.map { it.id })

        ServerRepository.markPushed(snapshot)
        val pushed = assertNotNull(ServerRepository.syncSnapshot(snapshot.profileId))
        assertFalse(pushed.pendingPush)
        assertEquals(setOf(snapshot.servers.single().key), pushed.syncedKeys)
    }

    @Test
    fun appliesRemoteServersWithoutQueueingAPush() {
        val connection = installFakeServer()
        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        val remote = snapshot.servers.single().copy(
            id = "remote-id",
            token = "shared",
            libraries = listOf(SyncedLibrary("10", "Movies", "movie", selected = false)),
        )

        assertTrue(ServerRepository.applySync(snapshot, listOf(remote), setOf(remote.key)))

        val updated = assertNotNull(ServerRepository.connection(connection.id))
        assertEquals("shared", ServerRepository.session(connection.id)?.token)
        assertFalse(updated.libraries.single().selected)
        assertFalse(assertNotNull(ServerRepository.syncSnapshot(snapshot.profileId)).pendingPush)
    }

    @Test
    fun skipsStaleRemoteAppliesAfterLocalEdits() {
        val connection = installFakeServer()
        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))

        ServerRepository.setEnabled(connection.id, false)

        assertFalse(ServerRepository.applySync(snapshot, emptyList(), emptySet()))
        assertNotNull(ServerRepository.connection(connection.id))
    }
}
