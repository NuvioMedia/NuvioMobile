package com.nuvio.app.features.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Gives HLS media playlists the init segment from [HlsParameterSetInit] when their segments need
 * it. Playlists are read whole and handed on, rewritten or not. Everything else passes straight
 * through, including the `data:` init segment, which [upstream] must therefore be able to open
 * (a DefaultDataSource can).
 */
@OptIn(UnstableApi::class)
internal class HlsParameterSetInitDataSource(
    private val upstream: DataSource,
    private val createSegmentSource: () -> DataSource
) : DataSource {

    private var playlist: ByteArray? = null
    private var playlistReadPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        playlist = null
        playlistReadPosition = 0
        val length = upstream.open(dataSpec)
        if (dataSpec.position != 0L || !isPlaylist(dataSpec.uri, upstream.responseHeaders)) return length

        val body = readAll(upstream)
        val baseUri = (upstream.uri ?: dataSpec.uri).toString()
        // Healthy streams pay for this check too, so a slow segment host must not hold up the start.
        val rewritten = resultWithin(CHECK_TIMEOUT_MS) {
            HlsParameterSetInit.withParameterSetInit(body.toString(Charsets.UTF_8), baseUri, ::fetchSegmentHead)
        }
        if (rewritten != null) Log.i(TAG, "Added an SPS/PPS init segment to $baseUri")
        val served = rewritten?.toByteArray(Charsets.UTF_8) ?: body
        playlist = served
        return served.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = playlist ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val remaining = data.size - playlistReadPosition
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        data.copyInto(buffer, offset, playlistReadPosition, playlistReadPosition + count)
        playlistReadPosition += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        playlist = null
        upstream.close()
    }

    private fun fetchSegmentHead(uri: String): ByteArray? {
        val source = createSegmentSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse(uri))
                    .setLength(HlsParameterSetInit.SEGMENT_HEAD_BYTES.toLong())
                    .build()
            )
            // Some hosts throttle segments, so stop as soon as the start of the segment says enough.
            val head = ByteArrayOutputStream()
            val step = ByteArray(SEGMENT_HEAD_STEP_BYTES)
            while (head.size() < HlsParameterSetInit.SEGMENT_HEAD_BYTES) {
                val read = source.read(step, 0, minOf(step.size, HlsParameterSetInit.SEGMENT_HEAD_BYTES - head.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                head.write(step, 0, read)
                if (HlsParameterSetInit.isSegmentHeadConclusive(head.toByteArray())) break
            }
            head.toByteArray()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not read the start of $uri", e)
            null
        } finally {
            runCatching { source.close() }
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HlsParameterSetInitDataSource(upstreamFactory.createDataSource(), upstreamFactory::createDataSource)
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 3_000L
        const val SEGMENT_HEAD_STEP_BYTES = 4 * 1024

        fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
            return headers.entries.any { (name, values) ->
                name.equals("Content-Type", ignoreCase = true) && values.any { it.contains("mpegurl", ignoreCase = true) }
            }
        }

        fun readAll(source: DataSource): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = source.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }
}

private const val TAG = "HlsParameterSetInit"

/**
 * Runs [block] on its own thread and returns its result, or null when it fails or [timeoutMs]
 * passes first. A block that overruns is interrupted and left to finish in the background.
 */
internal fun <T : Any> resultWithin(timeoutMs: Long, block: () -> T?): T? {
    val task = FutureTask(Callable { block() })
    Thread(task, TAG).apply { isDaemon = true }.start()
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        Log.w(TAG, "Checking segments took over $timeoutMs ms, playlist left as is")
        null
    } catch (e: ExecutionException) {
        Log.w(TAG, "Checking segments failed, playlist left as is", e.cause)
        null
    } catch (e: InterruptedException) {
        task.cancel(true)
        Thread.currentThread().interrupt()
        throw InterruptedIOException()
    }
}
