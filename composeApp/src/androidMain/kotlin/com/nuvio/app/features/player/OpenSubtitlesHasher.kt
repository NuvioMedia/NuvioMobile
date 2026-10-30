package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 *
 * Algorithm: `hash = fileSize + sum(first 64 KB as longs LE) + sum(last 64 KB as longs LE)`
 *
 * @see <a href="https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes">
 *     OpenSubtitles hash algorithm</a>
 */
internal object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(
        url: String,
        headers: Map<String, String>,
    ): Result? = withContext(Dispatchers.IO) {
        try {
            val fileSize = getContentLength(url, headers) ?: return@withContext null
            if (fileSize < CHUNK_SIZE * 2) return@withContext null

            var hash = fileSize
            hash += readChunkSum(url, headers, offset = 0, length = CHUNK_SIZE)
            hash += readChunkSum(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE)

            Result(
                hash = "%016x".format(hash),
                fileSize = fileSize,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getContentLength(url: String, headers: Map<String, String>): Long? {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "HEAD",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            if (acceptRanges.equals("none", ignoreCase = true)) return null
            val len = connection.getHeaderField("Content-Length")?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { it >= 0L }
            if (len != null && len > 0) len else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readChunkSum(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        length: Long,
    ): Long {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "GET",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            range = "bytes=$offset-${offset + length - 1}",
        )
        return try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) return 0L
            val stream: InputStream = connection.inputStream ?: return 0L
            val buf = ByteArray(LONG_SIZE)
            var sum = 0L
            var remaining = length
            while (remaining >= LONG_SIZE) {
                var read = 0
                while (read < LONG_SIZE) {
                    val n = stream.read(buf, read, LONG_SIZE - read)
                    if (n < 0) break
                    read += n
                }
                if (read < LONG_SIZE) break
                sum += buf.toLongLE()
                remaining -= LONG_SIZE
            }
            sum
        } catch (_: Exception) {
            0L
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.toLongLE(): Long {
        var v = 0L
        for (i in 0 until LONG_SIZE) v = v or ((this[i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
