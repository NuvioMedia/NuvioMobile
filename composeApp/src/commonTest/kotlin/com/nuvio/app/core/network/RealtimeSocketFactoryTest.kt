package com.nuvio.app.core.network

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(SupabaseInternal::class)
class RealtimeSocketFactoryTest {
    private class FakeSocket : RealtimeWebsocket {
        val sent = mutableListOf<RealtimeMessage>()
        val incoming = Channel<RealtimeMessage>(Channel.UNLIMITED)
        override val hasIncomingMessages = true
        override suspend fun receive() = incoming.receive()
        override suspend fun send(message: RealtimeMessage) {
            sent += message
        }
        override suspend fun blockUntilDisconnect() = Unit
        override fun disconnect() = Unit
    }

    private fun heartbeat(ref: String) = RealtimeMessage("phoenix", "heartbeat", JsonObject(emptyMap()), ref)

    private fun reply(ref: String) = RealtimeMessage("phoenix", "phx_reply", JsonObject(emptyMap()), ref)

    @Test
    fun replaysHeartbeatLostWithPreviousSocket() = runTest {
        val sockets = mutableListOf<FakeSocket>()
        val factory = RealtimeSocketFactory { FakeSocket().also(sockets::add) }

        factory.create("wss://test").send(heartbeat("7"))
        factory.create("wss://test")

        assertEquals(listOf(heartbeat("7")), sockets[1].sent)
    }

    @Test
    fun skipsReplayAfterHeartbeatReply() = runTest {
        val sockets = mutableListOf<FakeSocket>()
        val factory = RealtimeSocketFactory { FakeSocket().also(sockets::add) }

        val first = factory.create("wss://test")
        first.send(heartbeat("7"))
        sockets[0].incoming.send(reply("7"))
        first.receive()
        factory.create("wss://test")

        assertTrue(sockets[1].sent.isEmpty())
    }
}
