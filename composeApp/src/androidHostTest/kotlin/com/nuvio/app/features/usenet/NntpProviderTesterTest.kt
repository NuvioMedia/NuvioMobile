package com.nuvio.app.features.usenet

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NntpProviderTesterTest {
    /** Answers one connection: the greeting, then [replies] for each command prefix. */
    private class FakeNntp(greeting: String, replies: Map<String, String> = emptyMap()) : AutoCloseable {
        // Only on 127.0.0.1, so other loopback addresses do not answer.
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream()
                        output.write("$greeting\r\n".toByteArray()); output.flush()
                        while (true) {
                            val line = input.readLine() ?: break
                            commands += line
                            val reply = replies.entries.firstOrNull { line.startsWith(it.key) }?.value ?: "205 bye"
                            output.write("$reply\r\n".toByteArray()); output.flush()
                            if (line == "QUIT") break
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun provider(port: Int, username: String = "user", password: String = "pass") =
        UsenetProvider(name = "Test", host = "127.0.0.1", port = port, tls = false, username = username, password = password)

    private val auth = mapOf("AUTHINFO USER" to "381 password required", "AUTHINFO PASS" to "281 ok")

    @Test fun `signs in and quits`() = runBlocking {
        FakeNntp("200 welcome", auth).use { server ->
            assertEquals(UsenetProviderTestResult.SUCCESS, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
            assertEquals(listOf("AUTHINFO USER user", "AUTHINFO PASS pass", "QUIT"), server.commands.toList())
        }
    }

    @Test fun `providers without credentials only need a greeting`() = runBlocking {
        FakeNntp("201 no posting").use { server ->
            assertEquals(UsenetProviderTestResult.SUCCESS,
                NntpProviderTester().test(provider(server.port, "", ""), allowPrivateNetwork = true))
            assertFalse(server.commands.any { it.startsWith("AUTHINFO") })
        }
    }

    @Test fun `rejected password and refused connections are reported separately`() = runBlocking {
        FakeNntp("200 welcome", auth + ("AUTHINFO PASS" to "481 rejected")).use { server ->
            assertEquals(UsenetProviderTestResult.AUTH, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
        }
        FakeNntp("502 too many connections").use { server ->
            assertEquals(UsenetProviderTestResult.REFUSED, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
        }
    }

    @Test fun `local addresses need self hosted servers and closed ports are unreachable`() = runBlocking {
        FakeNntp("200 welcome", auth).use { server ->
            assertEquals(UsenetProviderTestResult.PRIVATE_NETWORK,
                NntpProviderTester().test(provider(server.port), allowPrivateNetwork = false))
            assertTrue("no connection may be made", server.commands.isEmpty())
        }
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(UsenetProviderTestResult.UNREACHABLE, NntpProviderTester().test(provider(closed), allowPrivateNetwork = true))
    }

    @Test fun `an address that does not answer does not hide the next one`() = runBlocking {
        FakeNntp("200 welcome", auth).use { server ->
            // Like a host whose IPv6 address has no route: the first address fails, the second answers.
            val addresses = arrayOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
            val tester = NntpProviderTester { addresses }
            assertEquals(UsenetProviderTestResult.SUCCESS,
                tester.test(provider(server.port).copy(host = "news.example.com"), allowPrivateNetwork = true))
            assertEquals(listOf("AUTHINFO USER user", "AUTHINFO PASS pass", "QUIT"), server.commands.toList())
        }
    }
}
