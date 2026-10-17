package com.nuvio.app.features.party

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.realtime.Presence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

@OptIn(ExperimentalCoroutinesApi::class)
object PartyRepository {
    private val log = Logger.withTag("Party")
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val clock = PartyClock()

    private val _state = MutableStateFlow(PartyUiState())
    val state: StateFlow<PartyUiState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<PartyPlayback?>(null)
    val playback: StateFlow<PartyPlayback?> = _playback.asStateFlow()

    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PartyEvent> = _events.asSharedFlow()

    private val _notices = MutableSharedFlow<PartyNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<PartyNotice> = _notices.asSharedFlow()

    private val _sharedStream = MutableStateFlow<PartyStream?>(null)
    val sharedStream: StateFlow<PartyStream?> = _sharedStream.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val available: StateFlow<Boolean> = combine(ServerConfigurationRepository.active, _enabled) { server, enabled ->
        server.capabilities.watchParty && enabled
    }.stateIn(scope, SharingStarted.Eagerly, ServerConfigurationRepository.active.value.capabilities.watchParty)

    private var channel: PartyChannel? = null
    private var sessionScope: CoroutineScope? = null
    private var sessionJob: Job? = null
    private var persistJob: Job? = null
    private var presenceJob: Job? = null
    private var localPresence: PartyPresence? = null
    private var waitStartedAt: TimeMark? = null
    private var resumedAt: TimeMark? = null
    private val ignoredWaiting = mutableSetOf<String>()
    private var rejoining = false

    init {
        scope.launch {
            AuthRepository.state.collect { auth ->
                val signedIn = auth is AuthState.Authenticated && !auth.isAnonymous
                if (!signedIn && _state.value.party != null) {
                    mutex.withLock { stopSession() }
                }
            }
        }
        scope.launch {
            refreshEnabled()
            AppForegroundMonitor.events()
                .flowOn(Dispatchers.Main)
                .filter { it == AppVisibility.Foreground }
                .collect { refreshEnabled() }
        }
    }

    val isSupported: Boolean
        get() = available.value

    val canUse: Boolean
        get() = isSupported && (AuthRepository.state.value as? AuthState.Authenticated)?.isAnonymous == false

    fun nowMs(): Long = clock.nowMs()

    suspend fun create(media: PartyMedia): PartyError? =
        start { identity -> PartyApi.create(identity, media, null) }

    suspend fun join(code: String): PartyError? =
        start { identity -> PartyApi.join(normalizePartyCode(code), identity) }

    suspend fun preview(code: String): PartyPreview =
        withContext(dispatcher) {
            try {
                PartyApi.preview(normalizePartyCode(code))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Watch party preview failed" }
                PartyPreview(error = "network")
            }
        }

    fun leave() {
        scope.launch {
            mutex.withLock {
                val party = _state.value.party ?: return@withLock
                stopSession()
                runRemote { PartyApi.leave(party.id, deviceId()) }
            }
        }
    }

    fun end() {
        scope.launch {
            mutex.withLock {
                val party = _state.value.party ?: return@withLock
                val result = runRemote { PartyApi.end(party.id, deviceId()) } ?: return@withLock
                if (result.error == null) stopSession()
            }
        }
    }

    fun removeMember(memberId: String) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            val snapshot = runRemote { PartyApi.removeMember(party.id, deviceId(), memberId) } ?: return@launch
            applySnapshot(snapshot)
        }
    }

    fun setHostOnly(enabled: Boolean) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || party.hostOnly == enabled) return@launch
            updateParty { it.copy(hostOnly = enabled) }
            val snapshot = runRemote { PartyApi.setHostOnly(party.id, deviceId(), enabled) }
            if (snapshot?.party == null) updateParty { it.copy(hostOnly = party.hostOnly) }
            snapshot?.let(::applySnapshot)
        }
    }

    fun setSharing(enabled: Boolean) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || party.sharing == enabled) return@launch
            updateParty { it.copy(sharing = enabled) }
            if (!enabled) sendStream(null)
        }
    }

    fun shareStream(stream: PartyStream?) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || !party.sharing || stream == _sharedStream.value) return@launch
            sendStream(stream)
        }
    }

    fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) {
        val at = clock.nowMs()
        scope.launch { publish(paused = paused, positionMs = positionMs, rate = rate, waiting = false, at = at) }
    }

    fun publishMedia(media: PartyMedia, positionMs: Long) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (media == party.media || !party.isHost) return@launch
            val sameContent = media.key == party.media.key
            updateParty { it.copy(media = media) }
            val next = if (sameContent) {
                _playback.value
            } else {
                nextPlayback(party, paused = false, positionMs = positionMs, rate = 1f, waiting = false, mediaKey = media.key, at = clock.nowMs())
                    .also { _playback.value = it }
            }
            persistJob?.cancel()
            val snapshot = runRemote { PartyApi.update(party.id, deviceId(), media, next) }
            snapshot?.let(::applySnapshot)
        }
    }

    fun setLocalStatus(status: PartyMemberStatus, mediaKey: String?, behindMs: Long? = null) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            val next = PartyPresence(memberId = party.memberId, status = status, mediaKey = mediaKey, behindMs = behindMs)
            if (next == localPresence) return@launch
            localPresence = next
            if (status == PartyMemberStatus.Ready) ignoredWaiting.remove(party.memberId)
            updateParty { it.copy(presence = it.presence + (party.memberId to next)) }
            presenceJob?.cancel()
            presenceJob = sessionScope?.launch {
                delay(250)
                runCatching { channel?.send("status", encodePresence(next)) }
            }
        }
    }

    private suspend fun start(request: suspend (PartyIdentity) -> PartySnapshot): PartyError? {
        if (!isSupported) return PartyError.Unavailable
        if (!canUse) return PartyError.SignInRequired
        return withContext(dispatcher) {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    val sentAt = clock.mark()
                    val snapshot = request(identity())
                    partyErrorOf(snapshot.error)?.let { error ->
                        if (error == PartyError.Unavailable) _enabled.value = false
                        return@withLock error
                    }
                    val party = snapshot.toActiveParty() ?: return@withLock PartyError.Network
                    stopSession()
                    clock.reset()
                    snapshot.serverTimeMs?.let { clock.addSample(it, sentAt) }
                    startSession(party, snapshot.party?.playback)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.w(error) { "Watch party request failed" }
                    PartyError.Network
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun startSession(party: ActiveParty, playback: PartyPlayback?) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val session = CoroutineScope(dispatcher + job)
        val partyChannel = PartyChannel(party.id, party.memberId)
        sessionJob = job
        sessionScope = session
        channel = partyChannel
        localPresence = null
        ignoredWaiting.clear()
        waitStartedAt = null
        resumedAt = null
        _playback.value = playback?.takeIf { it.mediaKey == null || it.mediaKey == party.media.key }
        _state.value = PartyUiState(party = party)
        session.launch {
            runCatching {
                partyChannel.open(
                    scope = session,
                    onMessage = { event, payload -> session.launch { onMessage(event, payload) } },
                    onPresence = { joins, leaves -> session.launch { onPresence(joins, leaves) } },
                    onConnected = { connected -> session.launch { onConnection(connected) } },
                )
            }.onFailure { log.w(it) { "Watch party channel failed" } }
        }
        session.launch { heartbeatLoop() }
        session.launch { refineClock() }
        session.launch { coordinatorLoop() }
    }

    private suspend fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
        persistJob?.cancel()
        persistJob = null
        presenceJob = null
        channel?.close()
        channel = null
        localPresence = null
        _playback.value = null
        _sharedStream.value = null
        _state.value = PartyUiState()
    }

    private fun finish(event: PartyEvent) {
        val partyId = _state.value.party?.id ?: return
        scope.launch {
            mutex.withLock {
                if (_state.value.party?.id != partyId) return@withLock
                stopSession()
                _events.tryEmit(event)
            }
        }
    }

    private suspend fun onConnection(connected: Boolean) {
        if (!connected) {
            updateParty { current -> current.copy(connected = false, presence = current.presence.filterKeys { it == current.memberId }) }
            return
        }
        updateParty { it.copy(connected = true) }
        val party = _state.value.party ?: return
        val presence = encodePresence(ownPresence(party))
        runCatching { channel?.track(presence) }
        runCatching { channel?.send("status", presence) }
        _playback.value?.let { current -> runCatching { channel?.send("state", encodePlayback(current)) } }
        if (party.isHost && party.sharing) _sharedStream.value?.let { sendStream(it) }
        requestState(party)
    }

    private suspend fun onMessage(event: String, payload: JsonObject) {
        val party = _state.value.party ?: return
        when (event) {
            "state" -> decode(payload, PartyPlayback.serializer())?.let(::accept)
            "state_request" -> {
                if (payload.string("member_id") == party.memberId) return
                runCatching { channel?.send("status", encodePresence(ownPresence(party))) }
                if (party.isHost && party.sharing) _sharedStream.value?.let { sendStream(it) }
                val current = _playback.value ?: return
                runCatching { channel?.send("state", encodePlayback(current)) }
            }
            "status" -> decode(payload, PartyPresence.serializer())?.let(::applyStatus)
            "control" -> {
                val hostOnly = (payload["host_only"] as? JsonPrimitive)?.booleanOrNull ?: return
                if (party.hostOnly == hostOnly) return
                updateParty { it.copy(hostOnly = hostOnly) }
                if (!party.isHost) _events.tryEmit(PartyEvent.ControlChanged(hostOnly))
            }
            "member_joined" -> refreshMembers(announceMemberId = payload.string("member_id"))
            "member_left" -> onMemberLeft(
                memberId = payload.string("member_id"),
                reason = payload.string("reason"),
                hostMemberId = payload.string("host_member_id"),
            )
            "ended" -> {
                if (payload.string("reason") == "disabled") _enabled.value = false
                finish(PartyEvent.Ended)
            }
            "media" -> {
                val media = payload["media"]?.let { decode(it, PartyMedia.serializer()) } ?: return
                val playback = payload["playback"]?.takeIf { it !is JsonNull }?.let { decode(it, PartyPlayback.serializer()) }
                applyMedia(media, playback)
            }
            "stream" -> {
                if (party.isHost || payload.string("member_id") != party.hostMemberId) return
                _sharedStream.value = payload["stream"]
                    ?.takeIf { it !is JsonNull }
                    ?.let { decode(it, PartyStream.serializer()) }
                    ?.takeIf { isShareableStreamUrl(it.url) }
            }
        }
    }

    private fun onPresence(joins: Map<String, Presence>, leaves: Map<String, Presence>) {
        val party = _state.value.party ?: return
        val presence = party.presence.toMutableMap()
        val left = leaves.keys.filter { it != party.memberId && it !in joins }
        presence -= left.toSet()
        joins.forEach { (key, value) ->
            if (key == party.memberId || key in presence) return@forEach
            val decoded = decode(value.state, PartyPresence.serializer()) ?: PartyPresence(memberId = key)
            presence[key] = decoded.copy(memberId = key)
            if (decoded.status == PartyMemberStatus.Ready) ignoredWaiting.remove(key)
        }
        updateParty { it.copy(presence = presence) }
        if (left.isNotEmpty()) sessionScope?.launch { requestState(party) }
    }

    private suspend fun requestState(party: ActiveParty) {
        runCatching { channel?.send("state_request", buildJsonObject { put("member_id", party.memberId) }) }
    }

    private fun applyStatus(status: PartyPresence) {
        val party = _state.value.party ?: return
        if (status.memberId == party.memberId) return
        if (status.status == PartyMemberStatus.Ready) ignoredWaiting.remove(status.memberId)
        updateParty { it.copy(presence = it.presence + (status.memberId to status)) }
    }

    private fun ownPresence(party: ActiveParty): PartyPresence =
        localPresence ?: PartyPresence(memberId = party.memberId)

    private fun onMemberLeft(memberId: String?, reason: String?, hostMemberId: String?) {
        val party = _state.value.party ?: return
        if (memberId == null) return
        if (memberId == party.memberId) {
            if (reason == "removed") finish(PartyEvent.Removed) else rejoinOrFinish()
            return
        }
        val name = party.member(memberId)?.displayName
        if (memberId == party.hostMemberId) _sharedStream.value = null
        updateParty { current ->
            current.copy(
                members = current.members.filterNot { it.id == memberId },
                presence = current.presence - memberId,
                hostMemberId = hostMemberId ?: current.hostMemberId,
            )
        }
        ignoredWaiting.remove(memberId)
        if (name != null) _events.tryEmit(PartyEvent.MemberLeft(name))
    }

    private suspend fun refreshMembers(announceMemberId: String?) {
        val party = _state.value.party ?: return
        val snapshot = runRemote { PartyApi.heartbeat(party.id, deviceId()) } ?: return
        applySnapshot(snapshot)
        val name = _state.value.party?.member(announceMemberId)?.displayName
        if (announceMemberId != null && announceMemberId != party.memberId && name != null) {
            _events.tryEmit(PartyEvent.MemberJoined(name))
        }
    }

    private fun applySnapshot(snapshot: PartySnapshot) {
        when (snapshot.error) {
            null -> Unit
            "not_member" -> {
                rejoinOrFinish()
                return
            }
            "party_ended" -> {
                finish(PartyEvent.Ended)
                return
            }
            else -> return
        }
        val info = snapshot.party ?: return
        updateParty { current ->
            current.copy(
                members = snapshot.members,
                hostMemberId = info.hostMemberId,
                maxMembers = info.maxMembers,
                hostOnly = info.hostOnly,
                presence = current.presence.filterKeys { key -> snapshot.members.any { it.id == key } },
            )
        }
        if (info.media.key != _state.value.party?.media?.key) applyMedia(info.media, info.playback)
        info.playback?.let(::accept)
    }

    private fun applyMedia(media: PartyMedia, playback: PartyPlayback?) {
        val party = _state.value.party ?: return
        val changed = media.key != party.media.key
        updateParty { it.copy(media = media) }
        if (changed) {
            _playback.value = playback?.takeIf { it.mediaKey == null || it.mediaKey == media.key }
            if (_sharedStream.value?.mediaKey != media.key) _sharedStream.value = null
            ignoredWaiting.clear()
            waitStartedAt = null
            _events.tryEmit(PartyEvent.MediaChanged(media))
        } else {
            playback?.let(::accept)
        }
    }

    private fun accept(candidate: PartyPlayback) {
        val party = _state.value.party ?: return
        if (candidate.mediaKey != null && candidate.mediaKey != party.media.key) return
        if (party.hostOnly && candidate.memberId != party.hostMemberId) return
        val current = _playback.value
        if (!PartySync.isNewer(candidate, current)) return
        if (current != null && current.paused && !candidate.paused) {
            resumedAt = clock.mark()
            if (current.waiting) ignoredWaiting += PartySync.waitingMemberIds(party, emptySet())
        }
        _playback.value = candidate
        if (candidate.memberId == party.memberId) return
        val action = PartySync.actionOf(current, candidate) ?: return
        val name = party.member(candidate.memberId)?.displayName ?: return
        _notices.tryEmit(PartyNotice(name, action, candidate.positionMs, candidate.rate))
    }

    private suspend fun publish(
        paused: Boolean,
        positionMs: Long,
        rate: Float,
        waiting: Boolean,
        at: Long = clock.nowMs(),
    ) {
        val party = _state.value.party ?: return
        if (!party.canControl) return
        val current = _playback.value
        val next = nextPlayback(party, paused, positionMs, rate, waiting, party.media.key, at)
        if (current != null && current.paused && !paused) {
            resumedAt = clock.mark()
            if (current.waiting && !waiting) ignoredWaiting += PartySync.waitingMemberIds(party, emptySet())
        }
        if (!waiting) waitStartedAt = null
        _playback.value = next
        runCatching { channel?.send("state", encodePlayback(next)) }
        schedulePersist()
    }

    private fun nextPlayback(
        party: ActiveParty,
        paused: Boolean,
        positionMs: Long,
        rate: Float,
        waiting: Boolean,
        mediaKey: String,
        at: Long,
    ) = PartyPlayback(
        seq = (_playback.value?.seq ?: 0L) + 1L,
        memberId = party.memberId,
        paused = paused,
        positionMs = positionMs.coerceAtLeast(0L),
        rate = rate.coerceIn(0.25f, 4f),
        at = at,
        mediaKey = mediaKey,
        waiting = waiting,
    )

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = sessionScope?.launch {
            delay(PERSIST_DELAY)
            val party = _state.value.party ?: return@launch
            val playback = _playback.value ?: return@launch
            runRemote { PartyApi.update(party.id, deviceId(), null, playback) }?.let { applySnapshot(it) }
        }
    }

    private suspend fun heartbeatLoop() {
        while (currentScopeActive()) {
            delay(HEARTBEAT_INTERVAL)
            val party = _state.value.party ?: return
            val snapshot = runRemote { PartyApi.heartbeat(party.id, deviceId()) } ?: continue
            applySnapshot(snapshot)
        }
    }

    private suspend fun refreshEnabled() {
        if (!ServerConfigurationRepository.active.value.capabilities.watchParty) return
        val enabled = runRemote { PartyApi.enabled() } ?: return
        _enabled.value = enabled
        if (!enabled) finish(PartyEvent.Ended)
    }

    private suspend fun refineClock() {
        repeat(CLOCK_SAMPLES) {
            delay(CLOCK_SAMPLE_GAP)
            val sentAt = clock.mark()
            runCatching { PartyApi.clock() }.getOrNull()?.let { clock.addSample(it, sentAt) }
        }
    }

    private suspend fun coordinatorLoop() {
        while (currentScopeActive()) {
            delay(COORDINATOR_TICK)
            val party = _state.value.party ?: return
            if (!party.connected) continue
            if (PartySync.coordinatorId(party) != party.memberId) {
                waitStartedAt = null
                continue
            }
            val playback = _playback.value ?: continue
            if (playback.mediaKey != null && playback.mediaKey != party.media.key) continue
            val waiting = PartySync.waitingMemberIds(party, ignoredWaiting)
            if (!playback.paused) {
                val graceOver = resumedAt?.let { it.elapsedNow() > RESUME_GRACE } ?: true
                if (waiting.isNotEmpty() && graceOver) {
                    val position = PartySync.expectedPositionMs(playback, clock.nowMs())
                    publish(paused = true, positionMs = position, rate = playback.rate, waiting = true)
                    waitStartedAt = clock.mark()
                }
            } else if (playback.waiting) {
                val started = waitStartedAt ?: clock.mark().also { waitStartedAt = it }
                if (waiting.isEmpty() || started.elapsedNow() > WAIT_LIMIT) {
                    ignoredWaiting += waiting
                    publish(paused = false, positionMs = playback.positionMs, rate = playback.rate, waiting = false)
                }
            }
        }
    }

    private fun rejoinOrFinish() {
        val party = _state.value.party ?: return
        if (rejoining) return
        rejoining = true
        scope.launch {
            try {
                mutex.withLock {
                    if (_state.value.party?.id != party.id) return@withLock
                    val snapshot = runRemote { PartyApi.join(party.code, identity()) }
                    val rejoined = snapshot?.takeIf { it.error == null }?.toActiveParty()
                    if (rejoined == null) {
                        stopSession()
                        _events.tryEmit(if (snapshot?.error == "removed") PartyEvent.Removed else PartyEvent.Ended)
                        return@withLock
                    }
                    if (rejoined.memberId == party.memberId) {
                        updateParty { it.copy(members = rejoined.members, hostMemberId = rejoined.hostMemberId) }
                        return@withLock
                    }
                    val playback = _playback.value
                    stopSession()
                    startSession(rejoined, playback)
                }
            } finally {
                rejoining = false
            }
        }
    }

    private fun currentScopeActive(): Boolean = sessionScope?.isActive == true

    private fun updateParty(transform: (ActiveParty) -> ActiveParty) {
        _state.update { state -> state.party?.let { state.copy(party = transform(it)) } ?: state }
    }

    private suspend fun <T> runRemote(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Watch party call failed" }
            null
        }

    private suspend fun identity(): PartyIdentity {
        val profile = ProfileRepository.state.value.activeProfile
        return PartyIdentity(
            deviceId = deviceId(),
            displayName = profile?.name?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: DEFAULT_NAME,
            avatarUrl = profile?.let { AvatarRepository.shareableImageUrl(it) }?.takeIf { it.startsWith("https://") && it.length <= 512 },
            avatarColor = profile?.avatarColorHex?.takeIf { COLOR_PATTERN.matches(it) },
        )
    }

    private fun deviceId(): String = SyncClientIdentity.currentClientId()

    private suspend fun sendStream(stream: PartyStream?) {
        val party = _state.value.party ?: return
        _sharedStream.value = stream
        val payload = buildJsonObject {
            put("member_id", party.memberId)
            put("stream", stream?.let { PartyApi.json.encodeToJsonElement(PartyStream.serializer(), it) } ?: JsonNull)
        }
        runCatching { channel?.send("stream", payload) }
    }

    private fun encodePlayback(playback: PartyPlayback): JsonObject =
        PartyApi.json.encodeToJsonElement(PartyPlayback.serializer(), playback).jsonObject

    private fun encodePresence(presence: PartyPresence): JsonObject =
        PartyApi.json.encodeToJsonElement(PartyPresence.serializer(), presence).jsonObject

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>): T? =
        runCatching { PartyApi.json.decodeFromJsonElement(serializer, element) }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun PartySnapshot.toActiveParty(): ActiveParty? {
        val info = party ?: return null
        val self = memberId ?: return null
        return ActiveParty(
            id = info.id,
            code = info.code,
            memberId = self,
            hostMemberId = info.hostMemberId,
            members = members,
            presence = emptyMap(),
            media = info.media,
            maxMembers = info.maxMembers,
            connected = false,
            hostOnly = info.hostOnly,
        )
    }

    private const val DEFAULT_NAME = "Nuvio"
    private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    private val HEARTBEAT_INTERVAL = 2.minutes
    private val PERSIST_DELAY = 1.5.seconds
    private val COORDINATOR_TICK = 0.5.seconds
    private val RESUME_GRACE = 3.seconds
    private val WAIT_LIMIT = 15.seconds
    private val CLOCK_SAMPLE_GAP = 0.4.seconds
    private const val CLOCK_SAMPLES = 4
}

fun normalizePartyCode(input: String): String =
    input.uppercase().filter { it in PARTY_CODE_ALPHABET }

fun isValidPartyCode(input: String): Boolean = normalizePartyCode(input).length == PARTY_CODE_LENGTH

fun formatPartyCode(code: String): String =
    normalizePartyCode(code).let { if (it.length == PARTY_CODE_LENGTH) "${it.take(3)} ${it.drop(3)}" else it }

fun partyInviteLink(code: String): String =
    if (ServerConfigurationRepository.active.value.isCustom) "nuvio://party/$code" else "https://nuvio.tv/party/$code"

const val PARTY_CODE_LENGTH = 6
private const val PARTY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
