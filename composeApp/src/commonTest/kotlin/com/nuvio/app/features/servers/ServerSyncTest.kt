package com.nuvio.app.features.servers

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun payloadIncludesEveryFieldEvenWhenDefault() {
        val payload = listOf(synced("a", "s1")).toSyncPayload().jsonArray.single().jsonObject

        assertEquals(
            setOf(
                "id", "provider_id", "name", "address", "remote_server_id", "remote_user_id",
                "user_name", "token", "libraries", "enabled", "use_catalog_metadata", "import_watch_state",
            ),
            payload.keys,
        )
    }

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
    fun keepsLocalImportSettingWhenRemoteHasNone() {
        val merged = mergeSyncedServers(
            local = listOf(synced("local-a", "s1").copy(importWatchState = true)),
            remote = listOf(synced("remote-a", "s1"), synced("remote-b", "s2").copy(importWatchState = false)),
            syncedKeys = null,
        )

        assertEquals(listOf(true, false), merged.map { it.importWatchState })
        assertFalse(merged.last().toConnection("c", "k").importWatchState)
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
    fun syncsMixedLibraries() {
        val mixed = ServerLibrary("90", "Everything", ServerMediaKind.MIXED)
        val connection = installFakeServer(libraries = listOf(mixed))

        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        assertEquals(listOf(SyncedLibrary("90", "Everything", "mixed", selected = true)), snapshot.servers.single().libraries)

        val remote = snapshot.servers.single().copy(libraries = listOf(SyncedLibrary("90", "Everything", "mixed", selected = false)))
        assertTrue(ServerRepository.applySync(snapshot, listOf(remote), setOf(remote.key)))

        assertEquals(listOf(mixed.copy(selected = false)), assertNotNull(ServerRepository.connection(connection.id)).libraries)
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
