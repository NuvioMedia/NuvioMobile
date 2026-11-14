package com.nuvio.app.features.settings

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStorage
import com.nuvio.app.features.servers.serverFailure
import com.nuvio.app.features.servers.silo.SiloCode
import com.nuvio.app.features.servers.silo.SiloCodeEnd
import com.nuvio.app.features.servers.silo.SiloCodeStatus
import com.nuvio.app.features.servers.silo.SiloProfile
import com.nuvio.app.features.servers.silo.SiloProvider
import com.nuvio.app.features.servers.silo.SiloServer
import com.nuvio.app.features.servers.silo.SiloSignInProblem
import com.nuvio.app.features.servers.silo.SiloTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal enum class SiloSignInStep { ADDRESS, FINDING, CODE, PASSWORD, SIGNING_IN, PROFILES, PIN, FAILED }

internal enum class SiloCodePhase { STARTING, WAITING, EXPIRED, DENIED, UNAVAILABLE }

internal data class SiloSignInState(
    val step: SiloSignInStep = SiloSignInStep.ADDRESS,
    val server: SiloServer? = null,
    val code: SiloCode? = null,
    val codePhase: SiloCodePhase = SiloCodePhase.STARTING,
    val busy: Boolean = false,
    val error: ServerFailure? = null,
    val problem: SiloSignInProblem? = null,
    val profiles: List<SiloProfile> = emptyList(),
    val pinFor: SiloProfile? = null,
    val pinWrong: Boolean = false,
)

/**
 * Signs in to Silo: the server's address, a code approved in the browser (on this phone or another device) or the
 * password, then a Silo profile.
 */
internal class SiloSignInFlow(
    private val provider: SiloProvider,
    private val scope: CoroutineScope,
    private val onConnected: (ServerConnection) -> Unit,
    private val cleanup: CoroutineScope = CleanupScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pending: PendingSiloCode = PendingSiloCode(),
) {
    private val mutableState = MutableStateFlow(SiloSignInState())
    val state: StateFlow<SiloSignInState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var codeJob: Job? = null
    private var tokens: SiloTokens? = null

    /** Picks up a code that was still waiting for approval when the app was closed. */
    fun start() {
        val saved = pending.load()?.takeIf { it.code.expiresAtMs > clock() } ?: run {
            pending.clear()
            return
        }
        mutableState.value = SiloSignInState(step = SiloSignInStep.FINDING)
        job = scope.launch {
            val server = try {
                provider.findServer(saved.address)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                pending.clear()
                mutableState.value = SiloSignInState()
                return@launch
            }
            mutableState.value = SiloSignInState(
                step = SiloSignInStep.CODE,
                server = server,
                code = saved.code,
                codePhase = SiloCodePhase.WAITING,
            )
            codeJob = scope.launch { runCode(server, first = saved.code) }
        }
    }

    fun find(address: String) {
        if (address.isBlank() || mutableState.value.step == SiloSignInStep.FINDING) return
        mutableState.value = SiloSignInState(step = SiloSignInStep.FINDING)
        job = scope.launch {
            val server = try {
                provider.findServer(address)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = SiloSignInState(step = SiloSignInStep.ADDRESS).withError(error)
                return@launch
            }
            mutableState.value = SiloSignInState(step = SiloSignInStep.CODE, server = server)
            when {
                server.codeSignIn -> startCode(server)
                server.passwordSignIn -> usePassword()
                else -> mutableState.update { it.copy(codePhase = SiloCodePhase.UNAVAILABLE) }
            }
        }
    }

    fun backToAddress() {
        if (mutableState.value.busy) return
        withdrawCode()
        mutableState.value = SiloSignInState()
    }

    fun newCode() {
        val server = mutableState.value.server ?: return
        mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, problem = null) }
        startCode(server)
    }

    fun useCode() {
        val state = mutableState.value
        if (state.server?.codeSignIn != true || state.busy) return
        if (state.code == null || state.codePhase != SiloCodePhase.WAITING) {
            newCode()
        } else {
            mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, problem = null) }
        }
    }

    fun usePassword() {
        if (mutableState.value.server?.passwordSignIn != true) return
        mutableState.update { it.copy(step = SiloSignInStep.PASSWORD, busy = false, error = null, problem = null) }
    }

    private fun startCode(server: SiloServer) {
        val previous = codeJob
        val previousCode = mutableState.value.code.takeIf { mutableState.value.codePhase == SiloCodePhase.WAITING }
        previous?.cancel()
        mutableState.update { it.copy(code = null, codePhase = SiloCodePhase.STARTING) }
        codeJob = scope.launch {
            previous?.join()
            previousCode?.let { provider.cancelCode(server, it) }
            runCode(server, first = null)
        }
    }

    /** Codes that run out unseen are replaced for a while; one someone opened is not. */
    private suspend fun runCode(server: SiloServer, first: SiloCode?) {
        val startedAt = clock()
        var next = first
        while (true) {
            val code = next ?: try {
                provider.startCode(server)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val phase = if (error.serverFailure() == ServerFailure.NOT_FOUND) SiloCodePhase.UNAVAILABLE else SiloCodePhase.EXPIRED
                mutableState.update { it.copy(codePhase = phase).withError(error) }
                return
            }
            next = null
            pending.save(server.address, code)
            mutableState.update { it.copy(code = code, codePhase = SiloCodePhase.WAITING, error = null, problem = null) }
            var opened = false
            val end = awaitCode(server, code) { opened = true }
            if (end != null) pending.clear()
            when (end) {
                null -> return
                SiloCodeEnd.EXPIRED -> {
                    if (opened || clock() - startedAt >= CODE_RENEW_FOR_MS) {
                        mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                        return
                    }
                    provider.cancelCode(server, code)
                }
                SiloCodeEnd.DENIED, SiloCodeEnd.CANCELED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.DENIED) }
                    return
                }
                SiloCodeEnd.USED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                    return
                }
            }
        }
    }

    /**
     * Null once approved (and handed on), otherwise why the code ended. Phones often cut a background app's network
     * while the browser is open, so a failed check is retried, more slowly, until the code runs out.
     */
    private suspend fun awaitCode(server: SiloServer, code: SiloCode, onOpened: () -> Unit): SiloCodeEnd? {
        var waitMs = 0L
        var backoffMs = code.pollEveryMs
        var deadline = code.expiresAtMs
        while (true) {
            if (waitMs > 0L) delay(waitMs.coerceAtMost((deadline - clock()).coerceAtLeast(POLL_MIN_MS)))
            if (clock() >= deadline) return SiloCodeEnd.EXPIRED
            val status = try {
                provider.pollCode(server, code)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            backoffMs = code.pollEveryMs
            when (status) {
                is SiloCodeStatus.Waiting -> {
                    deadline = maxOf(deadline, status.expiresAtMs)
                    if (status.opened) onOpened()
                    waitMs = status.pollAfterMs
                }
                is SiloCodeStatus.Approved -> {
                    pending.clear()
                    signedIn(server, status.tokens)
                    return null
                }
                is SiloCodeStatus.Ended -> return status.reason
            }
        }
    }

    fun signInWithPassword(username: String, password: String) {
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy || username.isBlank() || password.isEmpty()) return
        mutableState.update { it.copy(busy = true, error = null, problem = null) }
        job = scope.launch {
            val signedIn = try {
                provider.passwordSignIn(server, username, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            signedIn(server, signedIn)
        }
    }

    private suspend fun signedIn(server: SiloServer, signedIn: SiloTokens) {
        val polling = codeJob
        if (polling != null && polling != currentCoroutineContext()[Job]) {
            polling.cancel()
            withdrawCode()
        }
        tokens = signedIn
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null, problem = null) }
        val profiles = try {
            provider.checkUsable(server, signedIn)
            provider.profiles(server, signedIn)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        val only = profiles.singleOrNull()
        when {
            profiles.isEmpty() -> {
                abandon()
                fail(ServerException(ServerFailure.FAILED))
            }
            only != null && !only.hasPin -> finish(only, null)
            only != null -> mutableState.update {
                it.copy(step = SiloSignInStep.PIN, busy = false, profiles = profiles, pinFor = only, pinWrong = false)
            }
            else -> mutableState.update { it.copy(step = SiloSignInStep.PROFILES, busy = false, profiles = profiles) }
        }
    }

    fun pickProfile(profile: SiloProfile) {
        if (mutableState.value.busy) return
        if (profile.hasPin) {
            mutableState.update { it.copy(step = SiloSignInStep.PIN, pinFor = profile, pinWrong = false, error = null) }
        } else {
            job = scope.launch { finish(profile, null) }
        }
    }

    fun backToProfiles() {
        if (mutableState.value.busy || mutableState.value.profiles.size < 2) return
        mutableState.update { it.copy(step = SiloSignInStep.PROFILES, pinFor = null, pinWrong = false, error = null) }
    }

    fun submitPin(pin: String) {
        val state = mutableState.value
        val server = state.server ?: return
        val profile = state.pinFor ?: return
        val signedIn = tokens ?: return
        if (state.busy || pin.isBlank()) return
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null) }
        job = scope.launch {
            val profileToken = try {
                provider.verifyPin(server, signedIn, profile, pin.trim())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            if (profileToken == null) {
                mutableState.update { it.copy(busy = false, pinWrong = true) }
            } else {
                finish(profile, profileToken)
            }
        }
    }

    private suspend fun finish(profile: SiloProfile, profileToken: String?) {
        val state = mutableState.value
        val server = state.server ?: return
        val signedIn = tokens ?: return
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null) }
        val connection = try {
            ServerRepository.connect(provider) { provider.signIn(server, signedIn, profile, profileToken, state.profiles.size) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        tokens = null
        onConnected(connection)
    }

    /** The user left: an unused code is withdrawn and a sign-in that wasn't saved is ended on the server. */
    fun close() {
        withdrawCode()
        stop()
    }

    /** The sheet went away without the user leaving (the screen was rebuilt): a waiting code stays saved for next time. */
    fun stop() {
        job?.cancel()
        codeJob?.cancel()
        job = null
        codeJob = null
        abandon()
    }

    private fun withdrawCode() {
        val state = mutableState.value
        val server = state.server ?: return
        val code = state.code ?: return
        if (state.codePhase != SiloCodePhase.WAITING) return
        pending.clear()
        mutableState.update { it.copy(code = null) }
        cleanup.launch { provider.cancelCode(server, code) }
    }

    private fun abandon() {
        val signedIn = tokens ?: return
        val server = mutableState.value.server ?: return
        tokens = null
        cleanup.launch { provider.abandon(server, signedIn) }
    }

    private fun fail(error: Exception) {
        mutableState.update { it.copy(step = SiloSignInStep.FAILED, busy = false).withError(error) }
    }

    private fun SiloSignInState.withError(error: Exception): SiloSignInState =
        copy(error = error.serverFailure(), problem = SiloProvider.signInProblem(error))

    private companion object {
        const val POLL_MIN_MS = 250L
        const val POLL_BACKOFF_MAX_MS = 30_000L
        const val CODE_RENEW_FOR_MS = 30 * 60_000L
    }
}

/** Runs the server calls that end an unfinished sign-in, which must outlive the sheet that started them. */
private val CleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** A code still waiting for approval, kept so it survives the app being closed while the browser is open. */
internal open class PendingSiloCode {
    class Saved(val address: String, val code: SiloCode)

    open fun load(): Saved? = runCatching {
        ServerStorage.read(KEY)?.let { json.decodeFromString(Wire.serializer(), it) }?.let { wire ->
            Saved(
                address = wire.address,
                code = SiloCode(
                    deviceCode = wire.deviceCode,
                    userCode = wire.userCode,
                    link = wire.link,
                    linkWithCode = wire.linkWithCode,
                    expiresAtMs = wire.expiresAtMs,
                    serverExpiresAt = wire.serverExpiresAt,
                    pollEveryMs = wire.pollEveryMs,
                ),
            )
        }
    }.getOrNull()

    open fun save(address: String, code: SiloCode) {
        val wire = Wire(
            address = address,
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            link = code.link,
            linkWithCode = code.linkWithCode,
            expiresAtMs = code.expiresAtMs,
            serverExpiresAt = code.serverExpiresAt,
            pollEveryMs = code.pollEveryMs,
        )
        runCatching { ServerStorage.write(KEY, json.encodeToString(Wire.serializer(), wire)) }
    }

    open fun clear() {
        runCatching { ServerStorage.write(KEY, null) }
    }

    @Serializable
    private class Wire(
        val address: String,
        val deviceCode: String,
        val userCode: String,
        val link: String,
        val linkWithCode: String,
        val expiresAtMs: Long,
        val serverExpiresAt: String? = null,
        val pollEveryMs: Long,
    )

    private companion object {
        const val KEY = "silo.pending_sign_in"
        val json = Json { ignoreUnknownKeys = true }
    }
}
