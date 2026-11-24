package com.nuvio.app.features.audiosync

import android.app.Application
import com.nuvio.app.features.audiosync.asr.SpeechToText
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun drive(engine: SpotAudioSyncEngine, until: () -> Boolean): Boolean = runBlocking {
        withTimeoutOrNull(10_000L) {
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
}
