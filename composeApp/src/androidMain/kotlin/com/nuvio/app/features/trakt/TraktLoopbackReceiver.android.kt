package com.nuvio.app.features.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

internal actual val traktLoopbackReceiverSupported: Boolean = true

internal actual suspend fun awaitTraktLoopbackRedirect(
    port: Int,
    path: String,
    timeoutMillis: Long,
    responseHtml: String,
): String? = withContext(Dispatchers.IO) {
    ServerSocket().use { server ->
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server.soTimeout = ACCEPT_SLICE_MS
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val client = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                null
            } ?: continue
            val target = client.use { socket -> answerRequest(socket, path, responseHtml) }
            if (target != null) return@withContext target
        }
        null
    }
}

private fun answerRequest(socket: Socket, path: String, responseHtml: String): String? {
    socket.soTimeout = CLIENT_READ_TIMEOUT_MS
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
    val requestLine = runCatching { reader.readLine() }.getOrNull().orEmpty()
    // Drain the request headers so closing the socket does not reset the browser's connection.
    runCatching {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
    }
    val parts = requestLine.split(' ')
    val target = parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "GET" && it.startsWith("/") }
    val matches = target != null && target.substringBefore('?') == path
    val body = if (matches) responseHtml else "Not found"
    val bodyBytes = body.encodeToByteArray()
    val head = buildString {
        append(if (matches) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
        append(if (matches) "Content-Type: text/html; charset=utf-8\r\n" else "Content-Type: text/plain; charset=utf-8\r\n")
        append("Content-Length: ${bodyBytes.size}\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n\r\n")
    }
    runCatching {
        val output = socket.getOutputStream()
        output.write(head.encodeToByteArray())
        output.write(bodyBytes)
        output.flush()
    }
    return if (matches) target else null
}

private const val ACCEPT_SLICE_MS = 1_000
private const val CLIENT_READ_TIMEOUT_MS = 5_000
