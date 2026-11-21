package com.nuvio.app.features.audiosync.asr

import java.io.File
import java.util.concurrent.Executors

/**
 * One loaded speech recogniser shared by every player, kept for a while after the last one closes
 * so the next episode or film has it at once instead of loading the model again.
 */
internal object SharedRecognizer {
    /** Covers moving to the next episode or picking another film. */
    private const val KEEP_AFTER_LAST_USE_MS = 3 * 60_000L

    private val closer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NuvioAsrRelease").apply { isDaemon = true }
    }
    private val cache = SharedResource<SherpaSpeechToText>(closer, KEEP_AFTER_LAST_USE_MS) {
        runCatching { it.close() }
    }

    /** True when a recogniser is loaded and can be acquired without waiting. */
    val isLoaded: Boolean
        get() = cache.isLoaded

    /** Returns the loaded recogniser, loading it first when needed (blocking: call off the main thread). */
    fun acquire(modelDir: File, threads: Int, lowMemory: Boolean = false): SherpaSpeechToText =
        cache.acquire { SherpaSpeechToText(modelDir, threads, lowMemory) }

    /** Gives back an acquired recogniser; it is closed once nobody has used it for a while. */
    fun release() = cache.release()
}
