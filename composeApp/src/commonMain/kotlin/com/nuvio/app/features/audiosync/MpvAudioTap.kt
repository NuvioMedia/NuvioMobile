package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bitmask constants matching `enum mpv_audio_tap_flags` in `include/mpv/audio_tap.h`. */
internal object MpvAudioTapFlags {
    const val NONE = 0
    const val NON_PCM = 1 shl 0
    const val DROPPED = 1 shl 1
    const val EOF = 1 shl 2
    const val SPEED_MISMATCH = 1 shl 3
}

/**
 * Reusable metadata container populated by [MpvLiveAudioTap.readInto] to avoid per-chunk
 * allocations on the background audio reader loop.
 */
internal class MpvAudioTapChunk(
    var sampleRate: Int = 0,
    var sampleCount: Int = 0,
    var generation: Int = 0,
    var ptsUs: Long = 0L,
    var flags: Int = MpvAudioTapFlags.NONE,
) {
    fun clear() {
        sampleRate = 0
        sampleCount = 0
        generation = 0
        ptsUs = 0L
        flags = MpvAudioTapFlags.NONE
    }
}

/**
 * Platform bridge to libmpv's live audio tap API (`mpv_audio_tap_*`).
 *
 * Streams pre-gain, pre-mute mono float32 PCM from libmpv so mpv handles audio subtitle sync
 * through continuous playback audio, matching ExoPlayer. Ownership tokens prevent a stale session
 * from disabling or reading a newer session's ring buffer.
 */
internal interface MpvLiveAudioTap {
    /** True when the loaded libmpv binary exports a compatible audio tap ABI. */
    val isSupported: Boolean

    /** True when [isSupported] and an active libmpv instance is attached and ready. */
    val isAvailable: Boolean

    /**
     * Allocates and attaches the fixed ring buffer on the active mpv instance.
     * Returns a non-zero ownership token on success, or `0L` on failure.
     */
    fun enable(ringCapacitySamples: Int = DEFAULT_RING_SAMPLES): Long

    /** Pauses or resumes capture inside mpv for [token] without tearing down the tap. */
    fun setPaused(token: Long, paused: Boolean)

    /**
     * Non-blocking read of up to [maxSamples] mono float32 samples for [token] into [outSamples],
     * updating [outMeta]. Returns > 0 for samples read, 0 when empty (flags still populated),
     * or < 0 on error.
     */
    fun readInto(token: Long, outSamples: FloatArray, maxSamples: Int, outMeta: MpvAudioTapChunk): Int

    /** Detaches and releases the tap owned by [token] so its ring buffer is freed when unused. */
    fun disable(token: Long)

    companion object {
        const val API_VERSION = 1
        /** 65,536 mono float32 samples = 256 KiB (~1.36 s @ 48 kHz). */
        const val DEFAULT_RING_SAMPLES = 65_536
    }
}

/**
 * Shared background reader that drains decoded mono PCM from [MpvLiveAudioTap] using a single
 * reusable scratch buffer, suspends cleanly with zero polling while paused, tracks seek/reset
 * generations, and surfaces non-PCM passthrough transitions.
 */
internal class MpvLiveAudioReader(
    private val scope: CoroutineScope,
    private val tap: MpvLiveAudioTap,
    private val wantsPcm: (mediaTimeUs: Long, durationUs: Long) -> Boolean,
    private val onPcm: (samples: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) -> Unit,
    private val onDiscontinuity: () -> Unit,
    private val onNonPcmDetected: () -> Unit,
    private val onPcmResumed: () -> Unit = {},
    private val onEnabled: () -> Unit = {},
    private val onStopped: (failed: Boolean) -> Unit = {},
    private val log: (String) -> Unit,
) {
    private var job: Job? = null
    private val activeToken = atomic(0L)
    private val pausedFlow = MutableStateFlow(false)
    private val discontinuityRequested = atomic(false)
    @Volatile
    private var started = false
    @Volatile
    private var enabled = false
    @Volatile
    private var nonPcmActive = false

    val isRunning: Boolean
        get() = started && job?.isActive == true

    val isEnabled: Boolean
        get() = enabled && isRunning

    val isNonPcm: Boolean
        get() = nonPcmActive

    fun start(
        ringCapacitySamples: Int = MpvLiveAudioTap.DEFAULT_RING_SAMPLES,
        previousJob: Job? = null,
    ): Boolean {
        if (started && job?.isActive == true) return true
        if (!tap.isAvailable) return false
        started = true
        enabled = false
        nonPcmActive = false
        discontinuityRequested.value = false
        job = scope.launch(Dispatchers.Default) { readLoop(ringCapacitySamples, previousJob) }
        return true
    }

    fun setPaused(paused: Boolean) {
        if (pausedFlow.value == paused) return
        pausedFlow.value = paused
        val token = activeToken.value
        if (token != 0L) tap.setPaused(token, paused)
    }

    /** Schedules a thread-safe discontinuity reset on the reader's own coroutine. */
    fun requestDiscontinuity() {
        discontinuityRequested.value = true
    }

    fun stop(): Job? {
        started = false
        enabled = false
        nonPcmActive = false
        val running = job
        running?.cancel()
        job = null
        val token = activeToken.getAndSet(0L)
        if (token != 0L) {
            runCatching { tap.disable(token) }
        }
        return running
    }

    private suspend fun CoroutineScope.readLoop(ringCapacitySamples: Int, previousJob: Job?) {
        // Wait off-main-thread for any previously cancelled reader to finish disabling its token
        // before calling enable(), preventing overlapping enable() calls from replacing each other.
        if (previousJob != null) {
            runCatching { previousJob.join() }
        }
        if (!started || !isActive) return

        val token = runCatching { tap.enable(ringCapacitySamples) }.getOrDefault(0L)
        if (token == 0L) {
            started = false
            enabled = false
            onStopped(true)
            return
        }
        if (!started || !isActive) {
            runCatching { tap.disable(token) }
            onStopped(false)
            return
        }
        activeToken.value = token
        enabled = true
        tap.setPaused(token, pausedFlow.value)
        onEnabled()

        val scratch = FloatArray(SCRATCH_SAMPLES)
        val meta = MpvAudioTapChunk()
        val clock = PcmTimestampTracker()
        var lastGeneration = -1
        var nonPcmReported = false
        var speedMismatchActive = false
        var idleDelayMs = IDLE_POLL_MIN_MS
        var failed = false

        try {
            while (isActive && started) {
                if (pausedFlow.value) {
                    idleDelayMs = IDLE_POLL_MIN_MS
                    pausedFlow.first { !it }
                    continue
                }
                if (discontinuityRequested.getAndSet(false)) {
                    clock.reset()
                    onDiscontinuity()
                }
                meta.clear()
                val read = tap.readInto(token, scratch, scratch.size, meta)
                if (read < 0) {
                    failed = true
                    log("libmpv audio tap read error ($read); stopping live reader")
                    break
                }
                val flags = meta.flags
                if (read == 0) {
                    if ((flags and MpvAudioTapFlags.EOF) != 0) break
                    if ((flags and MpvAudioTapFlags.NON_PCM) != 0) {
                        nonPcmActive = true
                        if (!nonPcmReported) {
                            nonPcmReported = true
                            clock.reset()
                            onDiscontinuity()
                            log("libmpv audio output is non-PCM passthrough; falling back to spot sampling")
                            onNonPcmDetected()
                        }
                        delay(NON_PCM_POLL_MS)
                        continue
                    }
                    if ((flags and MpvAudioTapFlags.SPEED_MISMATCH) != 0) {
                        if (!speedMismatchActive) {
                            speedMismatchActive = true
                            clock.reset()
                            onDiscontinuity()
                        }
                        delay(NON_PCM_POLL_MS)
                        continue
                    }
                    delay(idleDelayMs)
                    idleDelayMs = (idleDelayMs * 2L).coerceAtMost(IDLE_POLL_MAX_MS)
                    continue
                }

                idleDelayMs = IDLE_POLL_MIN_MS
                if (nonPcmActive || nonPcmReported) {
                    nonPcmActive = false
                    nonPcmReported = false
                    onPcmResumed()
                }
                speedMismatchActive = false

                val generationChanged = lastGeneration != -1 && meta.generation != lastGeneration
                val dropped = (flags and MpvAudioTapFlags.DROPPED) != 0
                if (generationChanged || dropped) {
                    clock.reset()
                    onDiscontinuity()
                }
                lastGeneration = meta.generation

                val sampleRate = meta.sampleRate
                if (sampleRate <= 0) continue
                val mediaTimeUs = clock.resolve(meta.ptsUs, read, sampleRate)
                val durationUs = read * 1_000_000L / sampleRate
                if (mediaTimeUs >= 0L && wantsPcm(mediaTimeUs, durationUs)) {
                    onPcm(scratch, read, sampleRate, mediaTimeUs)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            failed = true
            log("libmpv live audio reader stopped: ${error.message}")
        } finally {
            started = false
            enabled = false
            if (activeToken.compareAndSet(expect = token, update = 0L)) {
                runCatching { tap.disable(token) }
            }
            onStopped(failed)
        }
    }

    private companion object {
        const val SCRATCH_SAMPLES = 4_096
        const val IDLE_POLL_MIN_MS = 20L
        const val IDLE_POLL_MAX_MS = 150L
        const val NON_PCM_POLL_MS = 250L
    }
}

/**
 * Prevents MPV subtitle track-reload flicker during continuous live audio alignment.
 *
 * Because MPV applies a retimed subtitle by writing a file and calling `sub-add`/`sub-remove`,
 * tiny provisional updates (< [PROVISIONAL_REWRITE_DELTA_US]) and tiny confirmed refinements
 * (< [CONFIRMED_REWRITE_DELTA_US]) are suppressed unless lock confirmation, scene-step count,
 * or playback-rate scale changed.
 */
internal object MpvSubtitleRetimerThrottle {
    const val PROVISIONAL_REWRITE_DELTA_US = 200_000L
    const val CONFIRMED_REWRITE_DELTA_US = 100_000L

    fun shouldApply(
        previousModel: SubtitleSyncModel?,
        previousConfirmed: Boolean,
        newModel: SubtitleSyncModel?,
        newConfirmed: Boolean,
        positionMs: Long,
    ): Boolean {
        if (previousModel === newModel && previousConfirmed == newConfirmed) return false
        if (previousModel == null || newModel == null) return true
        if (previousConfirmed != newConfirmed) return true
        if (previousModel.segments.size != newModel.segments.size) return true
        if (previousModel.segments.last().scale != newModel.segments.last().scale) return true
        val posUs = positionMs.coerceAtLeast(0L) * 1_000L
        val deltaUs = abs(previousModel.delayUsAt(posUs) - newModel.delayUsAt(posUs))
        val threshold = if (newConfirmed) CONFIRMED_REWRITE_DELTA_US else PROVISIONAL_REWRITE_DELTA_US
        return deltaUs >= threshold
    }
}
