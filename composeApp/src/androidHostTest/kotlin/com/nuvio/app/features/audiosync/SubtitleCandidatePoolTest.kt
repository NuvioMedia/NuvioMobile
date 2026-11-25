package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.HeardWord
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleCandidatePoolTest {
    private val durationMs = 20 * 60_000L

    @Test
    fun uncertainEarlySpeechKeepsAllCandidatesAndLaterDialogueCanRecover() {
        val spoken = cues(Random(950), durationMs)
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(cues(Random(951), durationMs)))
            .apply { mediaDurationMs = durationMs }
        repeat(11) { pool.addCandidate("decoy$it", cues(Random(960 + it), durationMs)) }
        pool.addCandidate("good", spoken.map { (a, b, t) -> Triple(a - 3_000L, b - 3_000L, t) })

        // More than the old three-minute pruning threshold, but unrelated to actual dialogue.
        // Six updates also give every file multiple opportunities to score poorly.
        val random = Random(980)
        val live = SpeechTimeline()
        val earlyFrames = (4 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        repeat(earlyFrames) { live.record(it, random.nextFloat()) }
        repeat(6) { pass ->
            live.record(0, random.nextFloat())
            assertNull(pool.update(live, null, nowMs = pass * 10_000L))
        }
        assertEquals("testing 12", pool.summary, "uncertain VAD must not prune or keep only the top four")

        // New sampled scenes replace the uncertain opening in the ten-minute scoring window.
        val full = speechFor(spoken, shiftMs = 0.0, random = Random(981))
        val end = full.knownRange()!!.last + 1
        val from = (9 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        full.snapshot(from, end).forEachIndexed { index, probability -> live.record(from + index, probability) }
        var winner: SubtitleCandidatePool.Winner? = null
        for (pass in 0 until 3) {
            live.record(0, random.nextFloat())
            winner = pool.update(live, null, nowMs = 60_000L + pass * 10_000L)
            if (winner != null) break
        }
        assertEquals("good", assertNotNull(winner, "the correct file must remain available for later evidence").key)
    }

    @Test
    fun spotOnlyScoresLaterBatchesWhenTimelineVersionStopsChanging() {
        val spoken = cues(Random(990), durationMs)
        val speech = speechFor(spoken, shiftMs = 0.0, random = Random(991))
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(cues(Random(992), durationMs)))
            .apply { mediaDurationMs = durationMs }
        repeat(6) { pool.addCandidate("decoy$it", cues(Random(1000 + it), durationMs)) }
        pool.addCandidate("goodInBatch2", spoken.map { (a, b, t) -> Triple(a - 3_000L, b - 3_000L, t) })

        // Timeline version never changes between updates, as happens in spot-only mode after all spots finish.
        assertTrue(pool.hasPendingWork(speech.version, wordCount = 0))
        assertNull(pool.update(speech, null, nowMs = 0L))
        assertTrue(pool.hasPendingWork(speech.version, wordCount = 0))
        val winner = assertNotNull(
            pool.update(speech, null, nowMs = 10_000L),
            "winner in batch 2 must be scored without waiting for timeline.version to change",
        )
        assertEquals("goodInBatch2", winner.key)
        assertFalse(pool.hasPendingWork(speech.version, wordCount = 0))
        val delayMs = winner.model.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - 3_000.0) < 80.0, "delay $delayMs")

        // When no candidate matches, pending work clears once all batches are scored.
        val unmatchedPool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(cues(Random(1020), durationMs)))
            .apply { mediaDurationMs = durationMs }
        repeat(6) { unmatchedPool.addCandidate("miss$it", cues(Random(1030 + it), durationMs)) }
        assertTrue(unmatchedPool.hasPendingWork(speech.version, wordCount = 0))
        assertNull(unmatchedPool.update(speech, null, nowMs = 0L))
        assertTrue(unmatchedPool.hasPendingWork(speech.version, wordCount = 0))
        assertNull(unmatchedPool.update(speech, null, nowMs = 10_000L))
        assertFalse(unmatchedPool.hasPendingWork(speech.version, wordCount = 0))
    }

    @Test
    fun findsTheFileThatFitsWhenTheChosenOneNeverWill() {
        val spoken = cues(Random(500), durationMs)
        val speech = speechFor(spoken, shiftMs = 0.0, random = Random(501))
        val chosen = cues(Random(502), durationMs)
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(chosen)).apply { mediaDurationMs = durationMs }
        repeat(4) { pool.addCandidate("decoy$it", cues(Random(510 + it), durationMs)) }
        assertTrue(!pool.addCandidate("copy", chosen), "a copy of the chosen file is not tested again")
        pool.addCandidate("short", cues(Random(520), 8 * 60_000L))
        pool.addCandidate("good", spoken.map { (a, b, t) -> Triple(a - 3_000L, b - 3_000L, t) })

        val (winner, atMs) = assertNotNull(replay(pool, speech), "no candidate found")
        assertEquals("good", winner.key)
        val delayMs = winner.model.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - 3_000.0) < 80.0, "delay $delayMs")
        println("found ${winner.key} after ${atMs / 1_000}s of playback (${pool.summary})")
        assertTrue(atMs <= 5 * 60_000L, "took until ${atMs / 1_000}s")
        assertTrue("ruled out" in pool.summary, pool.summary)
    }

    @Test
    fun staysQuietWhenTheChosenFileFits() {
        val spoken = cues(Random(600), durationMs)
        val speech = speechFor(spoken, shiftMs = 1_500.0, random = Random(601))
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(spoken)).apply { mediaDurationMs = durationMs }
        repeat(3) { pool.addCandidate("decoy$it", cues(Random(610 + it), durationMs)) }
        pool.addCandidate("alsoGood", spoken.map { (a, b, t) -> Triple(a + 700L, b + 700L, t) })
        val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(spoken))
        assertNull(replay(pool, speech, tracker))
        assertNotNull(tracker.model, "the chosen subtitle locks on its own")
    }

    @Test
    fun neverPicksAnUnrelatedFile() {
        repeat(3) { seed ->
            val speech = speechFor(cues(Random(700 + seed), durationMs), shiftMs = 0.0, random = Random(710 + seed))
            val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(cues(Random(720 + seed), durationMs)))
                .apply { mediaDurationMs = durationMs }
            repeat(8) { pool.addCandidate("decoy$it", cues(Random(730 + seed * 10 + it), durationMs)) }
            assertNull(replay(pool, speech), "false switch with seed $seed")
        }
    }

    @Test
    fun recognisedWordsSettleATranslatedFileAtOnce() {
        val random = Random(800)
        val english = script(random, 90)
        // The video runs 5 s after the English file; the good translation keeps its timing 4.2 s early.
        // Lines heard at sampled places several minutes apart, so the reference's rate is known.
        val words = hear(english, shiftSec = 5.0, random = random, upToLine = 90)
            .filter { it.segment < 6 || it.segment in 40..45 || it.segment >= 84 }
        val chosen = script(Random(801), 90).map { (a, b, _) -> Triple(a, b, "linie") }
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(chosen))
        pool.addReference("english", english)
        repeat(3) { pool.addCandidate("decoy$it", script(Random(810 + it), 90).map { (a, b, _) -> Triple(a, b, "linie") }) }
        pool.addCandidate("good", english.map { (a, b, _) -> Triple(a - 4_200L, b - 4_200L, "linie tradusa") })

        val winner = assertNotNull(pool.update(SpeechTimeline(), words, nowMs = 0L))
        assertEquals("good", winner.key)
        val shiftMs = winner.model.segments.single().shiftMs
        assertTrue(abs(shiftMs - 9_200.0) < 80.0, "shift $shiftMs")
    }

    @Test
    fun chosenFileThatFitsTheRecognisedReferenceIsSyncedDirectly() {
        val random = Random(820)
        val english = script(random, 90)
        val words = hear(english, shiftSec = 5.0, random = random, upToLine = 90)
            .filter { it.segment < 6 || it.segment in 40..45 || it.segment >= 84 }
        // The chosen translation keeps the English timing 4.2 s early: it maps at +9.2 s.
        val chosen = english.map { (a, b, _) -> Triple(a - 4_200L, b - 4_200L, "linie tradusa") }
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(chosen))
        pool.addReference("english", english)
        repeat(3) { pool.addCandidate("decoy$it", script(Random(830 + it), 90).map { (a, b, _) -> Triple(a, b, "linie") }) }
        val winner = assertNotNull(pool.update(SpeechTimeline(), words, nowMs = 0L))
        assertTrue(winner.chosen, "the chosen subtitle itself fits")
        val shiftMs = winner.model.segments.single().shiftMs
        assertTrue(abs(shiftMs - 9_200.0) < 80.0, "shift $shiftMs")
        assertTrue(pool.finished)
    }

    @Test
    fun chosenFileIsNeverStretchedByATenthOfAPercent() {
        val random = Random(840)
        // Quick exchanges: short lines, short pauses.
        var t = 20_000L
        val english = List(560) {
            val words = List(2 + random.nextInt(3)) { vocabulary[random.nextInt(vocabulary.size)] }
            val length = 800L + random.nextLong(700L)
            Triple(t, t + length, words.joinToString(" ")).also { t += length + 300L + random.nextLong(700L) }
        }
        val words = hear(english, shiftSec = 5.0, random = random, upToLine = 560)
            .filter { it.segment < 20 || it.segment in 270..300 || it.segment >= 540 }
        // Timing that drifts 0.1% against the recognised reference, as a scene the release adds
        // looks over a 20 minute window: too slight to tell apart from a step, so it is no rate.
        val chosen = english.map { (a, b, _) -> Triple((a / 1.001).toLong() - 4_200L, (b / 1.001).toLong() - 4_200L, "linie") }
        val pool = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(chosen))
        pool.addReference("english", english)
        pool.addCandidate("decoy", script(Random(850), 400).map { (a, b, _) -> Triple(a, b, "linie") })
        val winner = pool.update(SpeechTimeline(), words, nowMs = 0L)
        if (winner != null) assertEquals(1.0, winner.model.segments.single().scale, "${winner.model}")
    }

    @Test
    fun scoringTwelveFilesStaysCheap() {
        val spoken = cues(Random(900), durationMs)
        val speech = speechFor(spoken, shiftMs = 0.0, random = Random(901))
        fun pool() = SubtitleCandidatePool(SubtitleSpeechTrack.fromCues(cues(Random(902), durationMs))).apply {
            repeat(12) { addCandidate("decoy$it", cues(Random(910 + it), durationMs)) }
        }
        pool().update(speech, null, nowMs = 0L) // JIT warm-up
        val measured = pool()
        val started = System.nanoTime()
        measured.update(speech, null, nowMs = 0L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("scoring 12 candidates on 20 min of audio took ${elapsedMs}ms (${measured.summary})")
        assertTrue(elapsedMs < 5_000, "took ${elapsedMs}ms")
    }

    @Test
    fun regionalVariantsThatAreSeparateTranslationsGetTheirOwnKey() {
        val expected = mapOf(
            "por-BR" to listOf(
                "pt-BR", "pt_br", "PT-BR", "pob", "pb", "por-BR",
                "Portuguese (Brazil)", "Portuguese (Brazilian)", "Portuguese - Brazil", "Portuguese (BR)",
            ),
            "por" to listOf("pt", "pt-PT", "pt_PT", "por", "Portuguese", "Portuguese (Portugal)", "Portuguese (European)"),
            "spa-419" to listOf(
                "es-419", "es_419", "es-MX", "es-LA", "spl", "ea",
                "Spanish (Latin America)", "Spanish (LA)", "Spanish (Latino)",
            ),
            "spa" to listOf("es", "es-ES", "spa", "spn", "sp", "Spanish", "Spanish (Spain)", "Spanish (EU)"),
            "zho-Hant" to listOf(
                "zh-Hant", "zh-TW", "zh-HK", "zh-Hant-TW", "zht", "chi-tw",
                "Chinese (Traditional)", "Chinese (Taiwan)", "Chinese (Hong Kong)",
            ),
            "zho" to listOf(
                "zh", "zh-Hans", "zh-CN", "zh-Hans-HK", "chi", "zho", "zhs", "chi-cn",
                "Chinese", "Chinese (Simplified)",
            ),
        )
        for ((key, labels) in expected) {
            for (label in labels) assertEquals(key, SubtitleCandidatePool.languageKey(label), "\"$label\"")
        }
    }

    @Test
    fun otherRegionsStillShareTheLanguage() {
        val expected = mapOf(
            "eng" to listOf("en", "en-US", "en-GB", "eng", "English", "English (SDH)"),
            "fra" to listOf("fr", "fr-CA", "fre", "fra", "French"),
            "ron" to listOf("ro", "rum", "Romanian"),
            "deu" to listOf("de", "de-AT", "ger", "German"),
        )
        for ((key, labels) in expected) {
            for (label in labels) assertEquals(key, SubtitleCandidatePool.languageKey(label), "\"$label\"")
        }
        assertNull(SubtitleCandidatePool.languageKey(null))
        assertNull(SubtitleCandidatePool.languageKey("  "))
    }

    /**
     * Plays [full] back with a 60 s look-ahead, updating every 10 s. Like the controller, the pool is
     * only asked while the chosen subtitle's [tracker] (when given) has not locked.
     */
    private fun replay(
        pool: SubtitleCandidatePool,
        full: SpeechTimeline,
        tracker: AudioSyncTracker? = null,
    ): Pair<SubtitleCandidatePool.Winner, Long>? {
        val range = full.knownRange()!!
        val all = full.snapshot(0, range.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        val endMs = ((range.last + 1) * SpeechTimeline.FRAME_DURATION_MS).toLong()
        var positionMs = 0L
        while (positionMs <= endMs) {
            val horizon = minOf(all.size, ((positionMs + 60_000L) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            tracker?.update(live, positionMs)
            if (tracker?.model != null) return null
            pool.update(live, null, nowMs = positionMs)?.let { return it to positionMs }
            positionMs += 10_000L
        }
        return null
    }

    /** Dialogue-like cues: 0.8-4.5 s lines separated by 0.2-9 s gaps. */
    private fun cues(random: Random, durationMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = 5_000L + random.nextLong(20_000L)
        while (t < durationMs - 60_000L) {
            val length = 800L + random.nextLong(3_700L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 9_000L else 2_500L)
        }
        return cues
    }

    /** Noisy detector output for speech where [cues] land when shifted by [shiftMs]. */
    private fun speechFor(cues: List<Triple<Long, Long, String>>, shiftMs: Double, random: Random): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val frames = ((cues.maxOf { it.second } + shiftMs + 30_000) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        val latencyMs = SubtitleAudioAligner.DETECTOR_BIAS_MS
        for ((start, stop, _) in cues) {
            val from = ((start + shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            val to = ((stop + shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            for (f in from until to) speaking[f] = true
        }
        val timeline = SpeechTimeline()
        for (f in 0 until frames) {
            val p = if (speaking[f]) {
                if (random.nextFloat() < 0.25f) random.nextFloat() * 0.3f else 0.7f + random.nextFloat() * 0.3f
            } else {
                if (random.nextFloat() < 0.08f) 0.4f + random.nextFloat() * 0.5f else random.nextFloat() * 0.1f
            }
            timeline.record(f, p)
        }
        return timeline
    }

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

    private fun hear(cues: List<Triple<Long, Long, String>>, shiftSec: Double, random: Random, upToLine: Int): List<HeardWord> =
        cues.take(upToLine).flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexedNotNull { k, word ->
                if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                val t = (start + (end - start) * k / words.size) / 1_000.0 + shiftSec + random.nextDouble(-0.15, 0.15)
                HeardWord(t, word, line)
            }
        }
}
