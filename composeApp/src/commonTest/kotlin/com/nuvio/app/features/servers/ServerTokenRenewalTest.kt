package com.nuvio.app.features.servers

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTokenRenewalTest {
    private open class RenewingProvider : ServerProvider by FakeServerProvider() {
        var due = false
        var renewals = 0
        var refuse = false

        override fun renewalDue(session: ServerSession): Boolean = due

        override suspend fun renewToken(session: ServerSession, failure: ServerException?): String? {
            if (refuse) throw ServerException(ServerFailure.AUTH_REQUIRED)
            renewals++
            return "token-${renewals + 1}"
        }
    }

    private fun install(provider: ServerProvider, token: String = "token-1"): ServerConnection {
        ServerProviders.registered.removeAll { it.id == provider.id }
        ServerProviders.registered += provider
        val connection = ServerConnection(
            id = CONNECTION,
            providerId = provider.id,
            name = "Box",
            address = "https://box.example",
            remoteServerId = "server-1",
            remoteUserId = "user-1",
            userName = "viewer",
            credentialRef = "k$CONNECTION",
        )
        ServerRepository.store(connection, token)
        return connection
    }

    @AfterTest
    fun tearDown() = removeFakeServer(CONNECTION)

    @Test
    fun anExpiredTokenIsRenewedAndTheCallRepeated() = runTest {
        install(RenewingProvider())
        val seen = mutableListOf<String>()

        val result = ServerRepository.call(CONNECTION) { _, session ->
            seen += session.token
            if (session.token == "token-1") throw ServerException(ServerFailure.AUTH_REQUIRED)
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("token-1", "token-2"), seen)
        assertEquals("token-2", ServerRepository.session(CONNECTION)?.token)
    }

    @Test
    fun aTokenRunningOutIsRenewedBeforeTheCall() = runTest {
        install(RenewingProvider().apply { due = true })

        val token = ServerRepository.call(CONNECTION) { _, session -> session.token }

        assertEquals("token-2", token)
    }

    @Test
    fun callsAtTheSameTimeShareOneRenewal() = runTest {
        val provider = RenewingProvider()
        install(provider)

        val tokens = (1..5).map {
            async {
                ServerRepository.call(CONNECTION) { _, session ->
                    if (session.token == "token-1") throw ServerException(ServerFailure.AUTH_REQUIRED)
                    session.token
                }
            }
        }.awaitAll()

        assertEquals(List(5) { "token-2" }, tokens)
        assertEquals(1, provider.renewals)
    }

    @Test
    fun aRenewalIsQueuedForTheAccount() = runTest {
        install(RenewingProvider().apply { due = true })
        val before = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        ServerRepository.markPushed(before)

        ServerRepository.call(CONNECTION) { _, _ -> Unit }

        val after = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        assertTrue(after.pendingPush)
        assertEquals("token-2", after.servers.single().token)
    }

    @Test
    fun aRenewalTheAccountDoesNotNeedIsNotUploaded() = runTest {
        install(object : RenewingProvider() {
            override fun renewalChangesAccount(previous: String, renewed: String): Boolean = false
        }.apply { due = true })
        val before = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        ServerRepository.markPushed(before)

        ServerRepository.call(CONNECTION) { _, _ -> Unit }

        assertEquals("token-2", ServerRepository.session(CONNECTION)?.token)
        val after = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))
        assertEquals(before.version, after.version)
        assertTrue(!after.pendingPush)
    }

    @Test
    fun aKeptTokenSurvivesAnAccountCopy() {
        install(
            object : ServerProvider by FakeServerProvider() {
                override fun keepsLocalToken(local: String, fromAccount: String): Boolean = true
                override fun tokenForAccount(token: String): String = "account-copy"
            },
            token = "device-token",
        )
        val snapshot = assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId))

        assertEquals("account-copy", snapshot.servers.single().token)
        assertTrue(ServerRepository.applySync(snapshot, snapshot.servers, snapshot.servers.map { it.key }.toSet()))
        assertEquals("device-token", ServerRepository.session(CONNECTION)?.token)
    }

    @Test
    fun anAccountCopyTooLongForTheBackendStaysOnTheDevice() {
        install(
            object : ServerProvider by FakeServerProvider() {
                override fun tokenForAccount(token: String): String = "x".repeat(1_025)
            },
            token = "device-token",
        )

        assertTrue(assertNotNull(ServerRepository.syncSnapshot(ProfileRepository.activeProfileId)).servers.isEmpty())
    }

    @Test
    fun anEndedSignInAsksToSignInAgain() = runTest {
        install(RenewingProvider().apply { refuse = true })

        val error = runCatching {
            ServerRepository.call(CONNECTION) { _, _ -> throw ServerException(ServerFailure.AUTH_REQUIRED) }
        }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        assertEquals(ServerFailure.AUTH_REQUIRED, ServerRepository.uiState.value.failures[CONNECTION])
    }

    private companion object {
        const val CONNECTION = "crenew"
    }
}
