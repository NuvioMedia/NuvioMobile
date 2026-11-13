package com.nuvio.app.features.settings

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStorage
import com.nuvio.app.features.servers.plex.PlexAccount
import com.nuvio.app.features.servers.plex.PlexHomeUser
import com.nuvio.app.features.servers.plex.PlexPin
import com.nuvio.app.features.servers.plex.PlexPinStatus
import com.nuvio.app.features.servers.plex.PlexProvider
import com.nuvio.app.features.servers.plex.PlexReach
import com.nuvio.app.features.servers.plex.PlexServer
import com.nuvio.app.features.servers.serverFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal enum class PlexSignInStep { CODE, SERVERS, USERS, PIN, SIGNING_IN, FAILED }

/** BROWSER: approve on plex.tv in this phone's browser. LINK: type a short code at plex.tv/link on another device. */
internal enum class PlexCodeMode { BROWSER, LINK }

internal enum class PlexCodePhase { STARTING, WAITING, EXPIRED }

internal data class PlexSignInState(
    val step: PlexSignInStep = PlexSignInStep.CODE,
    val mode: PlexCodeMode = PlexCodeMode.BROWSER,
    val codePhase: PlexCodePhase = PlexCodePhase.STARTING,
    val pin: PlexPin? = null,
    val authPage: String? = null,
    val accountName: String? = null,
    val servers: List<PlexServer> = emptyList(),
    val serversLoading: Boolean = false,
    val server: PlexServer? = null,
    val users: List<PlexHomeUser> = emptyList(),
    val pinFor: PlexHomeUser? = null,
    val pinWrong: Boolean = false,
    val busy: Boolean = false,
    val error: ServerFailure? = null,
)

/**
 * Signs in to Plex: approval on plex.tv (in this phone's browser, or with a code on another device), then one of the
 * account's servers, then a Plex Home user when there are several.
 */
internal class PlexSignInFlow(
    private val provider: PlexProvider,
    private val scope: CoroutineScope,
    private val onConnected: (ServerConnection) -> Unit,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pending: PendingPlexPin = PendingPlexPin(),
) {
    private val mutableState = MutableStateFlow(PlexSignInState())
    val state: StateFlow<PlexSignInState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var owner: PlexAccount? = null

    /** Picks up a browser sign-in that was still waiting when the app was closed, or starts a new one. */
    fun start() {
        val saved = pending.load()?.takeIf { it.expiresAtMs > clock() }
        if (saved == null) {
            newCode(PlexCodeMode.BROWSER)
            return
        }
        job?.cancel()
        owner = null
        mutableState.value = PlexSignInState(
            mode = PlexCodeMode.BROWSER,
            codePhase = PlexCodePhase.WAITING,
            pin = saved,
            authPage = provider.account.authPage(saved),
        )
        job = scope.launch { runCode(PlexCodeMode.BROWSER, first = saved) }
    }

    fun newCode(mode: PlexCodeMode = mutableState.value.mode) {
        job?.cancel()
        owner = null
        pending.clear()
        mutableState.value = PlexSignInState(mode = mode)
        job = scope.launch { runCode(mode, first = null) }
    }

    /** Plex codes last a few minutes, so new ones keep coming for a while when nobody approves one. */
    private suspend fun runCode(mode: PlexCodeMode, first: PlexPin?) {
        val startedAt = clock()
        var next = first
        while (clock() - startedAt < CODE_RENEW_FOR_MS) {
            val pin = next ?: try {
                provider.account.startPin(strong = mode == PlexCodeMode.BROWSER)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error)
                return
            }
            next = null
            if (mode == PlexCodeMode.BROWSER) pending.save(pin)
            mutableState.update {
                it.copy(
                    pin = pin,
                    authPage = provider.account.authPage(pin).takeIf { mode == PlexCodeMode.BROWSER },
                    codePhase = PlexCodePhase.WAITING,
                    error = null,
                )
            }
            val token = awaitLink(pin)
            if (token != null) {
                pending.clear()
                linked(token)
                return
            }
        }
        pending.clear()
        mutableState.update { it.copy(codePhase = PlexCodePhase.EXPIRED) }
    }

    /**
     * Phones often cut a background app's network while the browser is open, so a failed check is retried, more slowly,
     * until the code runs out.
     */
    private suspend fun awaitLink(pin: PlexPin): String? {
        var waitMs = POLL_MS
        while (clock() < pin.expiresAtMs) {
            delay(waitMs)
            val status = try {
                provider.account.checkPin(pin).also { waitMs = POLL_MS }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = (waitMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            when (status) {
                is PlexPinStatus.Linked -> return status.accountToken
                PlexPinStatus.Expired -> return null
                PlexPinStatus.Waiting -> Unit
            }
        }
        return null
    }

    private suspend fun linked(token: String) {
        mutableState.update { it.copy(step = PlexSignInStep.SERVERS, serversLoading = true) }
        val reached = try {
            val signedIn = provider.account.account(token)
            owner = signedIn
            mutableState.update { it.copy(accountName = signedIn.name) }
            coroutineScope {
                val users = async { provider.account.homeUsers(signedIn) }
                val servers = provider.account.servers(token)
                mutableState.update { it.copy(servers = servers) }
                val reached = servers.map { server -> async { provider.reach(server) } }.awaitAll()
                mutableState.update { it.copy(users = users.await()) }
                reached
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            return
        }
        val ordered = reached.sortedWith(compareBy({ it.reach.ordinal }, { !it.owned }, { it.name.lowercase() }))
        mutableState.update { it.copy(servers = ordered, serversLoading = false) }
        ordered.singleOrNull()?.takeIf { it.reach != PlexReach.OFFLINE }?.let(::pickServer)
    }

    fun pickServer(server: PlexServer) {
        val state = mutableState.value
        if (state.busy || server.reach == PlexReach.OFFLINE) return
        mutableState.update { it.copy(server = server, error = null) }
        val only = state.users.singleOrNull()
        when {
            state.users.isEmpty() || only != null && !only.hasPin -> finish(server, only, pin = null)
            only != null -> mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = only, pinWrong = false) }
            else -> mutableState.update { it.copy(step = PlexSignInStep.USERS) }
        }
    }

    fun pickUser(user: PlexHomeUser) {
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy) return
        if (user.hasPin) {
            mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = user, pinWrong = false, error = null) }
        } else {
            finish(server, user, pin = null)
        }
    }

    fun submitPin(pin: String) {
        val state = mutableState.value
        val server = state.server ?: return
        val user = state.pinFor ?: return
        if (state.busy || pin.isBlank()) return
        finish(server, user, pin.trim())
    }

    /** One step back; false when there is nothing to go back to and the sheet should close. */
    fun back(): Boolean {
        val state = mutableState.value
        if (state.busy) return true
        val previous = when (state.step) {
            PlexSignInStep.PIN -> if (state.users.size > 1) PlexSignInStep.USERS else PlexSignInStep.SERVERS
            PlexSignInStep.USERS -> PlexSignInStep.SERVERS
            else -> return false
        }
        mutableState.update { it.copy(step = previous, pinFor = null, pinWrong = false, error = null) }
        return true
    }

    private fun finish(server: PlexServer, user: PlexHomeUser?, pin: String?) {
        val owner = owner ?: return
        val step = mutableState.value.step
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null) }
        job = scope.launch {
            val connection = try {
                val switched = if (user == null) owner else provider.account.switchUser(owner, user, pin)
                if (switched == null) {
                    mutableState.update { it.copy(busy = false, pinWrong = true) }
                    return@launch
                }
                mutableState.update { it.copy(step = PlexSignInStep.SIGNING_IN) }
                val serverForUser = provider.serverFor(switched, owner, server)
                ServerRepository.connect(provider) { provider.signIn(serverForUser, switched) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(step = step, busy = false, error = error.serverFailure()) }
                return@launch
            }
            this@PlexSignInFlow.owner = null
            onConnected(connection)
        }
    }

    fun close() {
        job?.cancel()
        job = null
        owner = null
    }

    private fun fail(error: Exception) {
        mutableState.update {
            it.copy(step = PlexSignInStep.FAILED, busy = false, serversLoading = false, error = error.serverFailure())
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val POLL_BACKOFF_MAX_MS = 15_000L
        const val CODE_RENEW_FOR_MS = 30 * 60_000L
    }
}

/** The browser sign-in still waiting for approval, kept so it survives the app being closed while the browser is open. */
internal open class PendingPlexPin {
    open fun load(): PlexPin? = runCatching {
        ServerStorage.read(KEY)?.let { json.decodeFromString(Saved.serializer(), it) }
            ?.let { PlexPin(it.id, it.code, it.expiresAtMs, strong = true) }
    }.getOrNull()

    open fun save(pin: PlexPin) {
        runCatching {
            ServerStorage.write(KEY, json.encodeToString(Saved.serializer(), Saved(pin.id, pin.code, pin.expiresAtMs)))
        }
    }

    open fun clear() {
        runCatching { ServerStorage.write(KEY, null) }
    }

    @Serializable
    private class Saved(val id: Long, val code: String, val expiresAtMs: Long)

    private companion object {
        const val KEY = "plex.pending_sign_in"
        val json = Json { ignoreUnknownKeys = true }
    }
}
