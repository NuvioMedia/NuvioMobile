package com.nuvio.app.features.tvremote

import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TvRemoteState(
    val deviceName: String? = null,
    val snapshot: RemoteSnapshot? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val pairingDeviceName: String? = null,
    val disconnected: Boolean = false,
    val commandPending: Boolean = false,
    val receivedAt: Long = 0,
    val message: String? = null,
) {
    val canControl get() = connected && !commandPending && snapshot?.sessionId != null && snapshot.state != "idle"
    fun position(now: Long): Long {
        val s = snapshot ?: return 0
        val advance = if (connected && s.state == "playing") ((now - receivedAt).coerceAtLeast(0) * s.speed).toLong() else 0
        return (s.positionMs + advance).coerceIn(0, s.durationMs.coerceAtLeast(s.positionMs))
    }
}

internal interface RemoteConnection {
    suspend fun pair(secret: String): PairResponse
    suspend fun snapshot(after: Long?): RemoteSnapshot
    suspend fun command(command: RemoteCommand)
    suspend fun revoke()
    fun close()
}

internal interface PairStore {
    fun read(): PairedTv?
    fun write(tv: PairedTv)
    fun clear()
}

/** Single authority for UI and Android controls. No command queue, alarms, workers or wake locks. */
internal class TvRemoteRepository(
    private val store: PairStore,
    private val scope: CoroutineScope,
    private val connect: (PairedTv) -> RemoteConnection = ::TvRemoteTransport,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutableState = MutableStateFlow(TvRemoteState())
    val state = mutableState.asStateFlow()
    var selected: PairedTv? = store.read()
        private set
    private var transport: RemoteConnection? = null
    private var pollJob: Job? = null
    private var commandJob: Job? = null
    private var pairingJob: Job? = null
    private var expiryJob: Job? = null
    private var generation = 0L
    private var disconnected = false

    init { mutableState.value = TvRemoteState(deviceName = selected?.deviceName); reconnect() }

    fun pair(value: String) {
        val code = try { PairingCode.parse(value) } catch (error: Exception) {
            mutableState.value = state.value.copy(message = error.message ?: "Invalid pairing code")
            return
        }
        disconnect()
        mutableState.value = state.value.copy(pairingDeviceName = code.tv.deviceName)
        pairingJob = scope.launch {
            var client: RemoteConnection? = null
            try {
                client = connect(code.tv)
                val result = withTimeout(12_000) { client.pair(code.secret) }
                require(result.deviceId == code.tv.deviceId && result.credential.matches(Regex("[A-Za-z0-9_-]{43}"))) { "TV identity mismatch" }
                val paired = code.tv.copy(deviceName = result.deviceName.take(100), credential = result.credential)
                store.write(paired)
                selected = paired
                disconnected = false
                mutableState.value = TvRemoteState(deviceName = paired.deviceName)
                reconnect(message = "Paired with ${paired.deviceName}. Checking TV playback…")
            } catch (timeout: TimeoutCancellationException) {
                mutableState.value = state.value.copy(pairingDeviceName = null, message = errorMessage(timeout, pairing = true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = state.value.copy(pairingDeviceName = null, message = errorMessage(error, pairing = true))
            } finally { client?.close() }
        }
    }

    fun reconnect(userRequested: Boolean = false, message: String? = null) {
        if (state.value.pairingDeviceName != null) return
        if (userRequested) disconnected = false
        val tv = selected ?: return
        if (disconnected) return
        stopConnection()
        invalidate(message)
        val expectedGeneration = generation
        mutableState.value = state.value.copy(connecting = true, disconnected = false)
        pollJob = scope.launch {
            var failures = 0
            var after: Long? = null // Always refresh before enabling controls, including foreground resumes.
            while (isActive && generation == expectedGeneration) {
                var client: RemoteConnection? = null
                try {
                    client = connect(tv)
                    transport = client
                    while (isActive && generation == expectedGeneration) {
                        val snapshot = client.snapshot(after)
                        require(snapshot.version == 1 && snapshot.deviceId == tv.deviceId) { "TV identity mismatch" }
                        require(snapshot.state in setOf("idle", "playing", "paused", "buffering"))
                        require(snapshot.positionMs >= 0 && snapshot.durationMs >= 0 && snapshot.speed.isFinite() && snapshot.speed in 0f..16f)
                        require(snapshot.state == "idle" || !snapshot.sessionId.isNullOrBlank())
                        expiryJob?.cancel(); expiryJob = null
                        mutableState.value = state.value.copy(
                            snapshot = snapshot.takeIf { it.state != "idle" && it.sessionId != null },
                            connected = true, connecting = false, message = null, receivedAt = elapsed(),
                        )
                        after = snapshot.revision
                        failures = 0
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (generation != expectedGeneration) return@launch
                    invalidate(errorMessage(error))
                    after = null
                    if (error is RemoteHttpException && error.status == 401) {
                        store.clear(); selected = null
                        mutableState.value = TvRemoteState(message = "TV authorization was revoked. Scan a new code to pair again.")
                        return@launch
                    }
                } finally { client?.close(); if (transport === client) transport = null }
                delay(retryDelay(failures++))
            }
        }
    }

    fun discovered(deviceId: String, host: String, port: Int) {
        val current = selected ?: return
        if (current.deviceId != deviceId || !isPrivateAddress(host) || port !in 1..65535) return
        if (current.host == host && current.port == port) return
        // Discovery is only an address hint; the scanned certificate still authenticates the TV.
        selected = current.copy(host = host, port = port)
        runCatching { store.write(requireNotNull(selected)) }
        reconnect()
    }

    fun send(action: String, positionMs: Long? = null, expectedSession: String? = state.value.snapshot?.sessionId) {
        val current = state.value
        val snapshot = current.snapshot ?: return
        if (!current.canControl || snapshot.sessionId != expectedSession || expectedSession == null) return
        if (action !in setOf("play", "pause", "seek")) return
        if (action == "seek" && (!snapshot.canSeek || positionMs == null)) return
        val client = transport ?: return
        val expectedGeneration = generation
        val request = RemoteCommand(UUID.randomUUID().toString(), expectedSession, action,
            if (action == "seek") positionMs!!.coerceIn(0, snapshot.durationMs) else null)
        mutableState.value = current.copy(commandPending = true)
        commandJob = scope.launch {
            try {
                // This job is cancelled on every disconnect, selection change, or foreground refresh.
                client.command(request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (expectedGeneration == generation) {
                    invalidate(errorMessage(error))
                    reconnect() // Refresh only. The command is deliberately never retried.
                }
            } finally {
                if (expectedGeneration == generation) mutableState.value = state.value.copy(commandPending = false)
            }
        }
    }

    fun seekBy(delta: Long, expectedSession: String? = state.value.snapshot?.sessionId) =
        send("seek", state.value.position(elapsed()) + delta, expectedSession)

    fun disconnect() {
        pairingJob?.cancel(); pairingJob = null
        disconnected = true
        stopConnection()
        expiryJob?.cancel(); expiryJob = null
        mutableState.value = TvRemoteState(deviceName = selected?.deviceName, disconnected = true)
    }

    fun forget() {
        val previous = selected
        disconnect()
        store.clear(); selected = null
        mutableState.value = TvRemoteState()
        if (previous != null) scope.launch {
            // Revoke if reachable; local forgetting never waits for the TV.
            val client = connect(previous)
            try { client.revoke() } catch (_: Exception) { } finally { client.close() }
        }
    }

    private fun stopConnection() {
        generation++
        pollJob?.cancel(); commandJob?.cancel()
        transport?.close(); transport = null
    }
    private fun invalidate(message: String? = null) {
        mutableState.value = state.value.copy(connected = false, commandPending = false, message = message)
        if (state.value.snapshot != null && expiryJob?.isActive != true) expiryJob = scope.launch {
            delay(30_000)
            if (!state.value.connected) mutableState.value = state.value.copy(snapshot = null)
        }
    }

    companion object {
        internal fun retryDelay(failures: Int) = minOf(30_000L, 1_000L shl failures.coerceIn(0, 5))
        internal fun errorMessage(error: Exception, pairing: Boolean = false): String {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            return when {
            causes.any { it is TvCertificateMismatchException } -> "The TV certificate changed. Generate a new pairing code on the TV and scan it again."
            causes.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException } -> "The TV certificate is outside its valid dates. Check the date and time on both devices, then scan a new code."
            error is javax.net.ssl.SSLException -> "Secure connection to the TV failed. Update Nuvio on the TV, then generate a new pairing code. If it still fails, turn off any VPN and try again."
            pairing && error is RemoteHttpException && error.status == 403 -> "Pairing code expired or already used. Generate a new code on the TV."
            pairing && error is RemoteHttpException -> "TV rejected pairing (HTTP ${error.status}). Generate a new code on the TV and try again."
            pairing && (error is java.io.IOException || error is TimeoutCancellationException) -> "Could not reach the TV. Keep both devices on the same home network, turn off any VPN, then scan a new code."
            pairing -> "Could not finish pairing. Generate a new code on the TV and try again."
            else -> "TV unavailable. Reconnecting when the connection returns."
            }
        }
    }
}
