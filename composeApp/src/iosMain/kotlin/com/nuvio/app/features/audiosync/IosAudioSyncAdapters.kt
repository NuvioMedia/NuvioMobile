package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrTokens
import com.nuvio.app.features.audiosync.asr.SpeechToText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.Foundation.dataWithLength
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal class IosAudioSyncSpotReader(private val bridge: AudioSyncNativeBridge) : AudioSyncSpotReader {
    override fun isUnmetered(): Boolean = bridge.isNetworkUnmetered()

    override fun read(request: AudioSyncSpotRequest, sink: AudioSyncSpotSink): AudioSyncSpotResult {
        val result = bridge.readSpots(
            url = request.url,
            headers = request.headers,
            spotsMs = request.spotsMs,
            spotMs = request.spotMs,
            targetBytes = request.targetBytes,
            maxBytes = request.maxBytes,
            minSpotMs = request.minSpotMs,
            audioLanguage = request.audioLanguage,
            sink = object : AudioSyncNativePcmSink {
                override fun isCancelled(): Boolean = sink.isCancelled()

                override fun onPcm(samples: NSData, frames: Int, sampleRate: Int, timeUs: Long) {
                    val count = minOf(frames, (samples.length / 4u).toInt())
                    if (count <= 0) return
                    val floats = FloatArray(count)
                    floats.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), samples.bytes, (count * 4).convert())
                    }
                    sink.onPcm(floats, count, sampleRate, timeUs)
                }

                override fun onSpotEnd() {
                    sink.onSpotEnd()
                }
            },
        )
        return AudioSyncSpotResult(result.sampled, result.bytes, result.failure)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosSpeechToText(private val native: AudioSyncNativeRecognizer) : SpeechToText {
    override fun transcribe(samples: FloatArray): List<Pair<Double, String>> {
        if (samples.isEmpty()) return emptyList()
        val data = samples.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = (samples.size * 4).convert())
        }
        val transcript = native.transcribe(data, samples.size)
        return AsrTokens.merge(transcript.tokens, transcript.timestamps)
    }
}

internal object IosSharedRecognizer {
    private const val THREADS = 2
    private const val IDLE_RELEASE_MS = 60_000L

    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var native: AudioSyncNativeRecognizer? = null
    private var speechToText: SpeechToText? = null
    private var users = 0
    private var releaseJob: Job? = null

    fun acquire(bridge: AudioSyncNativeBridge): SpeechToText? {
        synchronized(lock) {
            releaseJob?.cancel()
            releaseJob = null
            speechToText?.let {
                users++
                return it
            }
            val files = IosAsrModel.recognizerFiles() ?: return null
            val created = bridge.createRecognizer(
                encoder = files.encoder,
                decoder = files.decoder,
                joiner = files.joiner,
                tokens = files.tokens,
                threads = THREADS,
                provider = files.provider,
            ) ?: return null
            native = created
            users = 1
            return IosSpeechToText(created).also { speechToText = it }
        }
    }

    fun release() {
        synchronized(lock) {
            if (users == 0) return
            users--
            if (users > 0) return
            releaseJob = scope.launch {
                delay(IDLE_RELEASE_MS)
                synchronized(lock) {
                    if (users == 0) {
                        native?.close()
                        native = null
                        speechToText = null
                    }
                }
            }
        }
    }
}

internal class IosAudioSyncRecognizers(private val bridge: AudioSyncNativeBridge) : AudioSyncRecognizers {
    override fun isModelReady(): Boolean = IosAsrModel.isReady()

    override fun acquire(): SpeechToText? = IosSharedRecognizer.acquire(bridge)

    override fun release() {
        IosSharedRecognizer.release()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosMpvLiveAudioTap(
    private val native: AudioSyncNativeBridge,
) : MpvLiveAudioTap {
    private val lock = SynchronizedObject()
    private var scratchData: NSMutableData? = null
    private var scratchCapacitySamples = 0
    private val metaBuffer = AudioSyncNativeTapChunk()

    override val isSupported: Boolean
        get() = native.isMpvAudioTapSupported()

    override val isAvailable: Boolean
        get() = native.isMpvAudioTapAvailable()

    override fun enable(ringCapacitySamples: Int): Long =
        native.enableMpvAudioTap(ringCapacitySamples)

    override fun setPaused(token: Long, paused: Boolean) {
        if (token == 0L) return
        native.setMpvAudioTapPaused(token, paused)
    }

    override fun readInto(token: Long, outSamples: FloatArray, maxSamples: Int, outMeta: MpvAudioTapChunk): Int {
        if (token == 0L) {
            outMeta.flags = MpvAudioTapFlags.EOF
            return 0
        }
        val limit = minOf(maxSamples, outSamples.size)
        if (limit <= 0) return -1
        return synchronized(lock) {
            val data = ensureScratchLocked(limit) ?: return@synchronized -1
            val read = native.readMpvAudioTap(token, data, limit, metaBuffer)
            outMeta.sampleRate = metaBuffer.sampleRate
            outMeta.sampleCount = metaBuffer.sampleCount
            outMeta.generation = metaBuffer.generation
            outMeta.ptsUs = metaBuffer.ptsUs
            outMeta.flags = metaBuffer.flags
            if (read > 0) {
                val count = minOf(read, limit)
                outSamples.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, (count * 4).convert())
                }
                count
            } else {
                read
            }
        }
    }

    override fun disable(token: Long) {
        if (token == 0L) return
        native.disableMpvAudioTap(token)
        synchronized(lock) {
            scratchData = null
            scratchCapacitySamples = 0
        }
    }

    private fun ensureScratchLocked(samples: Int): NSMutableData? {
        val existing = scratchData
        if (existing != null && scratchCapacitySamples >= samples) return existing
        val created = NSMutableData.dataWithLength((samples * 4).convert()) ?: return null
        scratchData = created
        scratchCapacitySamples = samples
        return created
    }
}
