package com.nuvio.app.features.audiosync

import android.app.Application
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
import com.nuvio.app.features.audiosync.asr.SpeechToText
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MpvLiveAudioTapIntegrationTest {
    private class NoRecognizers : AudioSyncRecognizers {
        override fun isModelReady(): Boolean = false
        override fun acquire(): SpeechToText? = null
        override fun release() = Unit
    }

    private class RecordingSpotReader : AudioSyncSpotReader {
        private val lock = SynchronizedObject()
        private val requests = ArrayList<AudioSyncSpotRequest>()

        fun requests(): List<AudioSyncSpotRequest> = synchronized(lock) { requests.toList() }

        override fun isUnmetered(): Boolean = true

        override fun read(request: AudioSyncSpotRequest, sink: AudioSyncSpotSink): AudioSyncSpotResult {
            synchronized(lock) { requests += request }
            return AudioSyncSpotResult(0, 0L, null)
        }
    }

    private class FakeLiveAudioTap(
        override val isSupported: Boolean = true,
        available: Boolean = isSupported,
    ) : MpvLiveAudioTap {
        private data class Chunk(
            val samples: FloatArray,
            val sampleRate: Int,
            val generation: Int,
            val ptsUs: Long,
            val flags: Int,
        )

        private val lock = SynchronizedObject()
        private val queue = ArrayDeque<Chunk>()
        private var activeToken: Long = 0L
        private var nextToken: Long = 0L

        override var isAvailable: Boolean = available
        var enableCount = 0
            private set
        var disableCount = 0
            private set
        var paused = false
            private set
        var statusFlags = MpvAudioTapFlags.NONE

        fun offer(samples: FloatArray, sampleRate: Int, generation: Int, ptsUs: Long, flags: Int = MpvAudioTapFlags.NONE) {
            synchronized(lock) {
                queue.addLast(Chunk(samples.copyOf(), sampleRate, generation, ptsUs, flags))
            }
        }

        override fun enable(ringCapacitySamples: Int): Long {
            if (!isAvailable) return 0L
            return synchronized(lock) {
                enableCount++
                activeToken = ++nextToken
                activeToken
            }
        }

        override fun setPaused(token: Long, paused: Boolean) {
            synchronized(lock) {
                if (token != 0L && token == activeToken) {
                    this.paused = paused
                }
            }
        }

        override fun readInto(token: Long, outSamples: FloatArray, maxSamples: Int, outMeta: MpvAudioTapChunk): Int {
            synchronized(lock) {
                if (token == 0L || token != activeToken) {
                    outMeta.flags = MpvAudioTapFlags.EOF
                    return 0
                }
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    outMeta.flags = statusFlags
                    return 0
                }
                val count = minOf(next.samples.size, maxSamples, outSamples.size)
                next.samples.copyInto(outSamples, 0, 0, count)
                outMeta.sampleRate = next.sampleRate
                outMeta.sampleCount = count
                outMeta.generation = next.generation
                outMeta.ptsUs = next.ptsUs
                outMeta.flags = next.flags or statusFlags
                return count
            }
        }

        override fun disable(token: Long) {
            synchronized(lock) {
                if (token != 0L && token == activeToken) {
                    activeToken = 0L
                    disableCount++
                    queue.clear()
                }
            }
        }
    }

    @Test
    fun liveTapLocksPlaylistStreamWithSpotSamplingOffAndFreesTapOnStop() {
        val durationMs = 25 * 60_000L
        val spoken = syntheticCues(Random(404), durationMs)
        val shiftedSubtitle = spoken.map { (start, end, text) -> Triple(start - 2_500L, end - 2_500L, text) }

        val spotReader = RecordingSpotReader()
        val liveTap = FakeLiveAudioTap(isSupported = true, available = true)
        val voiceTemplate = voiceWaveform()
        val sampleRate = 16_000
        val chunkSamples = 1_600 // 100 ms
        // Feed 4 minutes of continuous playback audio (matching a live HLS/m3u8 playback window).
        val feedDurationMs = 4 * 60_000L
        val chunk = FloatArray(chunkSamples)
        var offsetMs = 0L
        while (offsetMs < feedDurationMs) {
            chunk.fill(0f)
            for (cue in spoken) {
                if (cue.second <= offsetMs || cue.first >= offsetMs + 100L) continue
                val fromIdx = ((maxOf(cue.first, offsetMs) - offsetMs) * 16L).toInt().coerceIn(0, chunkSamples)
                val toIdx = ((minOf(cue.second, offsetMs + 100L) - offsetMs) * 16L).toInt().coerceIn(fromIdx, chunkSamples)
                for (i in fromIdx until toIdx) {
                    chunk[i] = voiceTemplate[((offsetMs * 16L + i) % voiceTemplate.size).toInt()]
                }
            }
            liveTap.offer(chunk, sampleRate, generation = 1, ptsUs = offsetMs * 1_000L)
            offsetMs += 100L
        }

        val logs = ArrayList<String>()
        val logLock = SynchronizedObject()
        val engine = SpotAudioSyncEngine(
            reader = spotReader,
            recognizers = NoRecognizers(),
            log = { synchronized(logLock) { logs += it } },
            onStatus = {},
            liveTap = liveTap,
        )
        try {
            engine.samplingMode = AudioSamplingMode.OFF
            engine.setSource("https://example.com/live/master.m3u8", emptyMap())
            engine.startSession("https://example.com/sub.srt", shiftedSubtitle)

            val locked = runBlocking {
                withTimeoutOrNull(20_000L) {
                    while (!engine.isModelConfirmed) {
                        engine.onPlaybackPosition(positionMs = feedDurationMs, durationMs = durationMs)
                        delay(50L)
                    }
                    true
                } ?: false
            }
            val dump = synchronized(logLock) { logs.joinToString("\n") }
            assertTrue(locked, "expected live tap to lock on playlist stream without spot sampling:\n$dump")
            assertTrue(spotReader.requests().isEmpty(), "spot sampling should remain off")
            assertEquals(1, liveTap.enableCount)

            val model = assertNotNull(engine.currentModel(), dump)
            val delayMs = model.delayUsAt(120_000_000L) / 1_000.0
            assertTrue(abs(delayMs - 2_500.0) < 300.0, "expected ~2500ms shift, got $delayMs\n$dump")

            // Pause during background/PiP forwards to native tap and suspends reader cleanly.
            engine.setLiveTapPaused(true)
            assertTrue(liveTap.paused)
            engine.setLiveTapPaused(false)
            assertFalse(liveTap.paused)

            // Switching audio language resets heard state safely via reader thread.
            engine.audioLanguage = "en"
            engine.audioLanguage = "ja"

            // Stopping the session disables the tap so the C ring buffer is freed.
            engine.stopSession()
            assertTrue(liveTap.disableCount >= 1)
        } finally {
            engine.release()
        }
    }

    @Test
    fun nonPcmPassthroughFallsBackToSpotSamplingAndRecoversWhenPcmResumes() {
        val spotReader = RecordingSpotReader()
        val liveTap = FakeLiveAudioTap(isSupported = true, available = true).apply {
            statusFlags = MpvAudioTapFlags.NON_PCM
        }
        val engine = SpotAudioSyncEngine(
            reader = spotReader,
            recognizers = NoRecognizers(),
            log = {},
            onStatus = {},
            liveTap = liveTap,
        )
        try {
            engine.samplingMode = AudioSamplingMode.LOW
            engine.setSource("https://example.com/film.mkv", emptyMap())
            engine.startSession("https://example.com/sub.srt", syntheticCues(Random(9), 45 * 60_000L))
            val fellBack = runBlocking {
                withTimeoutOrNull(10_000L) {
                    while (!engine.isLiveAudioNonPcm || spotReader.requests().isEmpty()) {
                        engine.onPlaybackPosition(positionMs = 5_000L, durationMs = 45 * 60_000L)
                        delay(50L)
                    }
                    true
                } ?: false
            }
            assertTrue(fellBack, "expected NON_PCM tap flag to surface isLiveAudioNonPcm and fall back to spot sampling")

            // When PCM audio resumes, isLiveAudioNonPcm clears and live audio becomes active again.
            liveTap.statusFlags = MpvAudioTapFlags.NONE
            liveTap.offer(FloatArray(1_600), 16_000, generation = 2, ptsUs = 10_000_000L)
            val recovered = runBlocking {
                withTimeoutOrNull(5_000L) {
                    while (engine.isLiveAudioNonPcm || !engine.isLiveAudioActive) {
                        delay(30L)
                    }
                    true
                } ?: false
            }
            assertTrue(recovered, "expected live tap to recover once PCM resumes")

            // A second NON_PCM transition must also be detected cleanly (nonPcmReported reset).
            liveTap.statusFlags = MpvAudioTapFlags.NON_PCM
            val secondNonPcm = runBlocking {
                withTimeoutOrNull(5_000L) {
                    while (!engine.isLiveAudioNonPcm) {
                        delay(30L)
                    }
                    true
                } ?: false
            }
            assertTrue(secondNonPcm, "expected second NON_PCM transition to be detected")
        } finally {
            engine.release()
        }
    }

    @Test
    fun unpatchedBinaryNeverEnablesLiveTapAndRetimerThrottleHandlesScaleAndConfirmedDelta() {
        val spotReader = RecordingSpotReader()
        val unavailableTap = FakeLiveAudioTap(isSupported = false, available = false)
        val engine = SpotAudioSyncEngine(
            reader = spotReader,
            recognizers = NoRecognizers(),
            log = {},
            onStatus = {},
            liveTap = unavailableTap,
        )
        try {
            assertFalse(engine.isLiveAudioTapSupported)
            assertFalse(engine.hasLiveAudioTap)
            assertFalse(engine.startLiveListening())
            assertEquals(0, unavailableTap.enableCount)
        } finally {
            engine.release()
        }

        // Ownership token protects newer tap from stale disable calls.
        val tokenTap = FakeLiveAudioTap(isSupported = true, available = true)
        val token1 = tokenTap.enable()
        val token2 = tokenTap.enable()
        tokenTap.disable(token1) // Stale token must be ignored.
        assertEquals(0, tokenTap.disableCount)
        val staleMeta = MpvAudioTapChunk()
        assertEquals(0, tokenTap.readInto(token1, FloatArray(16), 16, staleMeta))
        assertEquals(MpvAudioTapFlags.EOF, staleMeta.flags)
        tokenTap.disable(token2)
        assertEquals(1, tokenTap.disableCount)

        val initial = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_000.0)))
        val tinyProvisional = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_120.0)))
        val smallConfirmed = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_060.0)))
        val mediumConfirmed = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_130.0)))
        val rateCorrected = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.001, 2_000.0)))
        val withStep = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(0L, 1.0, 2_000.0),
                SubtitleSyncSegment(600_000L, 1.0, 4_000.0),
            ),
        )

        // First application always runs.
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(null, false, initial, false, 60_000L))
        // Provisional tweak < 200 ms is suppressed to avoid sub-add/sub-remove flicker.
        assertFalse(MpvSubtitleRetimerThrottle.shouldApply(initial, false, tinyProvisional, false, 60_000L))
        // Confirming lock always applies even for small shift delta.
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(initial, false, tinyProvisional, true, 60_000L))
        // Confirmed refinement < 100 ms stays suppressed; >= 100 ms applies.
        assertFalse(MpvSubtitleRetimerThrottle.shouldApply(initial, true, smallConfirmed, true, 60_000L))
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(initial, true, mediumConfirmed, true, 60_000L))
        // Rate correction (scale change) always applies immediately even with 0 ms delta at playhead.
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(initial, true, rateCorrected, true, 0L))
        // New scene-step segment always applies.
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(initial, true, withStep, true, 60_000L))
        // Withdrawal back to null always applies.
        assertTrue(MpvSubtitleRetimerThrottle.shouldApply(initial, true, null, false, 60_000L))
    }

    @Test
    fun androidControllerAcceptsExternalMpvLivePcmAndResetsTimelineOnAudioTrackSwitch() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = AudioSubtitleSyncController(
            context = context,
            manualDelayMs = { 0 },
            onStatus = {},
            requestSwitch = {},
        )
        try {
            controller.externalPlayer = true
            controller.spotOnly = false
            controller.externalAudioLanguage = "en"
            controller.samplingMode = AudioSamplingMode.OFF
            controller.listensBeforeSession = true
            controller.onSourceChanged("https://example.com/live/master.m3u8")

            val durationMs = 25 * 60_000L
            val spoken = syntheticCues(Random(505), durationMs)
            val shiftedCues = spoken.map { (start, end, text) ->
                val shiftedStartUs = (start - 2_500L).coerceAtLeast(0L) * 1_000L
                val durationUs = (end - start).coerceAtLeast(100L) * 1_000L
                CuesWithTiming(listOf(Cue.Builder().setText(text).build()), shiftedStartUs, durationUs)
            }
            controller.startSession("https://example.com/sub.srt", shiftedCues)

            val voiceTemplate = voiceWaveform()
            val sampleRate = 16_000
            val chunkSamples = 1_600 // 100 ms
            val feedDurationMs = 4 * 60_000L
            val chunk = FloatArray(chunkSamples)
            var offsetMs = 0L
            var fedChunks = 0
            while (offsetMs < feedDurationMs) {
                chunk.fill(0f)
                for (cue in spoken) {
                    if (cue.second <= offsetMs || cue.first >= offsetMs + 100L) continue
                    val fromIdx = ((maxOf(cue.first, offsetMs) - offsetMs) * 16L).toInt().coerceIn(0, chunkSamples)
                    val toIdx = ((minOf(cue.second, offsetMs + 100L) - offsetMs) * 16L).toInt().coerceIn(fromIdx, chunkSamples)
                    for (i in fromIdx until toIdx) {
                        chunk[i] = voiceTemplate[((offsetMs * 16L + i) % voiceTemplate.size).toInt()]
                    }
                }
                val ptsUs = offsetMs * 1_000L
                if (controller.wantsExternalPlaybackPcm(ptsUs, 100_000L)) {
                    controller.offerExternalPlaybackPcm(chunk.copyOf(), chunkSamples, sampleRate, ptsUs)
                    if (++fedChunks % 20 == 0) Thread.sleep(10L)
                }
                offsetMs += 100L
            }

            val locked = runBlocking {
                withTimeoutOrNull(20_000L) {
                    while (!controller.isModelConfirmed) {
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
                        controller.onPlaybackPosition(positionMs = feedDurationMs, durationMs = durationMs)
                        delay(50L)
                    }
                    true
                } ?: false
            }
            assertTrue(locked, "expected Android AudioSubtitleSyncController to lock via external libmpv live PCM")
            val model = assertNotNull(controller.currentModel())
            val delayMs = model.delayUsAt(120_000_000L) / 1_000.0
            assertTrue(abs(delayMs - 2_500.0) < 300.0, "expected ~2500ms shift, got $delayMs")

            // Live discontinuity does not crash or poison subsequent PCM ingestion.
            controller.onExternalPlaybackDiscontinuity()

            // Switching to a dubbed audio track resets the heard timeline just like ExoPlayer's onAudioTrackSelected.
            assertFalse(controller.wantsExternalPlaybackPcm(10_000_000L, 100_000L))
            controller.externalAudioLanguage = "ja"
            assertTrue(controller.wantsExternalPlaybackPcm(10_000_000L, 100_000L))
        } finally {
            controller.release()
        }
    }

    private fun syntheticCues(random: Random, durationMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = 4_000L + random.nextLong(8_000L)
        while (t < durationMs - 60_000L) {
            val length = 900L + random.nextLong(3_200L)
            cues += Triple(t, t + length, "Line ${cues.size} of dialogue")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 7_000L else 2_200L)
        }
        return cues
    }

    private fun voiceWaveform(): FloatArray = FloatArray(16_000).also { samples ->
        var state = 12_345L
        for (n in samples.indices) {
            state = (state * 1_103_515_245L + 12_345L) % (1L shl 31)
            val noise = state.toDouble() / (1L shl 31) - 0.5
            val envelope = 0.5 + 0.5 * sin(2 * PI * 3 * n / 16_000)
            val t = 2 * PI * n / 16_000
            samples[n] = (0.25 * envelope * (sin(180 * t) + 0.5 * sin(360 * t) + 0.25 * sin(540 * t)) + 0.02 * noise).toFloat()
        }
    }
}
