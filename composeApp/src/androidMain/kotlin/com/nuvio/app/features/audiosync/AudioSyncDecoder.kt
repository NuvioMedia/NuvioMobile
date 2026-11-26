@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.decoder.ffmpeg.AudioSyncFfmpegDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodes the copied compressed audio on its own low-priority thread with a private [MediaCodec]
 * and feeds mono PCM to [analyzer]. The same thread also analyses the player's own decoded audio
 * handed over by [PlaybackAudioTap] (see [offerPlaybackPcm]) with a separate [liveAnalyzer].
 *
 * Decoders that cannot run a second instance next to the player's are never used, so playback is
 * never starved of its decoder; in that case the bundled FFmpeg software decoder is used instead, and
 * only formats neither can decode fall back to the playback tap. The
 * queues are bounded; if analysis falls behind, input is dropped and the gap is left unknown.
 */
internal class AudioSyncDecoder(
    private val analyzer: SpeechAnalyzer,
    private val liveAnalyzer: SpeechAnalyzer,
    /**
     * Whether a vendor decoder that allows several instances may be used. Off for extra decoders
     * next to the look-ahead one, which keep to platform software decoders and FFmpeg.
     */
    private val allowVendorDecoders: Boolean = true,
    /** Called once per audio format that has no usable decoder. */
    private val onUnsupportedFormat: (mimeType: String) -> Unit = {},
) {
    private sealed interface Item {
        class Sample(val format: Format, val timeUs: Long, val data: ByteArray) : Item
        class Pcm(val samples: FloatArray, val sampleRate: Int, val timeUs: Long) : Item
        data object Discontinuity : Item
    }

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var queuedBytes = 0
    private var dropping = false
    private var released = false
    private var thread: Thread? = null

    // Decoder-thread state.
    private var codec: MediaCodec? = null
    private var ffmpeg: AudioSyncFfmpegDecoder? = null
    private var codecFormat: Format? = null
    private var outputChannels = 0
    private var outputRate = 0
    private var outputFloat = false
    private var centerIndex = -1
    private var mono = FloatArray(0)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val pcmClock = PcmTimestampTracker()
    private val unsupportedMimes = HashSet<String>()
    private val encodedSamples = AtomicLong()
    private val decodedUs = AtomicLong()
    private val playbackUs = AtomicLong()
    private val droppedSamples = AtomicLong()
    private val droppedPlaybackBuffers = AtomicLong()
    @Volatile private var lastPcmAtMs = 0L
    @Volatile private var lastPcmTimeUs = Long.MIN_VALUE
    @Volatile private var workerState = "not started"
    @Volatile private var decoderName = "none"
    @Volatile private var lastError: String? = null
    @Volatile private var lastDecodedPcm = "none"
    @Volatile private var lastPlaybackPcm = "none"

    /** Metadata only; no audio, subtitle text or stream URLs are retained. */
    fun diagnostics(): String {
        val bytes = synchronized(lock) { queuedBytes }
        val lastOutput = lastPcmAtMs
        val age = if (lastOutput == 0L) "never" else "${(SystemClock.elapsedRealtime() - lastOutput) / 1_000}s ago"
        val mediaMs = lastPcmTimeUs.takeUnless { it == Long.MIN_VALUE }?.div(1_000L)
        return "decoder=$decoderName worker=$workerState input=${encodedSamples.get()}" +
            " decoded=${decodedUs.get() / 1_000}ms playback=${playbackUs.get() / 1_000}ms" +
            " droppedInput=${droppedSamples.get()} droppedPlayback=${droppedPlaybackBuffers.get()} queue=${bytes}B" +
            " captureStarts=${analyzer.starts} playbackStarts=${liveAnalyzer.starts}" +
            " captureRepeatedPts=${pcmClock.repeatedTimestamps}" +
            " captureReset=[${analyzer.lastStartReason}] playbackReset=[${liveAnalyzer.lastStartReason}]" +
            " decodedPcm=[$lastDecodedPcm] playbackPcm=[$lastPlaybackPcm]" +
            " lastPcm=$age mediaMs=${mediaMs ?: "-"} lastError=${lastError ?: "-"}"
    }

    private fun recordPcm(timeUs: Long) {
        lastPcmTimeUs = timeUs
        lastPcmAtMs = SystemClock.elapsedRealtime()
    }

    /** Called from the extractor (loading) thread. Returns false when the sample was dropped. */
    fun offer(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int): Boolean {
        val mime = format.sampleMimeType ?: return false
        synchronized(lock) {
            if (released) return false
            if (mime in unsupportedMimesSnapshot) return false
            encodedSamples.incrementAndGet()
            if (queuedBytes + size > MAX_QUEUED_BYTES) {
                droppedSamples.incrementAndGet()
                if (!dropping) {
                    dropping = true
                    queue.addLast(Item.Discontinuity)
                }
                return false
            }
            dropping = false
            queue.addLast(Item.Sample(format, timeUs, data.copyOfRange(offset, offset + size)))
            queuedBytes += size
            ensureThread()
            lock.notifyAll()
        }
        return true
    }

    /** Called on the playback thread with the player's decoded audio, already mixed to mono. */
    fun offerPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, timeUs: Long) {
        synchronized(lock) {
            if (released) return
            val bytes = frames * 4
            if (queuedBytes + bytes > MAX_QUEUED_BYTES) {
                droppedPlaybackBuffers.incrementAndGet()
                return
            }
            queue.addLast(Item.Pcm(mono.copyOf(frames), sampleRate, timeUs))
            queuedBytes += bytes
            ensureThread()
            lock.notifyAll()
        }
    }

    /** False once [format] turned out to have no usable decoder (or after [release]). */
    fun accepts(format: Format): Boolean =
        !released && format.sampleMimeType.let { it != null && it !in unsupportedMimesSnapshot }

    /** Waits up to [timeoutMs] until everything offered so far has been taken for decoding. */
    fun awaitDrained(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (queue.isNotEmpty() && !released) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                lock.wait(left)
            }
        }
    }

    fun discontinuity() {
        synchronized(lock) {
            if (released) return
            queue.clear()
            queuedBytes = 0
            queue.addLast(Item.Discontinuity)
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
            queuedBytes = 0
            lock.notifyAll()
        }
    }

    @Volatile
    private var unsupportedMimesSnapshot: Set<String> = emptySet()

    private fun ensureThread() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "NuvioAudioSync").apply {
            isDaemon = true
            start()
        }
    }

    private fun stayInBackground() {
        val current = Process.getThreadPriority(Process.myTid())
        if (current == Process.THREAD_PRIORITY_BACKGROUND) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            while (true) {
                val item = synchronized(lock) {
                    workerState = "waiting for input"
                    while (queue.isEmpty() && !released) lock.wait()
                    if (released) return
                    lock.notifyAll()
                    queue.removeFirst().also { item ->
                        when (item) {
                            is Item.Sample -> queuedBytes -= item.data.size
                            is Item.Pcm -> queuedBytes -= item.samples.size * 4
                            Item.Discontinuity -> Unit
                        }
                    }
                }
                workerState = when (item) {
                    is Item.Pcm -> "analysing playback PCM"
                    is Item.Sample -> "decoding compressed audio"
                    Item.Discontinuity -> "resetting"
                }
                when (item) {
                    is Item.Discontinuity -> {
                        flushCodec()
                        analyzer.reset()
                    }
                    is Item.Sample -> decode(item)
                    is Item.Pcm -> {
                        lastPlaybackPcm = "${item.samples.size}frames@${item.sampleRate}Hz timeUs=${item.timeUs}"
                        playbackUs.addAndGet(item.samples.size * 1_000_000L / item.sampleRate.coerceAtLeast(1))
                        recordPcm(item.timeUs)
                        liveAnalyzer.accept(item.samples, item.samples.size, item.sampleRate, item.timeUs)
                    }
                }
                stayInBackground()
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            lastError = error.javaClass.simpleName
            SyncLog.w("decoder loop stopped: ${error.message}")
        } finally {
            workerState = "stopped"
            releaseCodec()
        }
    }

    private fun decode(sample: Item.Sample) {
        if (!prepareDecoder(sample.format)) return
        ffmpeg?.let { decodeWithFfmpeg(it, sample); return }
        val codec = codec ?: return
        try {
            var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            var attempts = 0
            while (inputIndex < 0 && attempts < MAX_INPUT_ATTEMPTS) {
                drainOutput(codec)
                inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                attempts++
            }
            if (inputIndex < 0) {
                droppedSamples.incrementAndGet()
                return
            }
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            if (input.remaining() < sample.data.size) {
                droppedSamples.incrementAndGet()
                codec.queueInputBuffer(inputIndex, 0, 0, sample.timeUs, 0)
                return
            }
            input.put(sample.data)
            codec.queueInputBuffer(inputIndex, 0, sample.data.size, sample.timeUs, 0)
            drainOutput(codec)
        } catch (error: Exception) {
            lastError = error.javaClass.simpleName
            SyncLog.w("decode failed for ${sample.format.sampleMimeType}: ${error.message}")
            releaseCodec()
            analyzer.reset()
        }
    }

    private fun decodeWithFfmpeg(decoder: AudioSyncFfmpegDecoder, sample: Item.Sample) {
        try {
            var attempts = 0
            while (!decoder.queue(sample.data, sample.timeUs)) {
                drainFfmpeg(decoder)
                if (++attempts > MAX_INPUT_ATTEMPTS) {
                    droppedSamples.incrementAndGet()
                    return
                }
                Thread.sleep(2)
            }
            drainFfmpeg(decoder)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            lastError = error.javaClass.simpleName
            SyncLog.w("ffmpeg decode failed for ${sample.format.sampleMimeType}: ${error.message}")
            releaseCodec()
            analyzer.reset()
        }
    }

    private fun drainFfmpeg(decoder: AudioSyncFfmpegDecoder) {
        decoder.drain { data, timeUs ->
            outputChannels = decoder.channelCount
            outputRate = decoder.sampleRate
            centerIndex = if (outputChannels >= 3) 2 else -1
            deliverPcm(data.order(ByteOrder.nativeOrder()), timeUs)
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && bufferInfo.size > 0) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        deliverPcm(output.slice().order(ByteOrder.nativeOrder()), bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    private fun readOutputFormat(format: MediaFormat) {
        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        outputFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val mask = if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
        } else {
            0
        }
        centerIndex = when {
            outputChannels < 3 -> -1
            mask != 0 && mask and AudioFormat.CHANNEL_OUT_FRONT_CENTER != 0 ->
                Integer.bitCount(mask and (AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT))
            mask != 0 -> -1
            // Android decoders emit the WAVE order (FL, FR, FC, LFE, ...) for 3+ channel layouts.
            else -> 2
        }
    }

    private fun deliverPcm(buffer: ByteBuffer, timeUs: Long) {
        val channels = outputChannels
        val rate = outputRate
        if (channels <= 0 || rate <= 0) return
        val frames = buffer.remaining() / ((if (outputFloat) 4 else 2) * channels)
        if (frames <= 0) return
        val mediaTimeUs = pcmClock.resolve(timeUs, frames, rate)
        lastDecodedPcm = "${frames}frames@${rate}Hz ${channels}ch ${if (outputFloat) "float" else "s16"} rawUs=$timeUs timeUs=$mediaTimeUs"
        decodedUs.addAndGet(frames * 1_000_000L / rate)
        recordPcm(mediaTimeUs)
        if (mono.size < frames) mono = FloatArray(frames)
        PcmDownmix.toMono(buffer, buffer.position(), frames, channels, outputFloat, centerIndex, mono)
        analyzer.accept(mono, frames, rate, mediaTimeUs)
    }

    /** Opens a decoder for [format] unless the current one already fits. False when none is usable. */
    private fun prepareDecoder(format: Format): Boolean {
        if ((codec != null || ffmpeg != null) && codecFormat.isSameStream(format)) return true
        releaseCodec()
        analyzer.reset()
        val mime = format.sampleMimeType ?: return false
        if (mime in unsupportedMimes) return false
        outputChannels = format.channelCount
        outputRate = format.sampleRate
        outputFloat = false
        centerIndex = if (format.channelCount >= 3) 2 else -1
        val created = runCatching { createCodec(format) }.onFailure {
            SyncLog.w("no usable MediaCodec for $mime: ${it.message}")
        }.getOrNull()
        if (created != null) {
            codec = created
            codecFormat = format
            decoderName = created.name
            SyncLog.i("decoding $mime ${format.channelCount}ch ${format.sampleRate}Hz with ${created.name}")
            return true
        }
        val software = if (AudioSyncFfmpegDecoder.supports(mime)) {
            runCatching { AudioSyncFfmpegDecoder(format) }.onFailure {
                SyncLog.w("ffmpeg decoder failed for $mime: ${it.message}")
            }.getOrNull()
        } else {
            null
        }
        if (software != null) {
            ffmpeg = software
            codecFormat = format
            // FFmpeg emits float PCM in its native layout, where the centre is the third channel.
            outputFloat = true
            decoderName = software.name
            SyncLog.i("decoding $mime ${format.channelCount}ch ${format.sampleRate}Hz with ${software.name}")
            return true
        }
        unsupportedMimes += mime
        decoderName = "playback tap ($mime)"
        unsupportedMimesSnapshot = unsupportedMimes.toSet()
        SyncLog.i("no usable decoder for $mime; relying on the playback tap")
        runCatching { onUnsupportedFormat(mime) }
        return false
    }

    private fun createCodec(format: Format): MediaCodec? {
        val mime = format.sampleMimeType ?: return null
        val candidates = buildList {
            add(mime)
            if (mime == MimeTypes.AUDIO_E_AC3_JOC) add(MimeTypes.AUDIO_E_AC3)
        }
        for (candidate in candidates) {
            val name = findDecoder(candidate) ?: continue
            val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format)
            mediaFormat.setString(MediaFormat.KEY_MIME, candidate)
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
                return codec
            } catch (error: Exception) {
                codec.release()
                SyncLog.w("failed to start $name: ${error.message}")
            }
        }
        return null
    }

    /**
     * Prefers platform software decoders. Otherwise accepts a vendor decoder (Dolby AC3/E-AC3 on many
     * phones) only when the platform reports it is not hardware-backed and it supports more than one
     * instance, so opening a second one next to the player's is safe. Before Android 10 that cannot
     * be told, so those devices use FFmpeg instead.
     */
    private fun findDecoder(mime: String): String? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        infos.forEach { info ->
            SyncLog.d("decoder candidate ${info.name} for $mime: software=${info.isSoftwareOnlyCompat()} " +
                    "hardware=${info.isHardwareAcceleratedCompat()} instances=${info.maxInstances(mime)}",
            )
        }
        infos.firstOrNull { it.isSoftwareOnlyCompat() }?.let { return it.name }
        if (!allowVendorDecoders) return null
        return infos.firstOrNull { info ->
            info.isHardwareAcceleratedCompat() == false && info.maxInstances(mime) >= 2
        }?.name
    }

    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isSoftwareOnly
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") || lower.startsWith("c2.ffmpeg.")
    }

    /** Null when the platform cannot tell (before Android 10). */
    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated else null

    private fun MediaCodecInfo.maxInstances(mime: String): Int =
        runCatching { getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(0)

    private fun Format?.isSameStream(other: Format): Boolean =
        this != null && sampleMimeType == other.sampleMimeType && sampleRate == other.sampleRate &&
            channelCount == other.channelCount && initializationData.size == other.initializationData.size &&
            initializationData.indices.all { initializationData[it].contentEquals(other.initializationData[it]) }

    private fun flushCodec() {
        pcmClock.reset()
        ffmpeg?.let { decoder ->
            runCatching { decoder.flush() }.onFailure { releaseCodec() }
            return
        }
        val current = codec ?: return
        try {
            current.flush()
        } catch (_: Exception) {
            releaseCodec()
        }
    }

    private fun releaseCodec() {
        pcmClock.reset()
        ffmpeg?.let { decoder ->
            ffmpeg = null
            codecFormat = null
            runCatching { decoder.release() }
        }
        val current = codec ?: return
        codec = null
        codecFormat = null
        try {
            current.stop()
        } catch (_: Exception) {
        }
        try {
            current.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val MAX_INPUT_ATTEMPTS = 40
    }
}
