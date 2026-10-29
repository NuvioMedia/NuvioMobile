package com.nuvio.app.features.party

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject

internal class PartyChannel(
    private val partyId: String,
    private val memberId: String,
    private val events: List<String> = PARTY_EVENTS,
) {
    private var channel: RealtimeChannel? = null
    private var jobs: List<Job> = emptyList()

    val isConnected: Boolean
        get() = channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    suspend fun open(
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onConnected: (Boolean) -> Unit,
        onPresence: ((joins: Map<String, Presence>, leaves: Map<String, Presence>) -> Unit)? = null,
    ) {
        val created = SupabaseProvider.client.channel("watch-party:$partyId") {
            isPrivate = true
            broadcast {
                receiveOwnBroadcasts = false
                acknowledgeBroadcasts = false
            }
            presence {
                key = memberId
            }
        }
        channel = created
        jobs = events.map { event ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                created.broadcastFlow<JsonObject>(event).collect { payload -> onMessage(event, payload) }
            }
        } + listOfNotNull(
            onPresence?.let { handler ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    created.presenceChangeFlow().collect { action -> handler(action.joins, action.leaves) }
                }
            },
        ) + scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.status.collect { status -> onConnected(status == RealtimeChannel.Status.SUBSCRIBED) }
        }
        yield()
        created.subscribe(blockUntilSubscribed = false)
    }

    suspend fun send(event: String, payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        current.broadcast(event, payload)
    }

    suspend fun track(payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        current.track(payload)
    }

    suspend fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val current = channel ?: return
        channel = null
        runCatching { SupabaseProvider.client.realtime.removeChannel(current) }
    }

    companion object {
        val PARTY_EVENTS = listOf("state", "state_request", "status", "control", "member_joined", "member_left", "ended", "media", "stream", "chat")
        val COMPANION_EVENTS = listOf("chat", "media", "member_left", "ended")
    }
}
