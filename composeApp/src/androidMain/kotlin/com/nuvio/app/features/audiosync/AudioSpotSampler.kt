@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.ExtractorsFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Reads a few short stretches of a film's audio far from the playhead, over its own connection, so
 * sync has evidence from across the film within seconds instead of only what playback has reached.
 *
 * The audio itself is delivered by the extractors factories (wrapped with an audio tap) while this
 * class only seeks and advances. Each worker reads spots over its own connection, so far-apart
 * spots arrive together where the host and playback allow more than one (see [run]). Containers interleave audio with video, so each spot costs its share of
 * the whole stream: a spot stops at its share of [targetBytes]. On a high-bitrate file that share
 * can hold only a few seconds, too little for whole sentences, so a spot keeps reading until it has
 * [minSpotMs] of audio when the connection is clearly faster than the stream (playback keeps its
 * bandwidth), never past [maxBytes] in total. Blocking; run it on a background thread.
 */
internal class AudioSpotSampler(
    private val uri: Uri,
    private val dataSourceFactory: DataSource.Factory,
    private val targetBytes: Long,
    private val maxBytes: Long,
    private val minSpotMs: Long,
    /** An extra connection was refused by the host; sampling carries on over the others. */
    private val onRefused: (String) -> Unit = {},
) {
    class Result(val sampled: Int, val bytes: Long, val failure: String?, val connections: Int)

    /** Bytes read over all connections: the budget. */
    private val bytes = AtomicLong()

    /** Counts into [bytes] and into [connectionBytes], the bytes of one connection. */
    private fun countingFactory(connectionBytes: AtomicLong) = DataSource.Factory {
        dataSourceFactory.createDataSource().apply { addTransferListener(ByteCounter(bytes, connectionBytes)) }
    }

    /**
     * Reads [spotMs] of audio from each of [spotsMs], taken in order by workers that each read over
     * their own connection and feed their own decoder (an extractors factory from [newWorker]),
     * until done, [isCancelled] or out of budget. [onSpot] reports progress after each spot, from any
     * worker thread.
     *
     * Sampling starts on one connection. Another is opened only once the newest one is reading,
     * while [mayAddConnection] allows it, up to [maxWorkers]. A host that refuses an extra
     * connection (it fails before or while reading) stops the ramp: its spot goes back to the queue
     * for the connections already working, and no further connection is opened for this stream.
     */
    fun run(
        spotsMs: List<Long>,
        spotMs: Long,
        maxWorkers: Int,
        newWorker: () -> ExtractorsFactory,
        mayAddConnection: () -> Boolean,
        isCancelled: () -> Boolean,
        onSpot: (sampled: Int, bytes: Long) -> Unit,
    ): Result {
        val queue = ConcurrentLinkedQueue(spotsMs.indices.toList())
        val sampled = AtomicInteger(0)
        val failure = AtomicReference<String?>(null)
        val connections = AtomicInteger(1)
        val refused = AtomicBoolean(false)
        // A connection was opened and has not started reading yet: no other is tried meanwhile.
        val opening = AtomicBoolean(false)
        val nextCheckNs = AtomicLong(0L)
        val helpers = ArrayList<Thread>()
        val stop = { isCancelled() || failure.get() != null || bytes.get() >= maxBytes }
        val startedNs = System.nanoTime()

        lateinit var addConnection: () -> Unit

        fun work(extractorsFactory: ExtractorsFactory, extra: Boolean) {
            // A spot's cost is what its own connection read: the others read other spots meanwhile.
            val connectionBytes = AtomicLong()
            val extractor = MediaExtractorCompat(extractorsFactory, countingFactory(connectionBytes))
            var taken: Int? = null
            var reading = false
            try {
                extractor.setDataSource(uri, 0L)
                var audioTracks = 0
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        extractor.selectTrack(index)
                        audioTracks++
                    }
                }
                if (audioTracks == 0) {
                    failure.compareAndSet(null, "no audio track")
                    return
                }
                while (!stop()) {
                    val index = queue.poll() ?: return
                    taken = index
                    val spotStartMs = spotsMs[index]
                    val share = (targetBytes - bytes.get()).coerceAtLeast(0L) / (queue.size + 1)
                    val spotStartBytes = connectionBytes.get()
                    extractor.seekTo(spotStartMs * 1_000L, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                    val firstUs = extractor.sampleTime
                    if (firstUs < 0 || abs(firstUs / 1_000L - spotStartMs) > MAX_SEEK_MISS_MS) {
                        failure.compareAndSet(null, "stream cannot seek")
                        return
                    }
                    if (!reading) {
                        // This connection works: the next one may be tried.
                        reading = true
                        if (extra) opening.set(false)
                        addConnection()
                    }
                    val endUs = (spotStartMs + spotMs) * 1_000L
                    while (!stop()) {
                        val timeUs = extractor.sampleTime
                        if (timeUs < 0 || timeUs >= endUs) break
                        val spotBytes = connectionBytes.get() - spotStartBytes
                        if (spotBytes >= share && !wantsMore(spotBytes, timeUs - firstUs, startedNs)) break
                        if (!extractor.advance()) break
                        // Conditions are checked every second, not only between spots, so a
                        // connection that playback now allows opens without waiting out a spot.
                        val now = System.nanoTime()
                        val due = nextCheckNs.get()
                        if (now >= due && nextCheckNs.compareAndSet(due, now + CONNECTION_CHECK_NS)) addConnection()
                    }
                    taken = null
                    onSpot(sampled.incrementAndGet(), bytes.get())
                    // Conditions may allow another connection now, e.g. playback has buffered more.
                    addConnection()
                }
            } catch (error: Exception) {
                if (extra) {
                    // The host refused one connection more: keep to the ones that work.
                    refused.set(true)
                    taken?.let(queue::add)
                    onRefused(error.message ?: error.javaClass.simpleName)
                } else {
                    failure.compareAndSet(null, error.message ?: error.javaClass.simpleName)
                }
            } finally {
                if (extra && !reading) opening.set(false)
                runCatching { extractor.release() }
            }
        }

        addConnection = add@{
            if (refused.get() || opening.get() || queue.isEmpty() || stop()) return@add
            val count = connections.get()
            if (count >= maxWorkers || !runCatching(mayAddConnection).getOrDefault(false)) return@add
            if (!opening.compareAndSet(false, true)) return@add
            if (!connections.compareAndSet(count, count + 1)) {
                opening.set(false)
                return@add
            }
            val extractorsFactory = runCatching(newWorker).getOrNull()
            if (extractorsFactory == null) {
                connections.decrementAndGet()
                opening.set(false)
                return@add
            }
            val thread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work(extractorsFactory, extra = true)
            }, "NuvioAudioSyncSpots").apply { isDaemon = true }
            synchronized(helpers) { helpers += thread }
            thread.start()
        }

        work(newWorker(), extra = false)
        // A helper only starts another while it runs itself, so once none is alive, none can start.
        while (true) {
            val running = synchronized(helpers) { helpers.firstOrNull(Thread::isAlive) } ?: break
            running.join()
        }
        // Spots put back by a refused connection after the others finished are read here.
        if (!queue.isEmpty() && !stop()) work(newWorker(), extra = false)
        return Result(sampled.get(), bytes.get(), failure.get(), connections.get() - if (refused.get()) 1 else 0)
    }

    /**
     * Whether a spot past its share keeps reading: only while it has less than [minSpotMs] of audio
     * ([readUs] so far, costing its connection [spotBytes]) and all workers together download at least
     * [MIN_SPEED_RATIO] times faster than the stream plays.
     */
    private fun wantsMore(spotBytes: Long, readUs: Long, startedNs: Long): Boolean {
        if (readUs <= 0 || readUs >= minSpotMs * 1_000L) return false
        val streamBytesPerSec = spotBytes * 1_000_000.0 / readUs
        val elapsedSec = (System.nanoTime() - startedNs) / 1e9
        val downloadBytesPerSec = if (elapsedSec > 0) bytes.get() / elapsedSec else 0.0
        return downloadBytesPerSec >= MIN_SPEED_RATIO * streamBytesPerSec
    }

    private class ByteCounter(private val bytes: AtomicLong, private val connectionBytes: AtomicLong) : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            bytes.addAndGet(bytesTransferred.toLong())
            connectionBytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private companion object {
        /** A seek landing further than this from the target means the stream has no usable index. */
        const val MAX_SEEK_MISS_MS = 30_000L

        /** Download speed over stream bitrate needed to read past a spot's share. */
        const val MIN_SPEED_RATIO = 3.0

        /** How often a reading connection checks whether another may open. */
        const val CONNECTION_CHECK_NS = 1_000_000_000L
    }
}
