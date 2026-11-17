package com.nuvio.app.features.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
internal class VodCacheWriteSink(
    private val delegate: DataSink,
    private val counters: Counters
) : DataSink {

    class Counters {
        val bytesWritten = AtomicLong(0L)
        val writeTimeMs = AtomicLong(0L)
        val enqueueTimeMs = AtomicLong(0L)
        val errors = AtomicLong(0L)
        val blockedMs = AtomicLong(0L)
        val bufferTimeNs = AtomicLong(0L)
        val copyTimeNs = AtomicLong(0L)
        val allocations = AtomicLong(0L)
        val closeWaitMs = AtomicLong(0L)
        val spans = AtomicLong(0L)

        fun reset() {
            bytesWritten.set(0L)
            writeTimeMs.set(0L)
            enqueueTimeMs.set(0L)
            errors.set(0L)
            blockedMs.set(0L)
            bufferTimeNs.set(0L)
            copyTimeNs.set(0L)
            allocations.set(0L)
            closeWaitMs.set(0L)
            spans.set(0L)
        }
    }

    @Volatile
    private var failed = false

    private val queuedBytes = AtomicLong(0L)

    private var pending: ByteArray? = null
    private var pendingLength = 0

    override fun open(dataSpec: DataSpec) {
        failed = false
        queuedBytes.set(0L)
        pending = null
        pendingLength = 0
        delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failed) return
        val startMs = SystemClock.elapsedRealtime()
        var blocked = false
        val waitUntilMs = startMs + MAX_BACKPRESSURE_WAIT_MS
        while (queuedBytes.get() + length > MAX_QUEUED_BYTES &&
            SystemClock.elapsedRealtime() < waitUntilMs
        ) {
            blocked = true
            try {
                Thread.sleep(BACKPRESSURE_SLEEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (queuedBytes.get() + length > MAX_QUEUED_BYTES) {
            failed = true
            counters.blockedMs.addAndGet(SystemClock.elapsedRealtime() - startMs)
            return
        }
        var consumed = 0
        while (consumed < length) {
            val beforeBufferNs = System.nanoTime()
            var target = pending
            if (target == null) {
                target = takeBuffer(counters)
                pending = target
                pendingLength = 0
            }
            val beforeCopyNs = System.nanoTime()
            counters.bufferTimeNs.addAndGet(beforeCopyNs - beforeBufferNs)
            val chunk = minOf(BUFFER_BYTES - pendingLength, length - consumed)
            System.arraycopy(buffer, offset + consumed, target, pendingLength, chunk)
            counters.copyTimeNs.addAndGet(System.nanoTime() - beforeCopyNs)
            pendingLength += chunk
            consumed += chunk
            if (pendingLength == BUFFER_BYTES) flushPending()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        counters.enqueueTimeMs.addAndGet(elapsedMs)
        if (blocked) counters.blockedMs.addAndGet(elapsedMs)
    }

    private fun flushPending() {
        val buffer = pending ?: return
        val length = pendingLength
        pending = null
        pendingLength = 0
        if (length == 0) {
            recycleBuffer(buffer)
            return
        }
        queuedBytes.addAndGet(length.toLong())
        writer.execute { drainOne(buffer, length) }
    }

    override fun close() {
        if (failed) {
            pending?.let(::recycleBuffer)
            pending = null
            pendingLength = 0
        } else {
            flushPending()
        }
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        val waitStartMs = SystemClock.elapsedRealtime()
        var interrupted = Thread.interrupted()
        val drained = try {
            done.await(MAX_CLOSE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            interrupted = true
            false
        }
        if (!drained) {
            failed = true
            Log.w(TAG, "VOD_CACHE: close gave up on the rest of this span (interrupted=$interrupted)")
            while (true) {
                try {
                    done.await()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        }
        counters.closeWaitMs.addAndGet(SystemClock.elapsedRealtime() - waitStartMs)
        counters.spans.incrementAndGet()
        try {
            delegate.close()
        } catch (e: IOException) {
            counters.errors.incrementAndGet()
            Log.w(TAG, "VOD_CACHE: sink close failed", e)
            throw e
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drainOne(data: ByteArray, length: Int) {
        if (!failed) {
            val startMs = SystemClock.elapsedRealtime()
            try {
                delegate.write(data, 0, length)
                counters.writeTimeMs.addAndGet(SystemClock.elapsedRealtime() - startMs)
                counters.bytesWritten.addAndGet(length.toLong())
            } catch (e: IOException) {
                counters.errors.incrementAndGet()
                Log.w(TAG, "VOD_CACHE: sink write failed", e)
                failed = true
            }
        }
        queuedBytes.addAndGet(-length.toLong())
        recycleBuffer(data)
    }

    private companion object {
        const val TAG = "PlayerMediaSource"
        const val MAX_QUEUED_BYTES = 24L * 1024L * 1024L
        const val BACKPRESSURE_SLEEP_MS = 2L
        const val MAX_BACKPRESSURE_WAIT_MS = 2_000L
        const val MAX_CLOSE_WAIT_MS = 500L
        const val POOL_CAPACITY = 64

        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "VodCacheWrite")
        }

        const val BUFFER_BYTES = 64 * 1024

        private val bufferPool = ArrayDeque<ByteArray>()

        fun takeBuffer(counters: Counters): ByteArray {
            val pooled = synchronized(bufferPool) { bufferPool.removeLastOrNull() }
            if (pooled != null) return pooled
            counters.allocations.incrementAndGet()
            return ByteArray(BUFFER_BYTES)
        }

        fun recycleBuffer(buffer: ByteArray) {
            if (buffer.size != BUFFER_BYTES) return
            synchronized(bufferPool) {
                if (bufferPool.size < POOL_CAPACITY) bufferPool.addLast(buffer)
            }
        }
    }
}
