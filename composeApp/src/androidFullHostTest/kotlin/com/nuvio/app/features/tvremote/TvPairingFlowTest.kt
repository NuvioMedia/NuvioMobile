package com.nuvio.app.features.tvremote

import android.app.Application
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

internal val pairingTestTv = PairedTv("television-device-1", "Living room", "192.168.1.2", 8443, "a".repeat(64))

internal fun pairingTestCode() = Uri.Builder().scheme("nuvio-tv").authority("pair")
    .appendQueryParameter("v", "1").appendQueryParameter("id", pairingTestTv.deviceId)
    .appendQueryParameter("name", pairingTestTv.deviceName).appendQueryParameter("host", pairingTestTv.host)
    .appendQueryParameter("port", pairingTestTv.port.toString()).appendQueryParameter("pin", pairingTestTv.pin)
    .appendQueryParameter("secret", "b".repeat(43))
    .appendQueryParameter("expires", (System.currentTimeMillis() + 120_000).toString()).build().toString()

internal class PairingTestStore(var tv: PairedTv? = null) : PairStore {
    override fun read() = tv
    override fun write(tv: PairedTv) { this.tv = tv }
    override fun clear() { tv = null }
}

internal class PairingTestConnection : RemoteConnection {
    val pairing = CompletableDeferred<PairResponse>()
    val snapshots = Channel<RemoteSnapshot>(Channel.UNLIMITED)
    val revisions = mutableListOf<Long?>()
    var secret: String? = null
    var closed = false
    override suspend fun pair(secret: String): PairResponse { this.secret = secret; return pairing.await() }
    override suspend fun snapshot(after: Long?): RemoteSnapshot { revisions.add(after); return snapshots.receive() }
    override suspend fun command(command: RemoteCommand) {}
    override suspend fun revoke() {}
    override fun close() { closed = true }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvPairingFlowTest {
    @Test fun scanPairsPersistsConnectsWhileIdleAndDetectsLaterPlayback() = runTest {
        val store = PairingTestStore()
        val pairing = PairingTestConnection()
        val playback = PairingTestConnection()
        val repository = TvRemoteRepository(store, backgroundScope, { if (it.credential.isEmpty()) pairing else playback }, { testScheduler.currentTime })
        repository.pair(pairingTestCode())
        assertEquals("Living room", repository.state.value.pairingDeviceName)
        runCurrent()
        repository.reconnect() // MainActivity resumes as the scan result is delivered.
        assertEquals("Living room", repository.state.value.pairingDeviceName)
        pairing.pairing.complete(PairResponse(pairingTestTv.deviceId, "Living room", "c".repeat(43)))
        runCurrent()
        assertEquals("c".repeat(43), store.tv?.credential)
        assertNull(repository.state.value.pairingDeviceName)
        assertContains(repository.state.value.message.orEmpty(), "Paired with Living room")
        assertTrue(pairing.closed)
        val idle = RemoteSnapshot(deviceId = pairingTestTv.deviceId, deviceName = "Living room")
        playback.snapshots.send(idle)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.snapshot)
        assertNull(repository.state.value.message)
        playback.snapshots.send(idle.copy(sessionId = "movie-session-1234", state = "playing", revision = 1, title = "A movie"))
        runCurrent()
        assertTrue(repository.state.value.canControl)
        assertEquals("A movie", repository.state.value.snapshot?.title)
        assertEquals(listOf(null, 0L, 1L), playback.revisions)
    }

    @Test fun pairingTimeoutClosesRequestAndTellsUserToRescanInsteadOfPromisingReconnect() = runTest {
        val connection = PairingTestConnection()
        val store = PairingTestStore()
        val repository = TvRemoteRepository(store, backgroundScope, { connection })
        repository.pair(pairingTestCode()); runCurrent()
        advanceTimeBy(12_000); runCurrent()
        assertNull(repository.state.value.pairingDeviceName)
        assertFalse(repository.state.value.connecting)
        assertNull(store.tv)
        assertTrue(connection.closed)
        assertContains(repository.state.value.message.orEmpty(), "scan a new code")
        assertFalse(repository.state.value.message.orEmpty().contains("Reconnecting"))
    }

    @Test fun connectionSetupFailureIsReportedAndAnotherScanCanSucceed() = runTest {
        var fail = true
        val connection = PairingTestConnection()
        val repository = TvRemoteRepository(PairingTestStore(), backgroundScope, {
            if (fail) throw IOException("connection setup failed") else connection
        })
        repository.pair(pairingTestCode()); runCurrent()
        assertNull(repository.state.value.pairingDeviceName)
        assertContains(repository.state.value.message.orEmpty(), "Could not reach the TV")
        fail = false
        repository.pair(pairingTestCode()); runCurrent()
        assertEquals("b".repeat(43), connection.secret)
        connection.pairing.completeExceptionally(RemoteHttpException(403)); runCurrent()
        assertContains(repository.state.value.message.orEmpty(), "expired or already used")
    }

    @Test fun disconnectCancelsInflightPairingWithoutSavingACredential() = runTest {
        val connection = PairingTestConnection()
        val store = PairingTestStore()
        val repository = TvRemoteRepository(store, backgroundScope, { connection })
        repository.pair(pairingTestCode()); runCurrent()
        repository.disconnect()
        connection.pairing.complete(PairResponse(pairingTestTv.deviceId, "Living room", "c".repeat(43)))
        runCurrent()
        assertNull(store.tv)
        assertNull(repository.state.value.pairingDeviceName)
        assertTrue(repository.state.value.disconnected)
        assertTrue(connection.closed)
    }
}
