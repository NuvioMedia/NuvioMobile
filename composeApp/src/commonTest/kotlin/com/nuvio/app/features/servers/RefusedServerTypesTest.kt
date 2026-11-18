package com.nuvio.app.features.servers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefusedServerTypesTest {
    private var clock = 1_000L
    private val refused = RefusedServerTypes(now = { clock })

    private fun server(provider: String, id: String) = SyncedServer(
        id = id,
        providerId = provider,
        name = "Box",
        address = "https://$id.example",
        remoteServerId = "server-$id",
        remoteUserId = "user",
        userName = "viewer",
        token = "token-$id",
    )

    private fun snapshot(vararg servers: SyncedServer) =
        ServerSyncSnapshot(profileId = 2, version = 7, servers = servers.toList(), pendingPush = true, syncedKeys = null)

    private val jellyfin = server("jellyfin", "a")
    private val plex = server("plex", "b")
    private val silo = server("silo", "c")

    private fun backendRefusing(
        vararg providers: String,
        sent: MutableList<List<String>>,
    ): suspend (ServerSyncSnapshot) -> Unit = { upload ->
        sent += upload.servers.map { it.providerId }
        upload.servers.firstOrNull { it.providerId in providers }?.let {
            throw RuntimeException("Unsupported media server provider: ${it.providerId}\nURL: https://api.example")
        }
    }

    @Test
    fun anAcceptedListIsSentOnce() = runTest {
        val sent = mutableListOf<List<String>>()

        val uploaded = refused.upload(snapshot(jellyfin, plex), backendRefusing(sent = sent))

        assertEquals(listOf(listOf("jellyfin", "plex")), sent)
        assertEquals(listOf(jellyfin, plex), uploaded.servers)
    }

    @Test
    fun aRefusedTypeIsLeftOutAndTheRestStillUpload() = runTest {
        val sent = mutableListOf<List<String>>()

        val uploaded = refused.upload(snapshot(jellyfin, plex, silo), backendRefusing("plex", "silo", sent = sent))

        assertEquals(
            listOf(listOf("jellyfin", "plex", "silo"), listOf("jellyfin", "silo"), listOf("jellyfin")),
            sent,
        )
        assertEquals(listOf(jellyfin), uploaded.servers)
        assertEquals(2, uploaded.profileId)
        assertEquals(7L, uploaded.version)
    }

    @Test
    fun aRefusedTypeIsNotTriedAgainForADay() = runTest {
        val sent = mutableListOf<List<String>>()
        val backend = backendRefusing("plex", sent = sent)
        refused.upload(snapshot(jellyfin, plex), backend)
        sent.clear()

        clock += 23 * 3_600_000L
        assertTrue(refused.isRefused("plex"))
        refused.upload(snapshot(jellyfin, plex), backend)
        assertEquals(listOf(listOf("jellyfin")), sent)
        sent.clear()

        clock += 2 * 3_600_000L
        assertFalse(refused.isRefused("plex"))
        refused.upload(snapshot(jellyfin, plex), backendRefusing(sent = sent))
        assertEquals(listOf(listOf("jellyfin", "plex")), sent)
    }

    @Test
    fun otherFailuresAreLeftForTheCaller() = runTest {
        val failure = RuntimeException("Too many media servers")

        val thrown = runCatching { refused.upload(snapshot(jellyfin, plex)) { throw failure } }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertFalse(refused.isRefused("plex"))
    }

    @Test
    fun aRefusalNamingATypeNotInTheListIsNotSwallowed() = runTest {
        assertFailsWith<RuntimeException> {
            refused.upload(snapshot(jellyfin)) { throw RuntimeException("Unsupported media server provider: plex") }
        }
    }

    @Test
    fun findsTheNameInACause() = runTest {
        val sent = mutableListOf<String>()

        refused.upload(snapshot(jellyfin, plex)) { upload ->
            sent += upload.servers.joinToString { it.providerId }
            if (upload.servers.any { it.providerId == "plex" }) {
                throw RuntimeException("Request failed", RuntimeException("Unsupported media server provider: plex"))
            }
        }

        assertEquals(listOf("jellyfin, plex", "jellyfin"), sent)
    }
}
