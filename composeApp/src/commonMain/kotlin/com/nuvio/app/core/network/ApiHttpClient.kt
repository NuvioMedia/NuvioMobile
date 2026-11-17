package com.nuvio.app.core.network

import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.readAvailable

internal expect fun createApiHttpClient(): HttpClient

internal const val API_RESPONSE_MAX_BYTES = 16 * 1024 * 1024

internal suspend fun readBoundedResponseBody(
    channel: ByteReadChannel,
    contentLength: Long?,
    maxBytes: Int = API_RESPONSE_MAX_BYTES,
): String {
    if (contentLength != null && contentLength > maxBytes) throw IOException("Response exceeds size limit")
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
        val buffer = ByteArray(minOf(8_192, maxBytes - total + 1))
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw IOException("Response exceeds size limit")
        chunks += buffer.copyOf(count)
    }
    val bytes = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes.decodeToString()
}
