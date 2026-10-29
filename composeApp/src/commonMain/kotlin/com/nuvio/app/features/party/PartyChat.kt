package com.nuvio.app.features.party

import co.touchlab.kermit.Logger
import com.nuvio.app.core.sync.SyncClientIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

enum class PartyChatStatus {
    Sent,
    Sending,
    Failed,
}

data class PartyChatMessage(
    val key: String,
    val id: Long?,
    val memberId: String,
    val name: String,
    val avatarUrl: String?,
    val avatarColor: String?,
    val body: String,
    val sentAt: Long,
    val mine: Boolean,
    val clientId: String? = null,
    val status: PartyChatStatus = PartyChatStatus.Sent,
)

internal data class PartyChatRoom(
    val partyId: String,
    val memberId: String,
    val members: List<PartyMember>,
)

internal data class PartyChatMerge(
    val messages: List<PartyChatMessage>,
    val added: List<PartyChatMessage>,
)

@OptIn(ExperimentalCoroutinesApi::class)
object PartyChat {
    private val log = Logger.withTag("PartyChat")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val syncLock = Mutex()

    private val _messages = MutableStateFlow<List<PartyChatMessage>>(emptyList())
    val messages: StateFlow<List<PartyChatMessage>> = _messages.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    val draft = MutableStateFlow("")

    private var room: PartyChatRoom? = null
    private val ownMemberIds = mutableSetOf<String>()
    private var loaded = false
    private var open = false

    fun setOpen(value: Boolean) {
        scope.launch {
            open = value
            if (value) _unread.value = 0
        }
    }

    fun send(text: String) {
        val body = text.trim().take(MAX_LENGTH)
        if (body.isEmpty()) return
        scope.launch {
            val current = room ?: return@launch
            val self = current.members.firstOrNull { it.id == current.memberId }
            val clientId = newClientId()
            val message = PartyChatMessage(
                key = clientId,
                id = null,
                memberId = current.memberId,
                name = self?.displayName.orEmpty(),
                avatarUrl = self?.avatarUrl,
                avatarColor = self?.avatarColor,
                body = body,
                sentAt = PartyRepository.nowMs(),
                mine = true,
                clientId = clientId,
                status = PartyChatStatus.Sending,
            )
            _messages.update { it + message }
            deliver(current.partyId, message)
        }
    }

    fun retry(key: String) {
        scope.launch {
            val target = room?.partyId ?: return@launch
            val message = _messages.value.firstOrNull { it.key == key && it.status == PartyChatStatus.Failed } ?: return@launch
            setStatus(key, PartyChatStatus.Sending)
            deliver(target, message)
        }
    }

    internal fun start(next: PartyChatRoom) {
        scope.launch {
            if (room?.partyId != next.partyId) reset()
            room = next
            ownMemberIds += next.memberId
            sync()
        }
    }

    internal fun stop() {
        scope.launch { reset() }
    }

    internal fun refresh() {
        scope.launch {
            sync()
            val target = room?.partyId ?: return@launch
            _messages.value.filter { it.status == PartyChatStatus.Failed }.forEach { message ->
                setStatus(message.key, PartyChatStatus.Sending)
                deliver(target, message)
            }
        }
    }

    internal fun receive(payload: JsonObject) {
        scope.launch {
            if (room == null) return@launch
            val message = runCatching { PartyApi.json.decodeFromJsonElement(PartyChatPayload.serializer(), payload) }
                .getOrNull() ?: return@launch
            apply(listOf(message), replaceOlder = false, notify = true)
        }
    }

    private suspend fun sync() {
        syncLock.withLock {
            val target = room?.partyId ?: return
            val after = if (loaded) _messages.value.lastOrNull { it.id != null }?.id else null
            val history = try {
                PartyApi.listMessages(target, SyncClientIdentity.currentClientId(), after)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Chat history failed" }
                return
            }
            if (target != room?.partyId || history.error != null) return
            val firstLoad = !loaded
            loaded = true
            apply(
                payloads = history.messages,
                replaceOlder = after != null && history.messages.size >= HISTORY_LIMIT,
                notify = !firstLoad,
            )
        }
    }

    private suspend fun deliver(target: String, message: PartyChatMessage) {
        val result = try {
            PartyApi.sendMessage(target, SyncClientIdentity.currentClientId(), message.body, message.key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Chat message failed" }
            null
        }
        if (target != room?.partyId) return
        val sent = result?.message
        if (sent == null) {
            setStatus(message.key, PartyChatStatus.Failed)
            return
        }
        apply(listOf(sent), replaceOlder = false, notify = false)
    }

    private fun apply(payloads: List<PartyChatPayload>, replaceOlder: Boolean, notify: Boolean) {
        val result = mergePartyChat(
            current = _messages.value,
            incoming = payloads.map { it.toMessage() },
            replaceOlder = replaceOlder,
            limit = MESSAGE_LIMIT,
        )
        _messages.value = result.messages
        if (!notify || open) return
        val fromOthers = result.added.filterNot { it.mine }
        if (fromOthers.isEmpty()) return
        _unread.update { it + fromOthers.size }
    }

    private fun setStatus(key: String, status: PartyChatStatus) {
        _messages.update { list -> list.map { if (it.key == key && it.id == null) it.copy(status = status) else it } }
    }

    private fun reset() {
        room = null
        ownMemberIds.clear()
        loaded = false
        draft.value = ""
        _messages.value = emptyList()
        _unread.value = 0
    }

    private fun PartyChatPayload.toMessage() = PartyChatMessage(
        key = "id:$id",
        id = id,
        memberId = memberId,
        name = name,
        avatarUrl = avatarUrl,
        avatarColor = avatarColor,
        body = body,
        sentAt = sentAt,
        mine = mine || memberId in ownMemberIds,
        clientId = clientId,
    )

    private fun newClientId(): String =
        buildString { repeat(CLIENT_ID_LENGTH) { append(CLIENT_ID_ALPHABET[Random.nextInt(CLIENT_ID_ALPHABET.length)]) } }

    const val MAX_LENGTH = 500
    private const val HISTORY_LIMIT = 100
    private const val MESSAGE_LIMIT = 200
    private const val CLIENT_ID_LENGTH = 20
    private const val CLIENT_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}

internal fun mergePartyChat(
    current: List<PartyChatMessage>,
    incoming: List<PartyChatMessage>,
    replaceOlder: Boolean,
    limit: Int,
): PartyChatMerge {
    val oldestIncoming = incoming.mapNotNull { it.id }.minOrNull()
    val base = if (replaceOlder && oldestIncoming != null) {
        current.filter { it.id == null || it.id >= oldestIncoming }
    } else {
        current
    }
    val confirmed = base.filter { it.id != null }.associateByTo(mutableMapOf()) { it.id }
    val pending = base.filter { it.id == null }.toMutableList()
    val added = mutableListOf<PartyChatMessage>()
    incoming.sortedBy { it.id }.forEach { message ->
        val id = message.id ?: return@forEach
        if (id in confirmed) return@forEach
        val local = message.clientId
            ?.takeIf { message.mine }
            ?.let { clientId -> pending.firstOrNull { it.key == clientId } }
        if (local != null) {
            pending.remove(local)
            confirmed[id] = message.copy(key = local.key)
        } else {
            confirmed[id] = message
            added += message
        }
    }
    val merged = confirmed.values.sortedBy { it.id } + pending
    return PartyChatMerge(merged.takeLast(limit), added)
}
