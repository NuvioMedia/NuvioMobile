package com.nuvio.app.features.usenet

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** [session] is open and signed in only when [result] is [UsenetProviderTestResult.SUCCESS]. */
internal class OpenResult(val result: UsenetProviderTestResult, val session: NntpSession? = null)

/**
 * One NNTP connection with its own read buffer, so status lines and multi-line bodies can be read
 * from the same stream without reading past the end of a reply (pipelined replies follow it).
 */
internal class NntpSession(private val socket: Socket) : Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buffer = ByteArray(64 * 1024)
    private var position = 0
    private var limit = 0

    var timeoutMs: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    fun command(line: String) {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Several commands in one write, for pipelining. */
    fun commands(lines: List<String>) {
        output.write(lines.joinToString("") { "$it\r\n" }.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun fill(): Boolean {
        position = 0
        limit = input.read(buffer)
        if (limit <= 0) { limit = 0; return false }
        return true
    }

    /** A status line without its CRLF; null when the connection closed. */
    fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            if (position == limit && !fill()) return null
            while (position < limit) {
                val byte = buffer[position++].toInt() and 0xFF
                if (byte == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length < MAX_LINE) line.append(byte.toChar())
            }
        }
    }

    /**
     * Reads a multi-line body up to its terminating "." line, reporting the bytes as they arrive;
     * returns its size. Only the body is consumed: a following pipelined reply stays buffered.
     */
    fun skipBody(onBytes: (Int) -> Unit = {}): Long {
        // The status line ended with CRLF, so the body starts at the beginning of a line.
        var state = AT_LINE_START
        var total = 0L
        while (true) {
            if (position == limit && !fill()) throw IOException("Connection closed")
            val start = position
            while (position < limit) {
                val byte = buffer[position++]
                state = when (state) {
                    AFTER_CR -> if (byte == LF) AT_LINE_START else if (byte == CR) AFTER_CR else IN_LINE
                    AT_LINE_START -> if (byte == DOT) AFTER_DOT else if (byte == CR) AFTER_CR else IN_LINE
                    AFTER_DOT -> if (byte == CR) AFTER_DOT_CR else IN_LINE
                    AFTER_DOT_CR -> if (byte == LF) DONE else if (byte == CR) AFTER_CR else IN_LINE
                    else -> if (byte == CR) AFTER_CR else IN_LINE
                }
                if (state == DONE) break
            }
            val read = position - start
            total += read
            onBytes(read)
            if (state == DONE) return total
        }
    }

    override fun close() { runCatching { socket.close() } }

    private companion object {
        const val MAX_LINE = 1024
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
        const val DOT = '.'.code.toByte()
        const val IN_LINE = 0
        const val AFTER_CR = 1
        const val AT_LINE_START = 2
        const val AFTER_DOT = 3
        const val AFTER_DOT_CR = 4
        const val DONE = 5
    }
}

/**
 * Connects and signs in to an NNTP provider the way playback will, so settings
 * can be checked before anything is played. Results are categories only: raw
 * errors could echo credentials.
 */
internal class NntpProviderTester(private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName) {

    suspend fun test(provider: UsenetProvider, allowPrivateNetwork: Boolean): UsenetProviderTestResult =
        runInterruptible(Dispatchers.IO) {
            val opened = open(provider, allowPrivateNetwork)
            opened.session?.use { runCatching { it.command("QUIT") } }
            opened.result
        }

    /**
     * A signed-in connection ([OpenResult.session], closed by the caller) or why there is none.
     * Blocking; the same dial, TLS and sign-in steps as [test].
     */
    internal fun open(provider: UsenetProvider, allowPrivateNetwork: Boolean, addresses: List<InetAddress>? = null,
        newSocket: () -> Socket = ::Socket): OpenResult {
        val usable = addresses ?: resolveUsable(provider, allowPrivateNetwork).let { (found, failure) ->
            if (failure != null) return OpenResult(failure)
            found
        }
        val socket = connect(usable, provider.port, newSocket) ?: return OpenResult(UsenetProviderTestResult.UNREACHABLE)
        var keep = false
        try {
            socket.soTimeout = TIMEOUT_MS
            val stream = if (provider.tls) secure(socket, provider.host) else socket
            val session = NntpSession(stream)
            val result = session(session, provider)
            keep = result == UsenetProviderTestResult.SUCCESS
            return if (keep) OpenResult(result, session) else OpenResult(result)
        } catch (_: SSLException) { return OpenResult(UsenetProviderTestResult.TLS) }
        catch (_: IOException) { return OpenResult(UsenetProviderTestResult.UNREACHABLE) }
        finally { if (!keep) runCatching { socket.close() } }
    }

    /** The provider's addresses this device may dial, or why there are none. */
    internal fun resolveUsable(provider: UsenetProvider, allowPrivateNetwork: Boolean): Pair<List<InetAddress>, UsenetProviderTestResult?> {
        val addresses = try { resolve(provider.host.removeSurrounding("[", "]")) }
            catch (_: IOException) { return emptyList<InetAddress>() to UsenetProviderTestResult.UNREACHABLE }
        // Mirror the engine's dial policy, or a test could pass where playback is refused:
        // it skips the addresses it may not dial and tries the others.
        val usable = addresses.filter { allowed(it, allowPrivateNetwork) }
        return usable to if (usable.isEmpty()) UsenetProviderTestResult.PRIVATE_NETWORK else null
    }

    /**
     * Tries each address in turn within one time budget, like the engine's dialer: a host whose
     * first address does not answer (IPv6 without a route, one server down) still connects.
     */
    private fun connect(addresses: List<InetAddress>, port: Int, newSocket: () -> Socket): Socket? {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        addresses.forEachIndexed { i, address ->
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (left <= 0) return null
            // Share what is left, so a silent address leaves time for the next ones.
            val timeout = maxOf(left / (addresses.size - i), minOf(left, MIN_ATTEMPT_MS))
            val socket = newSocket()
            try {
                socket.connect(InetSocketAddress(address, port), timeout)
                return socket
            } catch (_: IOException) { runCatching { socket.close() } }
        }
        return null
    }

    private fun session(session: NntpSession, provider: UsenetProvider): UsenetProviderTestResult {
        fun code() = session.readLine()?.take(3)?.toIntOrNull() ?: throw IOException("Connection closed")
        // 400 and 502 greetings mean the service is unavailable, often too many connections.
        if (code() !in 200..201) return UsenetProviderTestResult.REFUSED
        if (provider.username.isNotEmpty() || provider.password.isNotEmpty()) {
            session.command("AUTHINFO USER ${provider.username}")
            var reply = code()
            if (reply == 381) {
                session.command("AUTHINFO PASS ${provider.password}")
                reply = code()
            }
            when (reply) {
                281 -> Unit
                // Some providers report too many connections (or a spent block) as 502.
                502 -> return UsenetProviderTestResult.REFUSED
                else -> return UsenetProviderTestResult.AUTH
            }
        }
        return UsenetProviderTestResult.SUCCESS
    }

    private fun secure(socket: Socket, host: String): Socket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.startHandshake()
        return ssl
    }

    internal companion object {
        const val TIMEOUT_MS = 10_000
        const val MIN_ATTEMPT_MS = 2_000

        fun allowed(address: InetAddress, allowPrivate: Boolean): Boolean {
            if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return false
            val bytes = address.address
            val private = address.isLoopbackAddress || address.isSiteLocalAddress ||
                (bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 0x40) ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
            return allowPrivate || !private
        }
    }
}
