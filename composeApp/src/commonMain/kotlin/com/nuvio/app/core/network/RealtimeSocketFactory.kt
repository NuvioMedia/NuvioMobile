package com.nuvio.app.core.network

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

@OptIn(SupabaseInternal::class)
internal class RealtimeSocketFactory(
    private val connect: suspend (String) -> RealtimeWebsocket,
) : RealtimeWebsocketFactory {
    @Volatile
    private var unansweredHeartbeat: String? = null

    override suspend fun create(url: String): RealtimeWebsocket {
        val socket = Socket(connect(url))
        unansweredHeartbeat?.let { ref -> socket.send(RealtimeMessage(PHOENIX, HEARTBEAT, JsonObject(emptyMap()), ref)) }
        return socket
    }

    private inner class Socket(private val socket: RealtimeWebsocket) : RealtimeWebsocket by socket {
        override suspend fun send(message: RealtimeMessage) {
            if (message.topic == PHOENIX && message.event == HEARTBEAT) unansweredHeartbeat = message.ref
            socket.send(message)
        }

        override suspend fun receive(): RealtimeMessage {
            val message = socket.receive()
            if (message.topic == PHOENIX && message.ref == unansweredHeartbeat) unansweredHeartbeat = null
            return message
        }
    }

    private companion object {
        const val PHOENIX = "phoenix"
        const val HEARTBEAT = "heartbeat"
    }
}
