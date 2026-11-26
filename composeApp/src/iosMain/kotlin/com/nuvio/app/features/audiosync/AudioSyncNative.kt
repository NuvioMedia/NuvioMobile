package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import platform.Foundation.NSData
import platform.Foundation.NSMutableData

class AudioSyncNativeTapChunk {
    var sampleRate: Int = 0
    var sampleCount: Int = 0
    var generation: Int = 0
    var ptsUs: Long = 0L
    var flags: Int = 0
}

interface AudioSyncNativePcmSink {
    fun isCancelled(): Boolean

    fun onPcm(samples: NSData, frames: Int, sampleRate: Int, timeUs: Long)

    fun onSpotEnd()
}

class AudioSyncNativeSpotResult(val sampled: Int, val bytes: Long, val failure: String?)

class AudioSyncNativeTranscript(val tokens: List<String>, val timestamps: List<Double>)

interface AudioSyncNativeRecognizer {
    fun transcribe(samples: NSData, frames: Int): AudioSyncNativeTranscript

    fun close()
}

interface AudioSyncNativeDownloadProgress {
    fun onBytes(written: Long)
}

interface AudioSyncNativeBridge {
    fun isNetworkUnmetered(): Boolean

    fun readSpots(
        url: String,
        headers: Map<String, String>,
        spotsMs: List<Long>,
        spotMs: Long,
        targetBytes: Long,
        maxBytes: Long,
        minSpotMs: Long,
        audioLanguage: String?,
        sink: AudioSyncNativePcmSink,
    ): AudioSyncNativeSpotResult

    fun createRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int,
        provider: String,
    ): AudioSyncNativeRecognizer?

    fun downloadFile(
        url: String,
        destinationPath: String,
        expectedSize: Long,
        sha256: String,
        progress: AudioSyncNativeDownloadProgress,
    ): String?

    fun isMpvAudioTapSupported(): Boolean

    fun isMpvAudioTapAvailable(): Boolean

    fun enableMpvAudioTap(ringCapacitySamples: Int): Long

    fun setMpvAudioTapPaused(token: Long, paused: Boolean)

    fun readMpvAudioTap(token: Long, outSamples: NSMutableData, maxSamples: Int, outMeta: AudioSyncNativeTapChunk): Int

    fun disableMpvAudioTap(token: Long)
}

object AudioSyncNativeRegistry {
    @Volatile
    internal var bridge: AudioSyncNativeBridge? = null
        private set

    fun register(bridge: AudioSyncNativeBridge) {
        this.bridge = bridge
    }
}
