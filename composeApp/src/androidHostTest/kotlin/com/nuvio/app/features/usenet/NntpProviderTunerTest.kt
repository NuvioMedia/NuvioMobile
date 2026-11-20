package com.nuvio.app.features.usenet

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** NuvioTV's tests of the same tuner. */
class NntpProviderTunerTest {
    private fun reading(connections: Int, mbps: Double, port: Int = 563, tls: Boolean = true) =
        TuneReading(port, tls, connections, mbps)

    @Test fun `usable connections leave a tenth of the limit`() {
        assertEquals(48, TunePlan.usable(53, 54))
        assertEquals(90, TunePlan.usable(100, 101))
        assertEquals(1, TunePlan.usable(1, 2))
        assertEquals(4, TunePlan.usable(5, 6))
        assertEquals(100, TunePlan.usable(null, 100))
    }

    @Test fun `the probe goes past a low allowance but not without bound`() {
        assertEquals(100, TunePlan.probeCap(20))
        assertEquals(150, TunePlan.probeCap(150))
        assertEquals(200, TunePlan.probeCap(4096))
    }

    @Test fun `the ladder ends at the usable count`() {
        assertEquals(listOf(4, 8, 12, 16, 24, 32, 48), TunePlan.ladder(48))
        assertEquals(listOf(4, 8, 10), TunePlan.ladder(10))
        assertEquals(listOf(3), TunePlan.ladder(3))
    }

    @Test fun `TLS providers only try TLS ports, the current one first`() {
        assertEquals(listOf(563 to true, 443 to true), TunePlan.ports(UsenetProvider(port = 563, tls = true)))
        assertEquals(listOf(5563 to true, 563 to true, 443 to true), TunePlan.ports(UsenetProvider(port = 5563, tls = true)))
        assertEquals(listOf(119 to false), TunePlan.ports(UsenetProvider(port = 119, tls = false)))
    }

    @Test fun `the fewest connections near the best speed are the fastest`() {
        // 16 averages 202; 8 at 190 is below 95% of it, 24 at 198 is near but uses more.
        val readings = listOf(reading(4, 100.0), reading(8, 190.0), reading(16, 200.0), reading(24, 198.0), reading(16, 204.0))
        assertEquals(16, TunePlan.fastestConnections(readings))
        assertEquals(8, TunePlan.fastestConnections(listOf(reading(8, 195.0), reading(16, 202.0))))
        assertEquals(16, TunePlan.fastestConnections(listOf(reading(8, 150.0), reading(16, 200.0), reading(24, 205.0))))
        assertNull(TunePlan.fastestConnections(emptyList()))
    }

    @Test fun `the allowance is lowered only to the limit and raised only for speed`() {
        val initial = mapOf(10 to 100.0, 32 to 130.0)
        val repeat = mapOf(10 to 101.0, 32 to 125.0)
        assertEquals(48, TunePlan.recommend(100, 16, 53, 54, emptyMap(), emptyMap()))
        assertEquals(30, TunePlan.recommend(30, 16, 53, 54, initial, repeat))
        assertEquals(300, TunePlan.recommend(300, 16, null, 200, initial, repeat))
        assertEquals(32, TunePlan.recommend(10, 32, 53, 54, initial, repeat))
        assertEquals(10, TunePlan.recommend(10, 32, 53, 54, initial, mapOf(10 to 120.0, 32 to 125.0)))
        assertEquals(10, TunePlan.recommend(10, 32, 53, 54, initial, emptyMap()))
        assertEquals(10, TunePlan.recommend(10, 32, 53, 54, mapOf(32 to 130.0), repeat))
    }

    @Test fun `the current port stays unless another is clearly faster`() {
        val current = 563 to true
        fun pick(vararg readings: TuneReading) = TunePlan.pickPort(readings.toList(), current) { _, _ ->
            error("Equal counts should need no recheck")
        }
        assertEquals(current, pick(reading(8, 100.0), reading(8, 108.0, port = 443)))
        assertEquals(443 to true, pick(reading(8, 100.0), reading(8, 130.0, port = 443)))
        assertEquals(443 to true, pick(reading(8, 130.0, port = 443)))
        assertNull(pick())
    }

    @Test fun `partially opened port samples are compared at a common count`() {
        val current = 563 to true
        val partial = listOf(reading(3, 100.0), reading(8, 180.0, port = 443))
        assertEquals(current, TunePlan.pickPort(partial, current) { old, count ->
            assertEquals(443, old.port)
            assertEquals(3, count)
            old.copy(connections = count, mbps = 105.0)
        })
        assertEquals(443 to true, TunePlan.pickPort(partial, current) { old, count ->
            old.copy(connections = count, mbps = 120.0)
        })
        val partialAlternate = listOf(reading(8, 100.0), reading(3, 140.0, port = 443))
        assertEquals(current, TunePlan.pickPort(partialAlternate, current) { _, _ -> null })
        assertEquals(current, TunePlan.pickPort(partialAlternate, current) { old, _ -> old.copy(connections = 2) })
    }

    @Test fun `a rising round stops after two steps without gain`() {
        assertFalse(TunePlan.stopRising(listOf(50.0, 100.0)))
        assertFalse(TunePlan.stopRising(listOf(50.0, 100.0, 150.0, 151.0)))
        assertTrue(TunePlan.stopRising(listOf(50.0, 100.0, 150.0, 151.0, 149.0)))
        assertTrue(TunePlan.stopRising(listOf(100.0, 0.0, 0.0)))
    }

    @Test fun `message ids are read from segments`() {
        val nzb = """<file subject="x"><segments>
            <segment bytes="739666" number="1">abc-1@nyuu</segment>
            <segment number="2" bytes="10"> <def@host> </segment></segments></file>"""
        assertEquals(listOf("abc-1@nyuu", "def@host"), TunePlan.messageIds(nzb))
    }

    @Test fun `a body is read to its end without consuming the next reply`() {
        val wire = "line one\r\n..dot stuffed\r\n.\r\n222 next\r\nx\r\n.\r\n".toByteArray()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            thread(isDaemon = true) { server.accept().use { it.getOutputStream().write(wire); Thread.sleep(500) } }
            NntpSession(Socket("127.0.0.1", server.localPort)).use { session ->
                assertEquals(28L, session.skipBody())
                assertEquals("222 next", session.readLine())
                assertEquals(6L, session.skipBody())
            }
        }
    }

    @Test fun `an empty body ends at once`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            thread(isDaemon = true) { server.accept().use { it.getOutputStream().write(".\r\n205 bye\r\n".toByteArray()); Thread.sleep(500) } }
            NntpSession(Socket("127.0.0.1", server.localPort)).use { session ->
                assertEquals(3L, session.skipBody())
                assertEquals("205 bye", session.readLine())
            }
        }
    }

    /** Accepts [limit] signed-in connections at a time and serves [article] for every BODY; the rest are refused. */
    private class FakeProvider(private val limit: Int, private val missing: Boolean = false,
        private val dropGreetings: Set<Int> = emptySet()) : AutoCloseable {
        private val attempts = AtomicInteger()
        private val server = ServerSocket(0, 200, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val open = AtomicInteger()
        val most = AtomicInteger()
        val refused = AtomicInteger()
        /** Signed-in connections that ended without QUIT: some providers keep counting those for minutes. */
        val abandoned = AtomicInteger()
        val bodies = AtomicInteger()
        private val article = ByteArrayOutputStream().apply {
            repeat(2000) { write("=ybody line $it with some padding to make it longer\r\n".toByteArray()) }
            write("..starts with a dot\r\n.\r\n".toByteArray())
        }.toByteArray()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) = socket.use {
            if (attempts.incrementAndGet() in dropGreetings) return@use
            val output = socket.getOutputStream()
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val counted = synchronized(this) {
                if (open.get() >= limit) false else { most.accumulateAndGet(open.incrementAndGet(), ::maxOf); true }
            }
            var quit = false
            var signedIn = false
            try {
                if (!counted) {
                    refused.incrementAndGet()
                    output.write("502 too many connections\r\n".toByteArray()); output.flush(); return@use
                }
                output.write("200 welcome\r\n".toByteArray()); output.flush()
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        line.startsWith("AUTHINFO USER") -> output.write("381 more\r\n".toByteArray())
                        line.startsWith("AUTHINFO PASS") -> { signedIn = true; output.write("281 ok\r\n".toByteArray()) }
                        line.startsWith("BODY") -> if (missing) output.write("430 no such article\r\n".toByteArray())
                            else { output.write("222 0 body\r\n".toByteArray()); output.write(article); bodies.incrementAndGet() }
                        line == "QUIT" -> { quit = true; output.write("205 bye\r\n".toByteArray()); output.flush(); break }
                        else -> output.write("500 what\r\n".toByteArray())
                    }
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                if (counted) open.decrementAndGet()
                if (signedIn && !quit) abandoned.incrementAndGet()
            }
        }

        override fun close() = server.close()
    }

    private fun provider(port: Int, connections: Int) = UsenetProvider(name = "Test", host = "127.0.0.1", port = port,
        tls = false, username = "user", password = "pass", connections = connections)

    private fun tuner(articles: List<String> = List(100) { "a$it@test" }) =
        NntpProviderTuner(NntpProviderTester(), { articles }, stepMs = 400, settleMs = 50, warmupMs = 100)

    @Test fun `finds the limit and recommends staying under it`() = runBlocking {
        FakeProvider(limit = 13).use { server ->
            val phases = mutableListOf<TuneProgress.Phase>()
            val outcome = tuner().tune(provider(server.port, connections = 40), allowPrivateNetwork = true, pipeline = 2) {
                synchronized(phases) { phases += it.phase }
            }
            val result = (outcome as TuneOutcome.Success).result
            assertEquals(13, result.limit)
            assertEquals(14, result.tried)
            // 13 less a tenth: 12. The allowance of 40 was over the limit, so it comes down.
            assertEquals(12, result.connections)
            assertEquals(server.port, result.port)
            assertFalse(result.tls)
            assertTrue(result.mbps > 0)
            assertTrue(result.downloadedBytes > 0)
            // Never more than the limit opened at once while measuring, including a single confirmation after the batch refusal.
            assertTrue(server.most.get() <= 13)
            assertEquals(4, server.refused.get())
            assertTrue(result.readings.all { it.connections <= 12 })
            assertEquals(listOf(TuneProgress.Phase.LIMIT, TuneProgress.Phase.PORT, TuneProgress.Phase.CONNECTIONS),
                phases.distinct())
            // Every connection signed out, the measured ones after reading the bodies still due.
            Thread.sleep(300)
            assertEquals(0, server.abandoned.get())
            assertEquals(0, server.open.get())
        }
    }

    @Test fun `a finished step waits for the bodies still due, within bounds`() {
        // 16 connections, 4 bodies of 0.7 MB each, at 20 MB/s: 2.24 s expected, waited up to twice that.
        assertEquals(4480L, TunePlan.drainMs(16, 4, 700_000.0, 20_000_000.0))
        assertEquals(TunePlan.MIN_DRAIN_MS, TunePlan.drainMs(4, 4, 700_000.0, 20_000_000.0))
        assertEquals(TunePlan.MAX_DRAIN_MS, TunePlan.drainMs(48, 4, 700_000.0, 1_000_000.0))
        assertEquals(TunePlan.MIN_DRAIN_MS, TunePlan.drainMs(8, 4, 0.0, 20_000_000.0))
        assertEquals(TunePlan.MIN_DRAIN_MS, TunePlan.drainMs(8, 4, 700_000.0, 0.0))
    }

    @Test fun `stopping while measuring signs every connection out`() = runBlocking {
        FakeProvider(limit = 30).use { server ->
            val measuring = CompletableDeferred<Unit>()
            val run = launch(Dispatchers.IO) {
                tuner().tune(provider(server.port, connections = 20), true, 4) {
                    if (it.phase == TuneProgress.Phase.CONNECTIONS) measuring.complete(Unit)
                }
            }
            withTimeout(30_000) { measuring.await() }
            delay(200)
            val start = System.nanoTime()
            run.cancelAndJoin()
            assertTrue((System.nanoTime() - start) / 1_000_000 < TunePlan.MIN_DRAIN_MS + 2_000)
            Thread.sleep(300)
            assertEquals(0, server.abandoned.get())
            assertEquals(0, server.open.get())
        }
    }

    @Test fun `without a refusal the limit is unknown and the probe stops at its cap`() = runBlocking {
        FakeProvider(limit = 1000).use { server ->
            val result = (tuner().tune(provider(server.port, connections = 6), true, 1) {} as TuneOutcome.Success).result
            assertNull(result.limit)
            assertEquals(TunePlan.PROBE_CAP, result.tried)
            assertTrue(result.readings.any { it.port == server.port && it.connections == 6 })
            assertEquals(0, server.refused.get())
            // A faster count may raise it; it is never lowered below the current allowance.
            assertTrue(result.connections >= 6)
        }
    }

    @Test fun `two lost greetings leave the limit unknown and preserve the allowance`() = runBlocking {
        FakeProvider(limit = 40, dropGreetings = setOf(8, 9)).use { server ->
            val result = (tuner().tune(provider(server.port, 40), true, 2) {} as TuneOutcome.Success).result
            assertNull(result.limit)
            assertEquals(7, result.tried)
            assertEquals(40, result.connections)
            assertEquals(0, server.refused.get())
        }
    }

    @Test fun `missing test articles and missing test data are reported`() = runBlocking {
        FakeProvider(limit = 20, missing = true).use { server ->
            assertEquals(TuneOutcome.Failed(TuneFailure.MISSING_ARTICLES),
                tuner().tune(provider(server.port, 10), true, 2) {})
        }
        FakeProvider(limit = 20).use { server ->
            assertEquals(TuneOutcome.Failed(TuneFailure.TEST_DATA),
                tuner(articles = emptyList()).tune(provider(server.port, 10), true, 2) {})
        }
    }

    @Test fun `restarting closes sockets stalled on the previous greeting`() = runBlocking {
        val tuner = tuner(articles = emptyList())
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { stalled ->
            stalled.soTimeout = 5000
            val old = launch(Dispatchers.IO) { tuner.tune(provider(stalled.localPort, 8), true, 1) {} }
            val sockets = mutableListOf<Socket>()
            try {
                withContext(Dispatchers.IO) {
                    repeat(TunePlan.PROBE_BATCH) { sockets += stalled.accept().apply { soTimeout = 1000 } }
                }
                old.cancel()
                FakeProvider(limit = 2).use { server ->
                    val restarted = tuner.tune(provider(server.port, 2), true, 1) { progress ->
                        if (progress.phase == TuneProgress.Phase.LIMIT && progress.connections == 0) {
                            sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
                        }
                    }
                    assertEquals(TuneOutcome.Failed(TuneFailure.TEST_DATA), restarted)
                }
            } finally {
                old.cancel()
                sockets.forEach { it.close() }
            }
        }
    }

    @Test fun `a refused first connection is a setup failure`() = runBlocking {
        FakeProvider(limit = 0).use { server ->
            assertEquals(TuneOutcome.Failed(TuneFailure.CONNECT, UsenetProviderTestResult.REFUSED),
                tuner().tune(provider(server.port, 10), true, 2) {})
        }
        assertEquals(TuneOutcome.Failed(TuneFailure.CONNECT, UsenetProviderTestResult.PRIVATE_NETWORK),
            tuner().tune(provider(1, 10), allowPrivateNetwork = false, pipeline = 2) {})
    }
}
