package com.nuvio.app.features.audiosync

import android.app.Application
import com.nuvio.app.features.audiosync.asr.SpeechToText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpotAudioSyncEngineTest {
    private class RecordingReader(private val unmetered: Boolean) : AudioSyncSpotReader {
        private val lock = SynchronizedObject()
        private val requests = ArrayList<AudioSyncSpotRequest>()

        fun requests(): List<AudioSyncSpotRequest> = synchronized(lock) { requests.toList() }

        override fun isUnmetered(): Boolean = unmetered

        override fun read(request: AudioSyncSpotRequest, sink: AudioSyncSpotSink): AudioSyncSpotResult {
            synchronized(lock) { requests += request }
            val silence = FloatArray(1_600)
            for (spotMs in request.spotsMs) {
                if (sink.isCancelled()) break
                sink.onPcm(silence, silence.size, 16_000, spotMs * 1_000L)
                sink.onSpotEnd()
            }
            return AudioSyncSpotResult(request.spotsMs.size, 1_000L, null)
        }
    }

    private class NoRecognizers : AudioSyncRecognizers {
        override fun isModelReady(): Boolean = false

        override fun acquire(): SpeechToText? = null

        override fun release() = Unit
    }

    private fun dialogue(): List<Triple<Long, Long, String>> =
        (0 until 300).map { index ->
            val startMs = 60_000L + index * 7_000L
            Triple(startMs, startMs + 2_500L, "Line number $index of the dialogue")
        }

    private val logLock = SynchronizedObject()
    private val logs = ArrayList<String>()

    private fun logs(): String = synchronized(logLock) { logs.joinToString("\n") }

    private fun engine(reader: AudioSyncSpotReader, mode: AudioSamplingMode): SpotAudioSyncEngine =
        SpotAudioSyncEngine(reader, NoRecognizers(), log = { synchronized(logLock) { logs += it } }, onStatus = {}).apply {
            samplingMode = mode
            setSource("https://example.com/film.mkv", mapOf("Referer" to "https://example.com"))
        }

    private fun drive(engine: SpotAudioSyncEngine, timeoutMs: Long = 10_000L, until: () -> Boolean): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!until()) {
                engine.onPlaybackPosition(positionMs = 5_000L, durationMs = 45 * 60_000L)
                delay(50L)
            }
            true
        } ?: false
    }

    @Test
    fun samplesPlannedDialogueSpotsOnceASessionStarts() {
        val reader = RecordingReader(unmetered = false)
        val engine = engine(reader, AudioSamplingMode.LOW)
        try {
            engine.startSession("https://example.com/subtitle.srt", dialogue())
            assertTrue(drive(engine) { reader.requests().isNotEmpty() }, logs())
            val request = reader.requests().single()
            assertEquals("https://example.com/film.mkv", request.url)
            assertEquals(mapOf("Referer" to "https://example.com"), request.headers)
            assertEquals(30_000L, request.spotMs)
            assertEquals(75L * 1_000_000L, request.targetBytes)
            assertTrue(request.spotsMs.isNotEmpty() && request.spotsMs.size <= 3)
            assertTrue(request.spotsMs.all { it in 0L until 45 * 60_000L })
        } finally {
            engine.release()
        }
    }

    @Test
    fun highSamplingOnUnmeteredNetworksUsesTheLargerBudget() {
        val reader = RecordingReader(unmetered = true)
        val engine = engine(reader, AudioSamplingMode.HIGH)
        try {
            engine.startSession("https://example.com/subtitle.srt", dialogue())
            assertTrue(drive(engine) { reader.requests().isNotEmpty() }, logs())
            assertEquals(150L * 1_000_000L, reader.requests().single().targetBytes)
        } finally {
            engine.release()
        }
    }

    @Test
    fun shortSubtitlesDoNotStartASession() {
        val reader = RecordingReader(unmetered = false)
        var failed = false
        val engine = SpotAudioSyncEngine(reader, NoRecognizers(), log = {}, onStatus = {
            if (it == AudioSyncStatus.Failed) failed = true
        })
        try {
            engine.setSource("https://example.com/film.mkv", emptyMap())
            engine.startSession("https://example.com/subtitle.srt", dialogue().take(5))
            assertTrue(drive(engine) { failed })
            assertTrue(reader.requests().isEmpty())
            assertNull(engine.currentModel())
        } finally {
            engine.release()
        }
    }

    @Test
    fun playlistStreamsSkipSpotSamplingWhileDirectMp4Samples() {
        val playlistReader = RecordingReader(unmetered = true)
        val playlistEngine = SpotAudioSyncEngine(
            playlistReader,
            NoRecognizers(),
            log = { synchronized(logLock) { logs += it } },
            onStatus = {},
        )
        try {
            playlistEngine.samplingMode = AudioSamplingMode.LOW
            playlistEngine.setSource("https://example.com/live/master.m3u8?token=abc", emptyMap())
            playlistEngine.startSession("https://example.com/subtitle.srt", dialogue())
            assertTrue(drive(playlistEngine) { logs().contains("playlist streams") }, logs())
            assertTrue(playlistReader.requests().isEmpty(), logs())
        } finally {
            playlistEngine.release()
        }

        val mp4Reader = RecordingReader(unmetered = true)
        val mp4Engine = SpotAudioSyncEngine(
            mp4Reader,
            NoRecognizers(),
            log = { synchronized(logLock) { logs += it } },
            onStatus = {},
        )
        try {
            mp4Engine.samplingMode = AudioSamplingMode.LOW
            mp4Engine.setSource("https://example.com/film.mp4?fallback=master.m3u8", emptyMap())
            mp4Engine.startSession("https://example.com/subtitle.srt", dialogue())
            assertTrue(drive(mp4Engine) { mp4Reader.requests().isNotEmpty() }, logs())
            assertEquals("https://example.com/film.mp4?fallback=master.m3u8", mp4Reader.requests().single().url)
        } finally {
            mp4Engine.release()
        }
    }

    @Test
    fun switchesToMatchingSameLanguageCandidateOnSampledSpotsAndAdoptsHandover() {
        val durationMs = 45 * 60_000L
        val spoken = syntheticCues(Random(202), durationMs)
        val chosen = syntheticCues(Random(101), durationMs)
        val decoy = syntheticCues(Random(303), durationMs)
        val winner = spoken.map { (start, end, text) -> Triple(start - 2_500L, end - 2_500L, text) }

        val chosenUrl = "https://example.com/chosen.srt"
        val decoyUrl = "https://example.com/decoy.srt"
        val winnerUrl = "https://example.com/winner.srt"
        val bodies = mapOf(
            decoyUrl to toSrt(decoy),
            winnerUrl to toSrt(winner),
        )

        val reader = SpeechGeneratingReader(spoken)
        var switchedUrl: String? = null
        var syncedStatus: AudioSyncStatus.Synced? = null
        val engine = SpotAudioSyncEngine(
            reader = reader,
            recognizers = NoRecognizers(),
            log = { synchronized(logLock) { logs += it } },
            onStatus = { status ->
                if (status is AudioSyncStatus.Synced) syncedStatus = status
            },
            requestSwitch = { url -> switchedUrl = url },
            downloadSubtitle = { ref -> bodies.getValue(ref.url) },
        )
        try {
            engine.samplingMode = AudioSamplingMode.HIGH
            engine.setSource("https://example.com/film.mkv", emptyMap())
            engine.setReferenceSubtitles(
                listOf(
                    AudioSyncReference(chosenUrl, "ro", emptyMap()),
                    AudioSyncReference(decoyUrl, "Romanian", emptyMap()),
                    AudioSyncReference(winnerUrl, "rum", emptyMap()),
                ),
            )
            engine.startSession(chosenUrl, chosen)
            assertTrue(drive(engine, timeoutMs = 30_000L) { switchedUrl != null }, logs())
            assertEquals(winnerUrl, switchedUrl, logs())

            engine.startSession(winnerUrl, winner)
            assertTrue(drive(engine, timeoutMs = 10_000L) { syncedStatus != null && engine.isModelConfirmed }, logs())
            val adopted = assertNotNull(engine.currentModel(), logs())
            val delayMs = adopted.delayUsAt(600_000_000L) / 1_000.0
            assertTrue(abs(delayMs - 2_500.0) < 250.0, "expected ~2500ms delay, got $delayMs\n${logs()}")
        } finally {
            engine.release()
        }
    }

    private fun syntheticCues(random: Random, durationMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = 5_000L + random.nextLong(20_000L)
        while (t < durationMs - 60_000L) {
            val length = 800L + random.nextLong(3_700L)
            cues += Triple(t, t + length, "Line ${cues.size} of dialogue")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 9_000L else 2_500L)
        }
        return cues
    }

    private fun toSrt(cues: List<Triple<Long, Long, String>>): String = buildString {
        cues.forEachIndexed { index, (start, end, text) ->
            append(index + 1).append('\n')
            append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
            append(text).append("\n\n")
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = clamped / 60_000L % 60L
        val seconds = clamped / 1_000L % 60L
        val millis = clamped % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private class SpeechGeneratingReader(
        private val spoken: List<Triple<Long, Long, String>>,
    ) : AudioSyncSpotReader {
        private val voiceTemplate: FloatArray = FloatArray(16_000).also { samples ->
            var state = 12_345L
            for (n in samples.indices) {
                state = (state * 1_103_515_245L + 12_345L) % (1L shl 31)
                val noise = state.toDouble() / (1L shl 31) - 0.5
                val envelope = 0.5 + 0.5 * sin(2 * PI * 3 * n / 16_000)
                val t = 2 * PI * n / 16_000
                samples[n] = (0.25 * envelope * (sin(180 * t) + 0.5 * sin(360 * t) + 0.25 * sin(540 * t)) + 0.02 * noise).toFloat()
            }
        }

        override fun isUnmetered(): Boolean = true

        override fun read(request: AudioSyncSpotRequest, sink: AudioSyncSpotSink): AudioSyncSpotResult {
            val sampleRate = 16_000
            val chunkSamples = 1_600 // 100 ms
            val chunk = FloatArray(chunkSamples)
            var sampled = 0
            for (spotMs in request.spotsMs) {
                if (sink.isCancelled()) break
                val spotEndMs = spotMs + request.spotMs
                val overlapping = spoken.filter { it.second > spotMs && it.first < spotEndMs }
                var offsetMs = 0L
                while (offsetMs < request.spotMs && !sink.isCancelled()) {
                    val chunkStartMs = spotMs + offsetMs
                    chunk.fill(0f)
                    for (cue in overlapping) {
                        val fromMs = maxOf(cue.first, chunkStartMs)
                        val toMs = minOf(cue.second, chunkStartMs + 100L)
                        if (toMs > fromMs) {
                            val fromIdx = ((fromMs - chunkStartMs) * 16L).toInt().coerceIn(0, chunkSamples)
                            val toIdx = ((toMs - chunkStartMs) * 16L).toInt().coerceIn(fromIdx, chunkSamples)
                            for (i in fromIdx until toIdx) {
                                chunk[i] = voiceTemplate[((chunkStartMs * 16L + i) % voiceTemplate.size).toInt()]
                            }
                        }
                    }
                    sink.onPcm(chunk, chunkSamples, sampleRate, chunkStartMs * 1_000L)
                    offsetMs += 100L
                }
                sink.onSpotEnd()
                sampled++
            }
            return AudioSyncSpotResult(sampled, 4_000_000L, null)
        }
    }
}
