package com.nuvio.app.features.tvremote

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TvRemoteRepositoryTest {
    private val tv = PairedTv("television-device-1", "Living room", "192.168.1.2", 8443, "a".repeat(64), "b".repeat(43))
    private fun snapshot(session: String = "session-movie-1234", revision: Long = 1, state: String = "playing", canSeek: Boolean = true) =
        RemoteSnapshot(deviceId = tv.deviceId, deviceName = tv.deviceName, sessionId = session, revision = revision,
            title = "A movie", state = state, durationMs = 100_000, positionMs = 20_000, canSeek = canSeek)
    private class Store(var tv: PairedTv?) : PairStore {
        override fun read() = tv
        override fun write(tv: PairedTv) { this.tv = tv }
        override fun clear() { tv = null }
    }
    private class Connection : RemoteConnection {
        val responses = Channel<Result<RemoteSnapshot>>(Channel.UNLIMITED)
        val commands = mutableListOf<RemoteCommand>()
        val revisions = mutableListOf<Long?>()
        var commandError: Exception? = null
        override suspend fun pair(secret: String): PairResponse = error("not pairing")
        override suspend fun snapshot(after: Long?): RemoteSnapshot { revisions.add(after); return responses.receive().getOrThrow() }
        override suspend fun command(command: RemoteCommand) { commands.add(command); commandError?.let { throw it } }
        override suspend fun revoke() {}
        override fun close() {}
        fun emit(value: RemoteSnapshot) { responses.trySend(Result.success(value)) }
        fun fail(error: Exception = IOException()) { responses.trySend(Result.failure(error)) }
    }

    @Test fun connectionLossDisablesImmediatelyAndExpiresAtThirtySeconds() = runTest {
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection }, { testScheduler.currentTime })
        connection.emit(snapshot()); runCurrent()
        assertTrue(repository.state.value.canControl)
        connection.fail(); runCurrent()
        assertFalse(repository.state.value.canControl)
        repository.send("pause"); runCurrent()
        assertTrue(connection.commands.isEmpty())
        advanceTimeBy(29_999); runCurrent()
        assertNotNull(repository.state.value.snapshot)
        advanceTimeBy(1); runCurrent()
        assertNull(repository.state.value.snapshot)
        connection.emit(snapshot()); runCurrent()
        assertTrue(repository.state.value.canControl)
        assertNull(connection.revisions[2])
    }

    @Test fun failedCommandsAreNeverReplayedAndReconnectRequiresFreshSnapshot() = runTest {
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection }, { testScheduler.currentTime })
        connection.emit(snapshot()); runCurrent()
        connection.commandError = IOException()
        repository.send("pause"); runCurrent()
        assertFalse(repository.state.value.canControl)
        assertEquals(1, connection.commands.size)
        connection.commandError = null
        connection.emit(snapshot(state = "paused")); runCurrent()
        assertTrue(repository.state.value.canControl)
        assertEquals(1, connection.commands.size)
    }

    @Test fun nonSeekableAndPreviousEpisodeCommandsCannotControlNewEpisode() = runTest {
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection }, { testScheduler.currentTime })
        connection.emit(snapshot(canSeek = false)); runCurrent()
        repository.seekBy(10_000); runCurrent()
        assertTrue(connection.commands.isEmpty())
        connection.emit(snapshot(session = "session-episode-2", revision = 2)); runCurrent()
        repository.send("pause", expectedSession = "session-movie-1234"); runCurrent()
        assertTrue(connection.commands.isEmpty())
        repository.send("seek", 999_999); runCurrent()
        assertEquals(100_000L, connection.commands.single().positionMs)
        assertEquals("session-episode-2", connection.commands.single().sessionId)
    }

    @Test fun revocationForgetsCredentialAndStopsReconnecting() = runTest {
        val connection = Connection()
        val store = Store(tv)
        val repository = TvRemoteRepository(store, backgroundScope, { connection }, { testScheduler.currentTime })
        connection.emit(snapshot()); runCurrent()
        connection.fail(RemoteHttpException(401)); runCurrent()
        assertNull(store.tv)
        assertNull(repository.selected)
        assertNull(repository.state.value.snapshot)
        val calls = connection.revisions.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(calls, connection.revisions.size)
    }

    @Test fun discoveryOnlyUpdatesSelectedTvAndRetainsPin() = runTest {
        val connection = Connection()
        val store = Store(tv)
        val repository = TvRemoteRepository(store, backgroundScope, { connection }, { testScheduler.currentTime })
        repository.discovered("other-tv", "192.168.1.9", 9876)
        assertEquals(tv, store.tv)
        repository.discovered(tv.deviceId, "192.168.1.9", 9876)
        assertEquals("192.168.1.9", store.tv?.host)
        assertEquals(tv.pin, store.tv?.pin)
        assertEquals(tv.credential, store.tv?.credential)
    }

    @Test fun explicitDisconnectStaysDisconnectedAcrossForegroundAndExitClearsState() = runTest {
        val connection = Connection()
        val repository = TvRemoteRepository(Store(tv), backgroundScope, { connection }, { testScheduler.currentTime })
        connection.emit(snapshot()); runCurrent()
        connection.emit(snapshot(state = "idle", revision = 2)); runCurrent()
        assertNull(repository.state.value.snapshot)
        repository.disconnect()
        repository.reconnect(); runCurrent()
        assertFalse(repository.state.value.connected)
        assertTrue(repository.state.value.disconnected)
        assertNull(repository.state.value.snapshot)
    }

    @Test fun progressFreezesOnDisconnectAndRetryDelayIsBounded() {
        val connected = TvRemoteState(snapshot = snapshot(), connected = true, receivedAt = 1_000)
        assertEquals(22_000L, connected.position(3_000))
        assertEquals(20_000L, connected.copy(connected = false).position(3_000))
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(TvRemoteRepository::retryDelay))
    }
}
