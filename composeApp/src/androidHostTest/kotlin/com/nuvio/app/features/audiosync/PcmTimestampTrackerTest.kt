@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmTimestampTrackerTest {
    @Test
    fun eightOpusBuffersSharingOneTimestampStayContinuous() {
        val clock = PcmTimestampTracker()
        repeat(400) { index ->
            val reported = 352_000_000L + (index / 8) * 160_000L
            assertEquals(352_000_000L + index * 20_000L, clock.resolve(reported, 960, 48_000))
        }
        assertEquals(350L, clock.repeatedTimestamps)
    }

    @Test
    fun realGapsSeeksAndRateChangesReanchorTheClock() {
        val clock = PcmTimestampTracker()
        assertEquals(10_000_000L, clock.resolve(10_000_000L, 960, 48_000))
        assertEquals(10_020_000L, clock.resolve(10_000_000L, 960, 48_000))
        assertEquals(15_000_000L, clock.resolve(15_000_000L, 960, 48_000))
        assertEquals(3_000_000L, clock.resolve(3_000_000L, 960, 48_000))
        assertEquals(3_000_000L, clock.resolve(3_000_000L, 320, 16_000))
        assertEquals(3_020_000L, clock.resolve(3_000_000L, 320, 16_000))
        clock.reset()
        assertEquals(3_000_000L, clock.resolve(3_000_000L, 320, 16_000))
    }

    @Test
    fun groupedCapturePcmGetsPastSpeechDetectorWarmupWithoutFillingGaps() {
        val clock = PcmTimestampTracker()
        val timeline = SpeechTimeline()
        val analyzer = analyzer(timeline)
        val pcm = FloatArray(960)
        for (startUs in listOf(352_000_000L, 420_000_000L)) {
            repeat(300) { index ->
                val resolved = clock.resolve(startUs + (index / 8) * 160_000L, pcm.size, 48_000)
                analyzer.accept(pcm, pcm.size, 48_000, resolved)
            }
        }
        assertEquals(2L, analyzer.starts)
        assertTrue(timeline.diagnostics().analysedSeconds > 11.5)
        assertEquals(0, timeline.knownFramesIn(12_000, 12_500), "gap between scenes must stay unknown")
    }

    @Test
    fun playbackWithGroupedTimestampsBuildsSpeechEvidence() {
        val timeline = SpeechTimeline()
        val analyzer = analyzer(timeline)
        var deliveredFrames = 0
        val tap = PlaybackAudioTap(sink { buffer -> buffer.position(buffer.limit()); true }, object : PlaybackPcmListener {
            override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long) = timeline.needsAnalysis(mediaTimeUs, durationUs)
            override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
                deliveredFrames += frames
                analyzer.accept(mono, frames, sampleRate, mediaTimeUs)
            }
        })
        tap.configure(pcmFormat(), 0, null)
        // A codec may reuse the same ByteBuffer object for a new, fully consumed output buffer.
        val buffer = ByteBuffer.allocate(960 * 6 * 2)
        repeat(300) { index ->
            buffer.clear()
            assertTrue(tap.handleBuffer(buffer, 352_000_000L + (index / 8) * 160_000L, 1))
        }
        assertEquals(300 * 960, deliveredFrames)
        assertEquals(1L, analyzer.starts)
        assertTrue(timeline.diagnostics().analysedSeconds > 5.7)
    }

    @Test
    fun partiallyConsumedBufferIsNotAnalyzedTwiceButNextBufferWithSamePtsIs() {
        var calls = 0
        val times = mutableListOf<Long>()
        val tap = PlaybackAudioTap(sink { buffer ->
            calls++
            if (calls % 2 == 1) {
                buffer.position(buffer.position() + 12)
                false
            } else {
                buffer.position(buffer.limit())
                true
            }
        }, object : PlaybackPcmListener {
            override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long) = true
            override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
                assertEquals(960, frames)
                times += mediaTimeUs
            }
        })
        tap.configure(pcmFormat(), 0, null)
        val buffer = ByteBuffer.allocate(960 * 6 * 2)
        repeat(2) {
            buffer.clear()
            assertEquals(false, tap.handleBuffer(buffer, 352_000_000L, 1))
            assertEquals(true, tap.handleBuffer(buffer, 352_000_000L, 1))
        }
        assertEquals(listOf(352_000_000L, 352_020_000L), times)
        tap.flush()
        buffer.clear()
        tap.handleBuffer(buffer, 352_000_000L, 1)
        assertEquals(352_000_000L, times.last(), "flush must reset the PCM clock")
    }

    private fun analyzer(timeline: SpeechTimeline): SpeechAnalyzer {
        val weights = SileroVadWeights.read(File("src/commonMain/composeResources/files/silero_vad_v5_16k.bin").readBytes())
        return SpeechAnalyzer(SileroVad(weights), timeline)
    }

    private fun pcmFormat() = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    private fun sink(handle: (ByteBuffer) -> Boolean): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java),
    ) { _, method, args ->
        if (method.name == "handleBuffer") handle(args!![0] as ByteBuffer) else null
    } as AudioSink
}
