package com.nuvio.app.features.party

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class PartyCompanionError {
    SeatLeft,
    Ended,
}

data class PartyCompanionLink(
    val partyId: String,
    val memberId: String,
    val members: List<PartyMember>,
    val media: PartyMedia,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
) {
    val seat: PartyMember?
        get() = members.firstOrNull { it.id == memberId }
}

data class PartyCompanionState(
    val link: PartyCompanionLink? = null,
    val error: PartyCompanionError? = null,
) {
    val active: Boolean
        get() = link != null || error != null
}

@OptIn(ExperimentalCoroutinesApi::class)
object PartyCompanion {
    private val log = Logger.withTag("PartyCompanion")
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(PartyCompanionState())
    val state: StateFlow<PartyCompanionState> = _state.asStateFlow()

    private var profileIndex: Int? = null
    private var channel: PartyChannel? = null
    private var session: Job? = null

    init {
        scope.launch {
            AuthRepository.state.collect { auth ->
                val signedIn = auth is AuthState.Authenticated && !auth.isAnonymous
                if (!signedIn) end(null)
            }
        }
    }

    fun start() {
        scope.launch {
            if (_state.value.active || session?.isActive == true) return@launch
            session = scope.launch { link() }
        }
    }

    fun leave() {
        scope.launch { end(null) }
    }

    internal suspend fun stop() {
        withContext(dispatcher) { end(null) }
    }

    private suspend fun link() {
        val index = ProfileRepository.state.value.activeProfile?.profileIndex ?: return
        if (!PartyRepository.canUse || PartyRepository.state.value.party != null) return
        val snapshot = runRemote { PartyApi.linkProfile(deviceId(), index, null) } ?: return
        if (snapshot.error != null) return
        open(snapshot, index)
    }

    private fun open(snapshot: PartySnapshot, index: Int) {
        val info = snapshot.party ?: return
        val memberId = snapshot.memberId ?: return
        profileIndex = index
        _state.value = PartyCompanionState(
            link = PartyCompanionLink(
                partyId = info.id,
                memberId = memberId,
                members = snapshot.members,
                media = info.media,
            ),
        )
        PartyChat.start(PartyChatRoom(info.id, memberId, snapshot.members))
        val job = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(dispatcher + job)
        val partyChannel = PartyChannel(info.id, memberId, PartyChannel.COMPANION_EVENTS)
        session = job
        channel = partyChannel
        sessionScope.launch {
            runCatching {
                partyChannel.open(
                    scope = sessionScope,
                    onMessage = { event, payload -> sessionScope.launch { onMessage(event, payload) } },
                    onConnected = { connected -> sessionScope.launch { onConnection(connected) } },
                )
            }.onFailure { log.w(it) { "Companion channel failed" } }
        }
    }

    private suspend fun onConnection(connected: Boolean) {
        updateLink { it.copy(connected = connected, reconnecting = !connected && (it.connected || it.reconnecting)) }
        if (!connected) return
        val link = _state.value.link ?: return
        val index = profileIndex ?: return
        val snapshot = runRemote { PartyApi.linkProfile(deviceId(), index, link.memberId) } ?: return
        when (snapshot.error) {
            null -> {
                updateLink { it.copy(members = snapshot.members, media = snapshot.party?.media ?: it.media) }
                PartyChat.refresh()
            }
            ENDED -> scope.launch { end(PartyCompanionError.Ended) }
            else -> scope.launch { end(PartyCompanionError.SeatLeft) }
        }
    }

    private fun onMessage(event: String, payload: JsonObject) {
        val link = _state.value.link ?: return
        when (event) {
            "chat" -> PartyChat.receive(payload)
            "media" -> payload["media"]
                ?.let { runCatching { PartyApi.json.decodeFromJsonElement(PartyMedia.serializer(), it) }.getOrNull() }
                ?.let { media -> updateLink { it.copy(media = media) } }
            "member_left" -> {
                val memberId = (payload["member_id"] as? JsonPrimitive)?.contentOrNull
                if (memberId == link.memberId) scope.launch { end(PartyCompanionError.SeatLeft) }
            }
            "ended" -> scope.launch { end(PartyCompanionError.Ended) }
        }
    }

    private suspend fun end(error: PartyCompanionError?) {
        session?.cancel()
        session = null
        channel?.close()
        channel = null
        if (_state.value.link != null) PartyChat.stop()
        _state.value = PartyCompanionState(error = error)
    }

    private fun updateLink(transform: (PartyCompanionLink) -> PartyCompanionLink) {
        _state.update { current -> current.link?.let { current.copy(link = transform(it)) } ?: current }
    }

    private suspend fun <T> runRemote(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Companion call failed" }
            null
        }

    private fun deviceId(): String = SyncClientIdentity.currentClientId()

    private const val ENDED = "party_ended"
}
