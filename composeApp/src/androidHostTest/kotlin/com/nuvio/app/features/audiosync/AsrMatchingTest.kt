package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.audiosync.asr.HeardWord
import com.nuvio.app.features.audiosync.asr.ReferenceSubtitle
import com.nuvio.app.features.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.audiosync.asr.WordAnchorMatcher
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrMatchingTest {
    /** Like real dialogue: a few common words and many that occur only now and then. */
    private val vocabulary = List(600) { index ->
        val syllables = listOf("ka", "lo", "mir", "den", "sa", "tor", "vel", "ni", "ra", "bex")
        syllables[index % 10] + syllables[(index / 10) % 10] + syllables[(index / 100) % 10]
    }

    private fun script(random: Random, lines: Int): List<Triple<Long, Long, String>> {
        var t = 20_000L
        return List(lines) {
            val words = List(3 + random.nextInt(5)) { vocabulary[random.nextInt(vocabulary.size)] }
            val length = 1_200L + random.nextLong(2_500L)
            Triple(t, t + length, words.joinToString(" ")).also { t += length + 500L + random.nextLong(3_000L) }
        }
    }

    /** Words heard at media = subtitle + shift, with some misrecognised. */
    private fun hear(cues: List<Triple<Long, Long, String>>, shiftSec: Double, random: Random, upToLine: Int): List<HeardWord> =
        cues.take(upToLine).flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexedNotNull { k, word ->
                if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                val t = (start + (end - start) * k / words.size) / 1_000.0 + shiftSec + random.nextDouble(-0.15, 0.15)
                HeardWord(t, word, line)
            }
        }

    @Test
    fun locksWithinAFewLines() {
        val random = Random(1)
        val cues = script(random, 60)
        val matcher = WordAnchorMatcher(cues)
        val fit = assertNotNull(matcher.fit(hear(cues, 7.3, random, upToLine = 6)))
        assertTrue(fit.isConfident, "fit $fit")
        assertTrue(abs(fit.shiftSec - 7.3) < 0.3, "shift ${fit.shiftSec}")
    }

    @Test
    fun votesBuiltOnceGiveTheSameFitsAsTheWords() {
        val random = Random(5)
        val cues = script(random, 400)
        val matcher = WordAnchorMatcher(cues)
        val heard = hear(cues, -4.1, random, upToLine = 400)
        val votes = matcher.votes(heard)
        val fit = assertNotNull(matcher.fit(votes))
        assertTrue(fit.isConfident && abs(fit.shiftSec + 4.1) < 0.3, "fit $fit")
        assertEquals(matcher.fit(heard), fit)
        assertEquals(matcher.localFits(heard, fit.scale), matcher.localFits(votes, fit.scale))
        assertEquals(matcher.fitByPlace(heard, fit.scale), matcher.fitByPlace(votes, fit.scale))
        // Votes are cast against one subtitle's words.
        assertFailsWith<IllegalArgumentException> { WordAnchorMatcher(cues).fit(votes) }
    }

    @Test
    fun frameRateIsFoundFromWordsHeardLateInTheFilm() {
        // Subtitle made for 23.976 fps, video at 25 fps: past ~23 minutes every word is more than a
        // minute off at 1x. Words heard only between minutes 30 and 40 (a resume) must still lock.
        // Every subtitle word is distinct, so no chance match lands within a minute at 1x either.
        val random = Random(11)
        val cues = List(900) { line ->
            val start = 20_000L + line * 5_000L
            Triple(start, start + 3_000L, "word${line}a word${line}b word${line}c word${line}d")
        }
        val scale = 23.976 / 25.0
        val heard = cues.withIndex()
            .filter { (_, cue) -> cue.first in 1_800_000L..2_400_000L }
            .flatMap { (line, cue) ->
                val (start, end, text) = cue
                val words = text.split(' ')
                words.mapIndexedNotNull { k, word ->
                    if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                    val subSec = (start + (end - start) * k / words.size) / 1_000.0
                    HeardWord(subSec * scale + random.nextDouble(-0.15, 0.15), word, line)
                }
            }
        val fit = assertNotNull(WordAnchorMatcher(cues).fit(heard))
        assertEquals(scale, fit.scale, 1e-9, "fit $fit")
        assertTrue(fit.isConfident, "fit $fit")
        assertTrue(abs(fit.shiftSec) < 0.3, "shift ${fit.shiftSec}")
    }

    @Test
    fun recognitionRunsAheadOfExpectedReferencesAndStopsListeningWhenUseless() {
        val cues = script(Random(12), 30)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        engine.setRecognizer { recognised.incrementAndGet(); emptyList() }
        engine.startSession(SubtitleSpeechTrack.fromCues(cues), emptyList())
        fun segment(frame: Int) = FloatArray(SileroVad.CHUNK_SAMPLES * 10) { frame.toFloat() }
        engine.offerSegment(100, segment(100))
        Thread.sleep(300)
        assertEquals(0, recognised.get(), "no reference and none expected: not recognised")
        // The references of a translated subtitle are downloading: words are recognised meanwhile,
        // so they are ready the moment one arrives.
        engine.expectReferences(60_000L)
        val deadline = System.currentTimeMillis() + 5_000
        while (recognised.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, recognised.get(), "recognised without waiting for a reference")
        engine.addReference(ReferenceSubtitle("en", cues, null))
        engine.setListening(false)
        engine.offerSegment(200, segment(200))
        Thread.sleep(300)
        assertEquals(1, recognised.get(), "not listening: speech is not kept")
        engine.setListening(true)
        engine.offerSegment(300, segment(300))
        val again = System.currentTimeMillis() + 5_000
        while (recognised.get() < 2 && System.currentTimeMillis() < again) Thread.sleep(10)
        engine.release()
        assertEquals(2, recognised.get())
    }

    @Test
    fun wordsHeardBeforeTheReferenceArrivesLockOnceItDoes() {
        val english = script(Random(31), 250)
        val translation = english.map { (a, b, _) -> Triple(a + 2_000, b + 2_000, "linie") }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(speech(english), onLock = { locks += it })
        engine.startSession(target, emptyList())
        engine.expectReferences(60_000L)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val heardLines = english.indices.filter { english[it].first / 1_000 in 30..150 || english[it].first / 1_000 in 500..700 }
        heardLines.forEach { line ->
            engine.offerSegment((english[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = english[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word -> ((start + (end - start) * k / words.size) / 1_000.0 - segmentStartSec) to word }
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(heardLines.size, recognised.get(), "all speech recognised before any reference")
        assertTrue(locks.isEmpty(), "nothing to match against yet: $locks")
        val heard = engine.heardWordCount
        engine.addReference(ReferenceSubtitle("en", english, bridge))
        val lockDeadline = System.currentTimeMillis() + 10_000
        while (locks.none { it.final } && System.currentTimeMillis() < lockDeadline) Thread.sleep(20)
        engine.release()
        val final = assertNotNull(locks.lastOrNull { it.final }, "no lock from words heard earlier: $locks")
        assertEquals(heardLines.size, recognised.get(), "nothing recognised again: the earlier words were used")
        assertTrue(heard > 100, "words $heard")
        assertTrue(abs(final.segments.first().shiftMs + 2_000.0) < 300.0, "lock $final")
    }

    @Test
    fun recognitionStopsWhenExpectedReferencesNeverArrive() {
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        engine.setRecognizer { recognised.incrementAndGet(); emptyList() }
        engine.startSession(SubtitleSpeechTrack.fromCues(script(Random(13), 30)), emptyList())
        fun segment(frame: Int) = FloatArray(SileroVad.CHUNK_SAMPLES * 10) { frame.toFloat() }
        // The downloads failed: past the expected time, speech is no longer recognised.
        engine.expectReferences(200L)
        Thread.sleep(400)
        engine.offerSegment(100, segment(100))
        Thread.sleep(300)
        assertEquals(0, recognised.get(), "recognised although no reference is coming")
        // Every reference finished without a match: expectation withdrawn at once.
        engine.expectReferences(60_000L)
        engine.expectReferences(0L)
        engine.offerSegment(200, segment(200))
        Thread.sleep(300)
        engine.release()
        assertEquals(0, recognised.get())
    }

    @Test
    fun unrelatedWordsDoNotLock() {
        val random = Random(2)
        val matcher = WordAnchorMatcher(script(random, 60))
        val heard = List(80) { HeardWord(30.0 + it * 2.5, "unrelated${it % 7}", it / 3) }
        val fit = matcher.fit(heard)
        assertTrue(fit == null || !fit.isConfident, "fit $fit")
    }

    @Test
    fun bridgesTranslationToReference() {
        val random = Random(3)
        val english = script(random, 80)
        // Same line timings, different words, shifted by 4.2 s relative to the English file.
        val translated = english.map { (a, b, _) -> Triple(a - 4_200L, b - 4_200L, "linie tradusa aici") }
        val bridge = assertNotNull(
            SubtitleBridge.align(SubtitleSpeechTrack.fromCues(translated), SubtitleSpeechTrack.fromCues(english)),
        )
        assertTrue(abs(bridge.shiftSec - 4.2) < 0.05, "bridge $bridge")
    }

    @Test
    fun sampledSpeechIsRecognisedFirstAcrossPlaces() {
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = java.util.concurrent.CountDownLatch(6)
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        val cues = script(Random(6), 30)
        engine.startSession(SubtitleSpeechTrack.fromCues(cues), listOf(ReferenceSubtitle("en", cues, null)))
        fun segment(frame: Int) = FloatArray(SileroVad.CHUNK_SAMPLES * 10) { frame.toFloat() }
        engine.onPlayhead(0L)
        listOf(100, 200, 300).forEach { engine.offerSegment(it, segment(it)) }
        // Two segments sampled at one place (~30 min), one at another (~60 min).
        listOf(56_000, 56_100, 112_000).forEach { engine.offerSegment(it, segment(it), spread = true) }
        engine.setRecognizer { samples ->
            order += samples[0].toInt()
            done.countDown()
            emptyList()
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        engine.release()
        assertEquals(listOf(56_000, 112_000, 56_100, 100, 200, 300), order.toList())
    }

    @Test
    fun englishReferenceAtAnotherFrameRateDoesNotSkewTheLock() {
        // Video and translated subtitle agree; the English reference was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = false)
        assertEquals(1.0, lock.scale, 0.002, "lock $lock")
        assertTrue(abs(lock.shiftMs) < 150.0, "lock $lock")
        assertTrue(!lock.final, "a rate chosen from a few lines stays open: $lock")
    }

    @Test
    fun translationAtAnotherFrameRateIsCorrected() {
        // Video and English reference agree; the translated subtitle was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = true)
        assertEquals(25.0 / 23.976, lock.scale, 0.002, "lock $lock")
    }

    @Test
    fun sceneAddedByTheReleaseBecomesAStep() {
        // Subtitle made for a cut without 2.4 s at 5:00: it is 1.5 s early before and 3.9 s after.
        val subtitle = script(Random(9), 250)
        val media = subtitle.map { (a, b, t) ->
            val shift = if (a < 300_000L) 1_500L else 3_900L
            Triple(a + shift, b + shift, t)
        }
        val target = SubtitleSpeechTrack.fromCues(subtitle.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(subtitle)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(speech(media), onLock = { locks += it })
        engine.startSession(target, listOf(com.nuvio.app.features.audiosync.asr.ReferenceSubtitle("en", subtitle, bridge)))
        // Words heard in three places, as from sampled spots.
        val heardLines = media.indices.filter { media[it].first / 1_000 in 30..150 || media[it].first / 1_000 in 500..700 || media[it].first / 1_000 in 900..1_000 }
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        heardLines.forEach { line ->
            engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val random = Random(10)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        val lock = locks.last()
        assertTrue(lock.final, "lock $lock")
        assertEquals(2, lock.segments.size, "segments ${lock.segments}")
        val (before, after) = lock.segments
        assertTrue(abs(before.shiftMs - 1_500.0) < 150.0, "before $before")
        assertTrue(abs(after.shiftMs - 3_900.0) < 150.0, "after $after")
        assertTrue(abs(after.fromMediaMs - 301_500L) < 20_000L, "step at ${after.fromMediaMs}")
        assertEquals(1.0, before.scale, 1e-9)
    }

    @Test
    fun translationWithASceneTheReferenceLacksBecomesAStep() {
        // The English file matches the video; the translation was made for a cut with 30 s more
        // at 5:00, so one whole-file offset between the two is wrong on one side of it.
        val english = script(Random(11), 250)
        val translation = english.map { (a, b, _) ->
            val extra = if (a < 300_000L) 0L else 30_000L
            Triple(a + extra, b + extra, "linie")
        }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val lock = lockFromHeardWords(english, target, english, bridge, listOf(30..150, 500..700, 900..1_000))
        assertTrue(lock.final, "lock $lock")
        assertEquals(2, lock.segments.size, "segments ${lock.segments}")
        val (before, after) = lock.segments
        assertTrue(abs(before.shiftMs) < 150.0, "before $before")
        assertTrue(abs(after.shiftMs + 30_000.0) < 150.0, "after $after")
        assertTrue(abs(after.fromMediaMs - 300_000L) < 20_000L, "step at ${after.fromMediaMs}")
    }

    @Test
    fun translationOffsetWhereTheWordsAreHeardWins() {
        // Words heard only after the scene the translation adds: the offset there, not the
        // whole-file one, which the earlier (longer) part of the file decides.
        val english = script(Random(12), 250)
        val translation = english.map { (a, b, _) ->
            val extra = if (a < 780_000L) 0L else 30_000L
            Triple(a + extra, b + extra, "linie")
        }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        assertTrue(abs(bridge.shiftSec) < 1.0, "bridge $bridge")
        val lock = lockFromHeardWords(english, target, english, bridge, listOf(830..1_050))
        val delayMs = lock.segments.last().shiftMs
        assertTrue(abs(delayMs + 30_000.0) < 150.0, "lock $lock")
    }

    @Test
    fun ambiguousBridgeIsNotConfirmedWithoutCorroboration() {
        // One English reference, bridged to the translation with the wrong offset (similar line
        // patterns at another offset), and no speech timing to check it against: the result may be
        // shown as an estimate but never confirmed, so it can still be replaced.
        val english = script(Random(21), 250)
        val translation = english.map { (a, b, _) -> Triple(a, b, "linie") }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val wrongBridge = com.nuvio.app.features.audiosync.asr.BridgeFit(1.0, 2.0, 0.6, 0.2)
        val locks = locksFromHeardWords(
            english, target,
            listOf(com.nuvio.app.features.audiosync.asr.ReferenceSubtitle("en", english, wrongBridge)),
            listOf(30..150, 500..700), SpeechTimeline(),
        )
        assertTrue(locks.isNotEmpty(), "no estimate at all")
        assertTrue(locks.none { it.final }, "confirmed through an uncorroborated bridge: $locks")
    }

    @Test
    fun bridgesOfTwoReferencesThatAgreeConfirm() {
        // Two different English files (one 5 s later), each bridged on its own: both put the lines
        // in the same place, which confirms the sync even without speech timing.
        val english = script(Random(22), 250)
        val later = english.map { (a, b, t) -> Triple(a + 5_000, b + 5_000, t) }
        val translation = english.map { (a, b, _) -> Triple(a, b, "linie") }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val references = listOf(english, later).mapIndexed { index, cues ->
            val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(cues)))
            com.nuvio.app.features.audiosync.asr.ReferenceSubtitle("en$index", cues, bridge)
        }
        val locks = locksFromHeardWords(english, target, references, listOf(30..150, 500..700), SpeechTimeline())
        val final = locks.lastOrNull { it.final }
        assertNotNull(final, "not confirmed: $locks")
        assertTrue(abs(final.segments.first().shiftMs) < 300.0, "lock $final")
    }

    /** Every lock the engine publishes once the lines of [media] within [heardSec] are recognised. */
    private fun locksFromHeardWords(
        media: List<Triple<Long, Long, String>>,
        target: SubtitleSpeechTrack,
        references: List<com.nuvio.app.features.audiosync.asr.ReferenceSubtitle>,
        heardSec: List<IntRange>,
        timeline: SpeechTimeline,
    ): List<com.nuvio.app.features.audiosync.asr.AsrLock> {
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(timeline, onLock = { locks += it })
        engine.startSession(target, references)
        val heardLines = media.indices.filter { line -> heardSec.any { media[line].first / 1_000 in it } }
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        heardLines.forEach { line ->
            engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val random = Random(23)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        return locks.toList()
    }

    /** Runs recognition on the lines of [media] starting within [heardSec], returning the last lock. */
    private fun lockFromHeardWords(
        media: List<Triple<Long, Long, String>>,
        target: SubtitleSpeechTrack,
        english: List<Triple<Long, Long, String>>,
        bridge: com.nuvio.app.features.audiosync.asr.BridgeFit,
        heardSec: List<IntRange>,
    ): com.nuvio.app.features.audiosync.asr.AsrLock {
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(speech(media), onLock = { locks += it })
        engine.startSession(target, listOf(com.nuvio.app.features.audiosync.asr.ReferenceSubtitle("en", english, bridge)))
        val heardLines = media.indices.filter { line -> heardSec.any { media[line].first / 1_000 in it } }
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        heardLines.forEach { line ->
            engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val random = Random(13)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        return locks.last()
    }

    @Test
    fun littleHeardAudioNeverStretches() {
        // As on a phone without sampling: about 70 s heard. Even if a stretch is right, it is not
        // applied on so little evidence. Here the stretch the bridge implies is right and fits the
        // speech as well as the plain rate, so a plain-rate estimate would put most of the film's
        // lines in the wrong place: nothing is published until more is heard.
        val locks = locksAcrossFrameRates(videoMatchesEnglish = true, heardMs = 70_000L)
        assertTrue(locks.none { it.scale != 1.0 }, "stretched on little evidence: $locks")
        assertTrue(locks.isEmpty(), "guessed a rate on little evidence: $locks")
    }

    private fun lockAcrossFrameRates(
        videoMatchesEnglish: Boolean,
        heardMs: Long = Long.MAX_VALUE,
    ): com.nuvio.app.features.audiosync.asr.AsrLock =
        locksAcrossFrameRates(videoMatchesEnglish, heardMs).lastOrNull() ?: throw AssertionError("no lock")

    /** Every result the engine publishes once all heard lines are recognised and evaluated. */
    private fun locksAcrossFrameRates(
        videoMatchesEnglish: Boolean,
        heardMs: Long = Long.MAX_VALUE,
    ): List<com.nuvio.app.features.audiosync.asr.AsrLock> {
        val rate = 25.0 / 23.976
        val media = script(Random(7), 160)
        fun at25(cues: List<Triple<Long, Long, String>>) =
            cues.map { (a, b, t) -> Triple((a / rate).toLong(), (b / rate).toLong(), t) }
        val english = if (videoMatchesEnglish) media else at25(media)
        val translated = (if (videoMatchesEnglish) at25(media) else media).map { (a, b, _) -> Triple(a, b, "linie tradusa") }
        val target = SubtitleSpeechTrack.fromCues(translated)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.audiosync.asr.AsrLock>())
        val latch = java.util.concurrent.CountDownLatch(1)
        val engine = AsrSyncEngine(speech(media, heardMs), onLock = { locks += it; latch.countDown() })
        engine.startSession(target, listOf(com.nuvio.app.features.audiosync.asr.ReferenceSubtitle("en", english, bridge)))
        // Twelve lines heard near the start: far too short a span to judge a frame rate from words.
        val random = Random(8)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        media.take(12).forEachIndexed { line, (start, _, _) ->
            engine.offerSegment((start / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() })
        }
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val line = samples[0].toInt()
            val (start, end, text) = media[line]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                val t = (start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1)
                (t - segmentStartSec) to word
            }
        }
        // Let every line be recognised and evaluated before reading the results.
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < 12 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        var settled = locks.size
        do {
            Thread.sleep(1_000)
            val now = locks.size
            val stable = now == settled
            settled = now
        } while (!stable && System.currentTimeMillis() < deadline)
        engine.release()
        return locks.toList()
    }

    /** Clean speech exactly where [cues] are. */
    private fun speech(cues: List<Triple<Long, Long, String>>, heardMs: Long = Long.MAX_VALUE): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val timeline = SpeechTimeline()
        val frames = (minOf(cues.maxOf { it.second } + 30_000, heardMs) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        for ((a, b, _) in cues) {
            val from = ((a + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            val to = ((b + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            for (f in from until to.coerceAtMost(frames)) speaking[f] = true
        }
        for (f in 0 until frames) timeline.record(f, if (speaking[f]) 0.9f else 0.05f)
        return timeline
    }

    @Test
    fun bridgeRejectsUnrelatedFiles() {
        val a = script(Random(4), 80)
        val b = script(Random(5), 80)
        assertNull(SubtitleBridge.align(SubtitleSpeechTrack.fromCues(a), SubtitleSpeechTrack.fromCues(b)))
    }
}
