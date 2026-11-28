package com.nuvio.app.features.autosync

import android.app.Application
import com.nuvio.app.features.player.SubtitleSyncCue
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused reliability, accuracy, and edge-case regression suite covering optimized AutoSync
 * alignment, parsers/retimers, MKV/PGS loading, forced/SDH/ASS tracks, wide recaps, gap bridging,
 * and zero-false-positive safety gates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoSyncReliabilityAndEdgeCasesTest {

    @Test
    fun activityTimelineWordBoundaryBitOperationsAreExact() {
        for (cueCount in listOf(18, 36, 60)) {
            val base = realisticMovieCues(cueCount, startMs = 40_000L, stepMinMs = 2_400L, stepSpreadMs = 3_800L, seed = 7)
            for (shiftMs in listOf(-32_500L, -32_000L, -31_500L, -500L, 0L, 500L, 31_500L, 32_000L, 32_500L)) {
                val res = AutoSyncTimelineRetimer.retime(base, shiftCues(base, -shiftMs), 1.0, 0.0, discoverAlignment = true)
                assertNotNull("Expected match for count=$cueCount shift=$shiftMs", res)
                assertTrue("Expected confident for count=$cueCount shift=$shiftMs: ${res!!.rejectReason}", res.confident)
                assertTrue("Offset error for shift=$shiftMs: ${res.alignmentInterceptMs}", abs(res.alignmentInterceptMs - shiftMs) <= 200.0)
            }
        }
    }

    @Test
    fun allFpsDriftRatiosAndCustomSpanRatioAreAccuratelyDiscovered() {
        val reference = realisticMovieCues(350, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        val ratios = listOf(25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 1.015)
        for (scale in ratios) {
            val target = scaleAndShiftCues(reference, scale, interceptMs = 7_500.0)
            val result = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, discoverAlignment = true)
            assertNotNull("No match for scale=$scale", result)
            assertTrue("Not confident for scale=$scale: ${result!!.rejectReason}", result.confident)
            assertTrue("Scale mismatch for $scale: got ${result.alignmentScale}", abs(result.alignmentScale - scale) <= 0.002)
            val g = result.groups[result.groups.size / 2]
            val errMs = abs(result.cues[g.targetStartIndex].startTimeMs - reference[g.referenceStartIndex].startTimeMs)
            assertTrue("Mid-film cue error too large for scale=$scale: ${errMs}ms", errMs <= 300L)
        }
    }

    @Test
    fun largeConstantOffsetsNear180sBoundaryAndPartialCd1Cd2AreAccurate() {
        val reference = realisticMovieCues(300, startMs = 200_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        for (shiftMs in listOf(-165_000L, -95_400L, 0L, 88_200L, 165_000L)) {
            val result = AutoSyncTimelineRetimer.retime(reference, shiftCues(reference, -shiftMs), 1.0, 0.0, discoverAlignment = true)
            assertNotNull(result)
            assertTrue(result!!.confident)
            assertEquals("delay-only-validated", result.alignmentSource)
            assertTrue(abs(result.alignmentInterceptMs - shiftMs) <= 200.0)
            assertEquals(result.alignmentInterceptMs.roundToLong(), result.cues.first().startTimeMs - result.cues.first().originalStartTimeMs)
        }

        val longRef = realisticMovieCues(450, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        val res1 = AutoSyncTimelineRetimer.retime(longRef, shiftCues(longRef.take(180), 5_000L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue("CD1 not confident: ${res1.rejectReason}", res1.confident)
        assertTrue(abs(res1.alignmentInterceptMs + 5_000.0) <= 300.0)

        val cd2Delta = longRef[270].startTimeMs - 3_000L
        val res2 = AutoSyncTimelineRetimer.retime(longRef, shiftCues(longRef.drop(270), -cd2Delta), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue("CD2 not confident: ${res2.rejectReason}", res2.confident)
        assertTrue(abs(res2.alignmentInterceptMs - cd2Delta) <= 300.0)
    }

    @Test
    fun activityRejectedUnrelatedTimelineConfirmsNoFitWithoutRunningDp() {
        val ref = realisticMovieCues(250, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 1)
        val unrelated = realisticMovieCues(240, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 999)
        var timings: AutoSyncRetimePhaseTimings? = null
        val result = AutoSyncTimelineRetimer.retime(ref, unrelated, 1.0, 0.0, discoverAlignment = true, timingObserver = { timings = it })
        assertNotNull(result)
        assertFalse(result!!.confident)
        assertTrue(AutoSyncNoFitTracker.confirmsNoFit(result, sampledReference = false))
        assertEquals("activity-rejected", timings?.path)
        assertEquals(0L, timings?.dpMs)
    }

    @Test
    fun referenceConsistencyEarlySkipMatchesFullPairwiseGraphOnAllOutlierPositions() {
        val base = realisticMovieCues(250, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        fun ref(key: String, cues: List<SubtitleSyncCue>) =
            AutoSyncReferenceConsistency.Reference(key, AutoSyncTimelineRetimer.prepareUnitActivity(cues)!!, cues.size)

        val good = (0 until 5).map { i -> ref("good-$i", jitterCues(base, seed = 10 + i)) }
        val badA = ref("bad-a", shiftCues(base, 7_000L))
        val badB = ref("bad-b", shiftCues(base, 19_000L))

        assertEquals(setOf("bad-a"), AutoSyncReferenceConsistency.findOutliers(listOf(badA) + good))
        assertEquals(setOf("bad-a"), AutoSyncReferenceConsistency.findOutliers(good.take(2) + listOf(badA) + good.drop(2)))
        assertEquals(setOf("bad-a"), AutoSyncReferenceConsistency.findOutliers(good + listOf(badA)))
        assertEquals(setOf("bad-a", "bad-b"), AutoSyncReferenceConsistency.findOutliers(listOf(badA, good[0], badB, good[1], good[2])))
        assertTrue(AutoSyncReferenceConsistency.findOutliers(good).isEmpty())
    }

    @Test
    fun parserHandlesTrickySrtVttAssMalformedSrtAndEntityEdgeCases() {
        val trickySrt = "\uFEFF" +
            "1\r\n00:00:01,5 --> 00:00:03,45\r\n<i>Hello</i>  \t  &amp;  &lt;b&gt;world&lt;/b&gt;  \r\n  a < b &nbsp; &quot;quoted&quot; \r\n   \r\n" +
            "00:00:05,123456 --> 00:00:07,000\r\n<font color=\"#fff\">No index line</font>\r\n\r\n" +
            "3\r\n00:00:08,000 --> 00:00:09,000\r\n<i>   </i>\r\n\r\n" +
            "4\r\n00:00:10,000 --> 00:00:11,000 --> 00:00:12,000\r\nMalformed\r\n"
        val srtCues = AutoSyncSubtitleCueParser.parse(trickySrt, "https://host/test.srt")
        assertEquals(2, srtCues.size)
        assertEquals(1_500L to 3_450L, srtCues[0].startTimeMs to srtCues[0].endTimeMs)
        assertEquals("Hello & <b>world</b>\na < b \"quoted\"", srtCues[0].text)
        assertEquals(5_123L to 7_000L, srtCues[1].startTimeMs to srtCues[1].endTimeMs)
        assertEquals("No index line", srtCues[1].text)

        val malformedSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            First line without trailing blank
            2
            00:00:04,500 --> 00:00:06,200	X1:10 X2:200
            Second cue right after first
            00:00:08,000 --> 00:00:10,000	align:middle
            Third cue missing both blank line and index
            4
            00:00:12,000 --> 00:00:14,000
            Line with a number on its own:
            42
            still part of cue 4!
        """.trimIndent()
        val malCues = AutoSyncSubtitleCueParser.parse(malformedSrt, "https://host/broken.srt")
        assertEquals(4, malCues.size)
        assertEquals(4_500L to 6_200L, malCues[1].startTimeMs to malCues[1].endTimeMs)
        assertEquals(8_000L to 10_000L, malCues[2].startTimeMs to malCues[2].endTimeMs)
        assertEquals("Line with a number on its own:\n42\nstill part of cue 4!", malCues[3].text)

        val trickyVtt = """
            WEBVTT - Test File

            STYLE
            ::cue { color: white; }

            NOTE
            Ignore this comment

            cue-1
            00:10.500 --> 00:12.750 align:start line:90%
            First <00:11.000><c.yellow>word</c> here

            01:02:03.040 --> 01:02:05.000
            Second line
        """.trimIndent()
        val vttCues = AutoSyncSubtitleCueParser.parse(trickyVtt, "https://host/sub.vtt")
        assertEquals(2, vttCues.size)
        assertEquals(10_500L to 12_750L, vttCues[0].startTimeMs to vttCues[0].endTimeMs)
        assertEquals("First word here", vttCues[0].text)
        assertEquals(3_723_040L to 3_725_000L, vttCues[1].startTimeMs to vttCues[1].endTimeMs)

        assertFalse(AutoSyncSubtitleCueParser.hasAssEventsHeader("1\n00:00:01,000 --> 00:00:02,000\nLook at [Events] now\n"))
        assertTrue(AutoSyncSubtitleCueParser.hasAssEventsHeader("[Script Info]\n  [events] \t\nDialogue: ..."))
    }

    @Test
    fun assTypesetTagScannerAndSwappedColumnTextRetimerAreExact() {
        val shouldMatch = listOf(
            "{\\pos(10,20)}Sign", "{\\pos (10,20)}Sign", "{\\move(0,0,10,10)}Anim",
            "{\\org(100,100)}Rot", "{\\clip(0,0,10,10)}Clip", "{\\iclip(0,0,10,10)}IClip",
            "{\\t(0,100,\\alpha&HFF&)}Fade", "{\\p1}m 0 0 l 10 10", "{\\k40}ka",
            "{\\kf25}ra", "{\\Ko10}o", "{\\frz-15}tilted", "{\\fr10}rot", "{\\fax0.5}shear", "{\\fay-0.2}shear",
        )
        val shouldNotMatch = listOf(
            "Plain dialogue line", "{\\i1}Italic{\\i0}", "{\\b1\\an8}Top bold",
            "{\\fad(200,200)}Soft fade", "{\\p0}Reset drawing", "{\\fnArial\\fs40}Styled", "Line with \\N and \\h",
        )
        shouldMatch.forEach { assertTrue("Expected match: $it", AutoSyncAssDialogueFilter.hasTypesetTag(it)) }
        shouldNotMatch.forEach { assertFalse("Expected NO match: $it", AutoSyncAssDialogueFilter.hasTypesetTag(it)) }

        val body = "[Events]\r\nFormat: Layer, End, Start, Style, Text\r\nDialogue: 0, 0:00:03.00, 0:00:01.00, Default, Hello, world\r\n"
        val timeline = AutoSyncTimelineRetimeResult(
            cues = listOf(AutoSyncRetimedCue(1_000L, 3_000L, 2_500L, 4_500L)),
            groups = emptyList(), targetCoverage = 1.0, referenceCoverage = 1.0,
            skippedTargetCues = 0, skippedReferenceCues = 0, longestTargetSkipRun = 0, averageGroupCost = 0.0,
            oneToOneGroups = 1, oneToTwoGroups = 0, twoToOneGroups = 0, oneToThreeGroups = 0,
            threeToOneGroups = 0, twoToTwoGroups = 0, confident = true, alignmentInterceptMs = 1_500.0,
        )
        assertEquals(
            "[Events]\nFormat: Layer, End, Start, Style, Text\nDialogue: 0, 0:00:04.50, 0:00:02.50, Default, Hello, world\n",
            AutoSyncSubtitleTextRetimer.retime(body, timeline),
        )
    }

    @Test
    fun sampledReferenceAlignerHandlesNormalAndSilentMiddleThirdAndRejectsWrong() {
        val full = realisticMovieCues(400, startMs = 20_000L, seed = 1515)
        val sampled = full.filterIndexed { i, _ -> i % 6 == 0 }
        for (delayMs in listOf(-24_000L, 0L, 18_500L)) {
            val res = AutoSyncSampledReferenceAligner.findDelay(sampled, shiftCues(full, -delayMs).filter { it.startTimeMs >= 0L })
            assertNotNull("Expected sampled match for delay=$delayMs", res)
            assertTrue(abs(res!!.offsetMs - delayMs) <= 250.0)
        }
        assertNull(AutoSyncSampledReferenceAligner.findDelay(sampled, realisticMovieCues(400, seed = 888)))

        // Silent middle third
        val firstStart = full.first().startTimeMs
        val span = full.last().startTimeMs - firstStart
        val outerOnly = full.filterIndexed { idx, cue ->
            val rel = (cue.startTimeMs - firstStart).toDouble() / span.toDouble()
            idx % 4 == 0 && (rel < 0.30 || rel > 0.70)
        }
        val silentMidRes = AutoSyncSampledReferenceAligner.findDelay(outerOnly, shiftCues(full, -9_350L))
        assertNotNull("Expected sparse live ref with silent middle third to lock", silentMidRes)
        assertTrue(abs(silentMidRes!!.offsetMs - 9_350.0) <= 250.0)
    }

    @Test
    fun mkvTailScanReusesTailBytesWithoutExtraRttsAndHandlesOutOfOrderCueFields() = runBlocking {
        val cues = realisticMovieCues(80, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        val mkv = buildMkv(
            tracks = listOf(MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English", events = (cues + cues[5]).reversed().map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })),
            includeSeekHead = false,
            dummyClusterBytes = 600 * 1024,
            trackPositionsBeforeCueTime = true,
            sortCuePoints = false,
        )
        var rangeReqs = 0
        serveFiles(mapOf("/tail-scan.mkv" to mkv), onRequest = { rangeReqs++ }) { server ->
            val loaded = EmbeddedSubtitleTimelineLoader.load(server.url("/tail-scan.mkv").toString(), emptyMap())
            assertNotNull(loaded)
            assertEquals(cues.map { it.startTimeMs }, loaded!!.tracks.single().cues.map { it.startTimeMs })
            assertEquals(2, rangeReqs)
        }
    }

    @Test
    fun endToEndStartupSearchFindsFpsDriftAndRejectsMistimedReferenceOutlier() = runBlocking {
        val base = realisticMovieCues(260, startMs = 30_000L, stepMinMs = 1_400L, stepSpreadMs = 3_800L, seed = 7)
        val specs = mutableListOf(
            MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "Mistimed EN", events = shiftCues(base, 9_500L).map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) }),
        )
        val good0 = jitterCues(base, seed = 40)
        specs += MkvTrackSpec(4, "S_TEXT/UTF8", "l0", "Dup 0", events = good0.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })
        for (idx in 0 until 5) {
            val c = if (idx == 0) good0 else jitterCues(base, seed = 40 + idx)
            specs += MkvTrackSpec((idx + 5).toLong(), "S_TEXT/UTF8", "l$idx", "Track $idx", events = c.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })
        }
        val mkv = buildMkv(specs, dummyClusterBytes = 600 * 1024)
        val scale = 25.0 / 23.976
        val files = linkedMapOf<String, ByteArray>("/outlier-test.mkv" to mkv, "/fps.srt" to buildSrt(scaleAndShiftCues(base, scale, 0.0)).toByteArray())
        for (i in 0 until 4) {
            files["/wrong-$i.srt"] = buildSrt(realisticMovieCues(250, seed = 600 + i)).toByteArray()
        }
        serveFiles(files) { server ->
            val wrongCandidates = (0 until 4).map { AutoSyncSubtitleCandidate(server.url("/wrong-$it.srt").toString(), "en", "Wrong $it") }
            val fpsUrl = server.url("/fps.srt").toString()
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = server.url("/outlier-test.mkv").toString(),
                selectedSubtitleUrl = wrongCandidates.first().url,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
                alternativeSubtitles = wrongCandidates + AutoSyncSubtitleCandidate(fpsUrl, "en", "Good FPS"),
            )
            assertNotNull(resolved)
            assertEquals(fpsUrl, resolved!!.subtitleUrl)
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentScale - scale) <= 0.002)
            assertTrue("Synced to mistimed outlier!", abs(resolved.timeline.alignmentInterceptMs) <= 600.0)
        }
    }

    @Test
    fun pgsAnomaliesAndSingleFrameCueDurationsPairWithoutPhaseInversion() = runBlocking {
        // 1. Back-to-back without clears + 80ms multi-object update
        val dialogue = realisticMovieCues(300, startMs = 15_000L, seed = 101)
        val pgsEvents = buildList {
            for (i in dialogue.indices) {
                val cue = dialogue[i]
                val backToBack = (i % 12 == 5) && i + 1 < dialogue.size && (dialogue[i + 1].startTimeMs - cue.startTimeMs) in 1_000L..5_500L
                add(cue.startTimeMs to 0L)
                if (i % 20 == 9 && cue.endTimeMs - cue.startTimeMs >= 1_200L) add((cue.startTimeMs + 80L) to 0L)
                if (!backToBack) add(cue.endTimeMs to 0L)
            }
        }
        val mkv1 = buildMkv(listOf(MkvTrackSpec(3, "S_HDMV/PGS", "eng", "English PGS", events = pgsEvents)))
        val shift1 = 3_420L
        serveMkvAndSrt(mkv1, "/pgs-anomalies.mkv", buildSrt(shiftCues(dialogue, -shift1)), "/pgs-ext.srt") { mkvUrl, srtUrl ->
            val pgsTrack = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())!!.tracks.single()
            val matchedStarts = dialogue.takeLast(50).count { exp -> pgsTrack.cues.any { abs(it.startTimeMs - exp.startTimeMs) <= 150L } }
            assertTrue("PGS phase inverted: $matchedStarts/50", matchedStarts >= 42)
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - shift1) <= 50.0)
        }

        // 2. Bogus 1-frame 41 ms CueDuration on every PGS event
        val dialogue2 = realisticMovieCues(220, startMs = 20_000L, seed = 202)
        val pgs41 = dialogue2.flatMap { listOf(it.startTimeMs to 41L, it.endTimeMs to 41L) }
        val mkv2 = buildMkv(listOf(MkvTrackSpec(3, "S_HDMV/PGS", "eng", "English PGS", events = pgs41)))
        val shift2 = -2_185L
        serveMkvAndSrt(mkv2, "/pgs-41ms.mkv", buildSrt(shiftCues(dialogue2, -shift2)), "/pgs-41ms.srt") { mkvUrl, srtUrl ->
            val cues = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())!!.tracks.single().cues
            assertEquals(dialogue2.size, cues.size)
            assertTrue(cues[10].endTimeMs - cues[10].startTimeMs >= 800L)
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - shift2) <= 30.0)
        }
    }

    @Test
    fun forcedPgsAndUnflaggedForcedEmbeddedTracksLockForcedExternals() = runBlocking {
        // 1. Forced PGS track alongside full SRT peer
        val full1 = realisticMovieCues(1_200, startMs = 15_000L, seed = 303)
        val forced1 = (0 until 35).map { full1[it * 33 + 10] }
        val mkv1 = buildMkv(
            listOf(
                MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English Full", events = full1.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) }),
                MkvTrackSpec(4, "S_HDMV/PGS", "eng", "English Forced", forced = true, events = forced1.flatMap { listOf(it.startTimeMs to 0L, it.endTimeMs to 0L) }),
            ),
        )
        serveMkvAndSrt(mkv1, "/forced-pgs.mkv", buildSrt(shiftCues(forced1, -4_320L)), "/forced-ext.srt") { mkvUrl, srtUrl ->
            val forcedTrack = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())!!.tracks.first { (it.selectionFlags and AutoSyncTrackFlags.SELECTION_FLAG_FORCED) != 0 }
            assertEquals(forced1.size, forcedTrack.cues.size)
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - 4_320.0) <= 30.0)
        }

        // 2. Unflagged forced track (FlagForced=0, no "forced" in name)
        val full2 = realisticMovieCues(1_100, startMs = 18_000L, seed = 404)
        val unflagged = (0 until 40).map { full2[it * 26 + 5] }
        val mkv2 = buildMkv(
            listOf(
                MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English", events = full2.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) }),
                MkvTrackSpec(4, "S_TEXT/UTF8", "eng", "English 2", forced = false, events = unflagged.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) }),
            ),
        )
        serveMkvAndSrt(mkv2, "/unflagged-forced.mkv", buildSrt(shiftCues(unflagged, 6_150L)), "/unflagged-ext.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs + 6_150.0) <= 30.0)
        }
    }

    @Test
    fun forcedExternalLocksDirectlyAgainstFullDialogueReferenceAndHasZeroFalsePositives() {
        val fullRef = realisticMovieCues(1_500, startMs = 25_000L, seed = 505)

        // Uniform 32-cue forced delay
        val resDelay = AutoSyncTimelineRetimer.retime(fullRef, shiftCues((0 until 32).map { fullRef[it * 45 + 12] }, -12_480L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resDelay.confident)
        assertTrue(abs(resDelay.alignmentInterceptMs - 12_480.0) <= 35.0)

        // Clustered 18-cue forced with >350-cue gaps (delay + FPS drift)
        val clustered = listOf(15, 22, 31, 45, 58, 70, 450, 462, 478, 495, 510, 525, 950, 968, 985, 1_360, 1_390, 1_420).map { fullRef[it] }
        val resClustered = AutoSyncTimelineRetimer.retime(fullRef, shiftCues(clustered, -19_640L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resClustered.confident)
        assertEquals("sparse-target-delay", resClustered.alignmentSource)
        assertTrue(abs(resClustered.alignmentInterceptMs - 19_640.0) <= 35.0)

        val scale = 25.0 / 23.976
        val resFps = AutoSyncTimelineRetimer.retime(fullRef, scaleAndShiftCues(clustered, scale, 3_200.0), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resFps.confident)
        assertEquals("sparse-target-affine", resFps.alignmentSource)
        assertTrue(abs(resFps.alignmentScale - scale) <= 0.0005)
        val midIdx = clustered.size / 2
        assertTrue(abs(resFps.cues[midIdx].startTimeMs - clustered[midIdx].startTimeMs) <= 50L)

        // Small 12-cue forced delay
        val res12 = AutoSyncTimelineRetimer.retime(fullRef, shiftCues((0 until 12).map { fullRef[it * 120 + 20] }, -8_750L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(res12.confident)
        assertTrue(abs(res12.alignmentInterceptMs - 8_750.0) <= 35.0)

        // 100 random sparse timelines -> 0 false positives
        val denseRef = realisticMovieCues(1_800, startMs = 10_000L, seed = 606)
        val refActivity = AutoSyncTimelineRetimer.prepareUnitActivity(denseRef)!!
        val refSpan = denseRef.last().startTimeMs - denseRef.first().startTimeMs
        val sizes = intArrayOf(8, 10, 12, 16, 25, 45, 80)
        var falsePositives = 0
        for (trial in 0 until 100) {
            val rng = Random(7_000 + trial)
            val starts = LongArray(sizes[trial % sizes.size]) { 10_000L + rng.nextLong(refSpan) }.apply { sort() }
            var prevEnd = 0L
            val randomSparse = starts.map { sRaw ->
                val s = maxOf(sRaw, prevEnd + 500L)
                val dur = 1_000L + rng.nextLong(2_500L)
                prevEnd = s + dur
                SubtitleSyncCue(s, prevEnd, "Unrelated $trial")
            }
            if (AutoSyncTimelineRetimer.retime(denseRef, randomSparse, 1.0, 0.0, discoverAlignment = true, preparedReferenceActivity = refActivity)?.confident == true) {
                falsePositives++
            }
        }
        assertEquals(0, falsePositives)
    }

    @Test
    fun lowDialogueMovieBelowTwoCuesPerMinuteLocksEndToEnd() = runBlocking {
        val quietCues = realisticMovieCues(110, startMs = 60_000L, stepMinMs = 30_000L, stepSpreadMs = 45_000L, seed = 707)
        val density = quietCues.size / ((quietCues.last().endTimeMs - quietCues.first().startTimeMs) / 60_000.0)
        assertTrue("Expected density < 2.0 cues/min, got $density", density in 0.5..1.95)
        val mkv = buildMkv(listOf(MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English", events = quietCues.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })))
        serveMkvAndSrt(mkv, "/quiet-film.mkv", buildSrt(shiftCues(quietCues, -5_640L)), "/quiet-ext.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - 5_640.0) <= 30.0)
        }
    }

    @Test
    fun externalSdhSubtitleLocksAgainstCleanAndSdhReferencesPreservingAllSdhCues() = runBlocking {
        val dialogue = realisticMovieCues(400, startMs = 20_000L, seed = 808)
        val trueShiftMs = 2_875L
        val sdhSamples = listOf("[door slams]", "(sighing heavily)", "♪ dramatic music playing ♪", "- [coughing]\n- [panting]", "♫ ♪")
        val externalAll = buildList {
            for (i in dialogue.indices) {
                val cue = dialogue[i]
                add(SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, if (i % 15 == 0) "(whispering) ${cue.text}" else cue.text))
                if (i % 5 == 0 || i % 5 == 2) {
                    val gapMid = cue.endTimeMs + 300L
                    val nextStart = dialogue.getOrNull(i + 1)?.startTimeMs ?: (cue.endTimeMs + 3_000L)
                    if (nextStart - gapMid >= 900L) {
                        add(SubtitleSyncCue(gapMid - trueShiftMs, gapMid + 700L - trueShiftMs, sdhSamples[i % sdhSamples.size]))
                    }
                }
            }
        }
        assertEquals(dialogue.size, AutoSyncSdhCueFilter.dialogueCues(externalAll).size)

        val sdhSrtBody = buildSrt(externalAll)
        val cleanMkv = buildMkv(listOf(MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English", events = dialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })))
        serveMkvAndSrt(cleanMkv, "/sdh-vs-clean.mkv", sdhSrtBody, "/ext-sdh.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0)
            val retimedText = AutoSyncSubtitleTextRetimer.retime(resolved.subtitleBody!!, resolved.timeline)!!
            assertTrue(retimedText.contains("[door slams]") && retimedText.contains("♪ dramatic music playing ♪"))
            assertEquals(externalAll.size, AutoSyncSubtitleCueParser.parse(retimedText, "file.srt").size)
        }

        val sdhMkv = buildMkv(listOf(MkvTrackSpec(3, "S_TEXT/UTF8", "eng", "English SDH", events = externalAll.map { (it.startTimeMs + trueShiftMs) to (it.endTimeMs - it.startTimeMs) })))
        serveMkvAndSrt(sdhMkv, "/sdh-vs-sdh.mkv", sdhSrtBody, "/ext-sdh-2.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, srtUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0)
        }
    }

    @Test
    fun unbracketedExtraCuesAndLargeReferenceGapsLockAccurately() {
        // 1. Symmetric high-reference-coverage gate with 40 recap + unbracketed sign cues
        val ref = realisticMovieCues(230, startMs = 45_000L, seed = 909)
        val shiftMs = 1_750L
        val targetWithExtras = buildList {
            for (i in 0 until 40) add(SubtitleSyncCue(2_000L + i * 950L, 2_700L + i * 950L, "Previously line $i"))
            for (i in ref.indices) {
                val cue = ref[i]
                add(SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text))
                if (i % 9 == 4 && i + 1 < ref.size && ref[i + 1].startTimeMs - (cue.endTimeMs + 250L) >= 900L) {
                    add(SubtitleSyncCue(cue.endTimeMs + 250L - shiftMs, cue.endTimeMs + 900L - shiftMs, "Unbracketed sign"))
                }
            }
        }
        val resExtra = AutoSyncTimelineRetimer.retime(ref, AutoSyncTimelineRetimer.normalizeExternalTimeline(targetWithExtras), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resExtra.confident)
        assertTrue(resExtra.targetCoverage < 0.90 && resExtra.referenceCoverage >= 0.90)
        assertTrue(abs(resExtra.alignmentInterceptMs - shiftMs) <= 40.0)

        // 2. 140-cue reference gap bridged by banded DP (delay + FPS)
        val fullRef = realisticMovieCues(400, startMs = 20_000L, seed = 1010)
        val kept = fullRef.take(130) + fullRef.drop(270)
        val resGapDelay = AutoSyncTimelineRetimer.retime(fullRef, shiftCues(kept, -5_430L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resGapDelay.confident)
        assertTrue(abs(resGapDelay.alignmentInterceptMs - 5_430.0) <= 30.0)

        val scale = 25.0 / 23.976
        val resGapFps = AutoSyncTimelineRetimer.retime(fullRef, scaleAndShiftCues(kept, scale, 2_000.0), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resGapFps.confident)
        assertTrue(abs(resGapFps.alignmentScale - scale) <= 0.001)
        assertTrue(abs(resGapFps.cues[135].startTimeMs - kept[135].startTimeMs) <= 100L)
    }

    @Test
    fun sub100msPrecisionMissingCueDuration24FpsDriftAndNonStandardAffineRefine() {
        // 1. Missing MKV CueDuration + sub-15ms refinement
        val base = realisticMovieCues(300, startMs = 20_000L, seed = 1111)
        val estRef = base.mapIndexed { idx, cue ->
            val next = base.getOrNull(idx + 1)?.startTimeMs ?: (cue.startTimeMs + 4_000L)
            SubtitleSyncCue(cue.startTimeMs, minOf(next, cue.startTimeMs + 4_000L).coerceAtLeast(cue.startTimeMs + 100L), "")
        }
        val estStarts = estRef.mapTo(hashSetOf()) { it.startTimeMs }
        for (shift in listOf(47L, 1_247L, -3_863L)) {
            val res = AutoSyncTimelineRetimer.retime(estRef, shiftCues(base, -shift), 1.0, 0.0, discoverAlignment = true, referenceEstimatedEndStartsMs = estStarts)!!
            assertTrue(res.confident)
            assertEquals("delay-only-validated", res.alignmentSource)
            assertTrue(abs(res.alignmentInterceptMs - shift) <= 15.0)
        }

        // 2. 24 / 23.976 FPS drift preserved over 2-hour movie even with SDH flag
        val twoHourRef = realisticMovieCues(1_500, startMs = 20_000L, stepMinMs = 2_400L, stepSpreadMs = 4_800L, seed = 1212)
        val fps24 = 24.0 / 23.976
        val fps24Target = scaleAndShiftCues(twoHourRef, fps24, 1_500.0)
        for (sdh in listOf(false, true)) {
            val res = AutoSyncTimelineRetimer.retime(twoHourRef, fps24Target, 1.0, 0.0, discoverAlignment = true, allowAmbiguousDelayOnlyMargin = sdh)!!
            assertTrue(res.confident)
            assertEquals("activity-correlation", res.alignmentSource)
            assertTrue(abs(res.alignmentScale - fps24) <= 0.0003)
            assertTrue(abs(res.cues.last().startTimeMs - twoHourRef.last().startTimeMs) <= 100L)
        }

        // 3. Non-standard 1.0038 affine drift + extra intro/outro credits
        val core = realisticMovieCues(400, startMs = 90_000L, seed = 1313)
        val refCredits = core + (0 until 20).map { i ->
            val s = core.last().endTimeMs + 5_000L + i * 2_800L
            SubtitleSyncCue(s, s + 1_500L, "Credit $i")
        }
        val tgtIntro = (0 until 20).map { i -> SubtitleSyncCue(2_000L + i * 2_500L, 3_200L + i * 2_500L, "Intro $i") } +
            scaleAndShiftCues(core, 1.0038, 4_500.0)
        val resAff = AutoSyncTimelineRetimer.retime(refCredits, tgtIntro, 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resAff.confident)
        assertTrue(abs(resAff.alignmentScale - 1.0038) <= 0.0006)
        assertTrue(abs(resAff.cues[20 + core.size / 2].startTimeMs - core[core.size / 2].startTimeMs) <= 150L)
    }

    @Test
    fun wideRecapOffsetsAndWideFpsDriftLockViaTwoTierFallback() {
        val ref = realisticMovieCues(600, startMs = 450_000L, stepMinMs = 2_000L, stepSpreadMs = 3_500L, seed = 1414)
        val resPos = AutoSyncTimelineRetimer.retime(ref, shiftCues(ref, -360_000L), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resPos.confident)
        assertTrue(abs(resPos.alignmentInterceptMs - 360_000.0) <= 30.0)

        val refNoLead = realisticMovieCues(600, startMs = 15_000L, stepMinMs = 2_000L, stepSpreadMs = 3_500L, seed = 1415)
        val targetNeg = (0 until 45).map { i -> SubtitleSyncCue(5_000L + i * 8_500L, 7_200L + i * 8_500L, "Recap $i") } +
            shiftCues(refNoLead, 420_000L)
        val resNeg = AutoSyncTimelineRetimer.retime(refNoLead, targetNeg, 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resNeg.confident)
        assertTrue(abs(resNeg.alignmentInterceptMs + 420_000.0) <= 40.0)

        val refFps = realisticMovieCues(500, startMs = 420_000L, stepMinMs = 2_000L, stepSpreadMs = 3_500L, seed = 1818)
        val scale = 25.0 / 23.976
        val resWideFps = AutoSyncTimelineRetimer.retime(refFps, scaleAndShiftCues(refFps, scale, 360_000.0), 1.0, 0.0, discoverAlignment = true)!!
        assertTrue(resWideFps.confident)
        assertTrue(abs(resWideFps.alignmentScale - scale) <= 0.001)
        assertTrue(abs(resWideFps.alignmentInterceptMs - 360_000.0) <= 400.0)
    }

    @Test
    fun negativeRegressionGatesRejectMidFilmCutsUnrelatedAndPeriodicCadences() {
        val ref = realisticMovieCues(500, startMs = 30_000L, seed = 1616)
        val cutTarget = ref.mapIndexed { i, c ->
            val s = if (i < 250) 2_000L else 14_000L
            SubtitleSyncCue(c.startTimeMs - s, c.endTimeMs - s, c.text)
        }
        assertFalse(AutoSyncTimelineRetimer.retime(ref, cutTarget, 1.0, 0.0, discoverAlignment = true)?.confident == true)
        assertFalse(AutoSyncTimelineRetimer.retime(ref, realisticMovieCues(480, startMs = 30_000L, seed = 9999), 1.0, 0.0, discoverAlignment = true)?.confident == true)

        val periodic = (0 until 120).map { i -> SubtitleSyncCue(20_000L + i * 5_000L, 22_000L + i * 5_000L, "Line $i") }
        assertFalse(AutoSyncTimelineRetimer.retime(periodic, shiftCues(periodic, 10_000L), 1.0, 0.0, discoverAlignment = true)?.confident == true)
        assertFalse(AutoSyncTimelineRetimer.retime(periodic, shiftCues(periodic.subList(10, 110), 10_000L), 1.0, 0.0, discoverAlignment = true)?.confident == true)
    }

    @Test
    fun assFansubWithFontsKaraokeSignsAndSdhLocksAndPreservesExactSignAndKaraokeTiming() = runBlocking {
        val dialogue = realisticMovieCues(260, startMs = 25_000L, seed = 1717)
        val trueShiftMs = 3_125L
        fun assTime(ms: Long): String {
            val cs = (ms + 5L) / 10L
            return "%d:%02d:%02d.%02d".format(cs / 360_000, (cs / 6_000) % 60, (cs / 100) % 60, cs % 100)
        }
        val assBody = buildString {
            appendLine("[Script Info]\nScriptType: v4.00+\n")
            appendLine("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, Alignment")
            appendLine("Style: Default,Arial,48,2\nStyle: OP Karaoke,Arial,40,8\nStyle: Signs,Arial,40,5\n")
            appendLine("[Fonts]\nfontname: custom_0.ttf")
            for (i in 0 until 1_500) appendLine(if (i % 50 == 0) "[ABC123UUENCODEDFONTDATA$i]" else "M9TATFONTDATA$i")
            appendLine("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            for (i in 0 until 60) {
                val s = 1_000L + i * 250L
                appendLine("Dialogue: 0,${assTime(s)},${assTime(s + 240L)},OP Karaoke,,0,0,0,,{\\k24}sy$i")
            }
            for (i in dialogue.indices) {
                val extS = dialogue[i].startTimeMs - trueShiftMs
                val extE = dialogue[i].endTimeMs - trueShiftMs
                val txt = if (i == 5) """Path C:\pos(1,2) in spoken text""" else dialogue[i].text
                appendLine("Dialogue: 0,${assTime(extS)},${assTime(extE)},Default,,0,0,0,,$txt")
                if (i == 10) {
                    appendLine("Dialogue: 0,${assTime(extS + 300L)},${assTime(extS + 2_300L)},Signs,,0,0,0,,{\\POS(320,40)}Station Sign")
                    appendLine("Dialogue: 0,${assTime(extS + 150L)},${assTime(extS + 300L)},OP Karaoke,,0,0,0,,{\\k15}la")
                    appendLine("Dialogue: 0,${assTime(extS + 300L)},${assTime(extS + 450L)},OP Karaoke,,0,0,0,,{\\k15}ti")
                }
                if (i % 6 == 0 && i + 1 < dialogue.size && (dialogue[i + 1].startTimeMs - trueShiftMs) - (extE + 250L) >= 900L) {
                    appendLine("Dialogue: 0,${assTime(extE + 250L)},${assTime(extE + 900L)},Default,,0,0,0,,[door slams]")
                }
            }
        }
        val mkv = buildMkv(listOf(MkvTrackSpec(3, "S_TEXT/ASS", "eng", "English ASS", events = dialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) })))
        serveMkvAndSrt(mkv, "/anime-ass.mkv", assBody, "/fansub.ass") { mkvUrl, assUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(mkvUrl, assUrl, emptyMap(), preferredLanguage = "en")!!
            assertTrue(resolved.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 25.0)

            val retimedAss = AutoSyncSubtitleTextRetimer.retime(resolved.subtitleBody!!, resolved.timeline)!!
            assertTrue(retimedAss.contains("[ABC123UUENCODEDFONTDATA0]"))
            assertTrue(retimedAss.contains("""C:\pos(1,2)"""))
            fun parseAssStartMs(marker: String): Long {
                val ts = retimedAss.lineSequence().first { it.contains(marker) }.substringAfter("Dialogue:").split(',')[1].trim()
                val p = ts.split(':', '.')
                return (p[0].toLong() * 3600 + p[1].toLong() * 60 + p[2].toLong()) * 1000 + p[3].toLong() * 10
            }
            val ref10 = dialogue[10].startTimeMs
            assertTrue(abs(parseAssStartMs("Station Sign") - (ref10 + 300L)) <= 30L)
            assertTrue(abs(parseAssStartMs("{\\k15}la") - (ref10 + 150L)) <= 30L)
            assertTrue(abs(parseAssStartMs("{\\k15}ti") - (ref10 + 300L)) <= 30L)
        }
    }

    // ---- Shared test fixtures ----

    private fun shiftCues(cues: List<SubtitleSyncCue>, deltaMs: Long): List<SubtitleSyncCue> =
        cues.map { SubtitleSyncCue(it.startTimeMs + deltaMs, it.endTimeMs + deltaMs, it.text) }

    private fun scaleAndShiftCues(cues: List<SubtitleSyncCue>, scale: Double, interceptMs: Double): List<SubtitleSyncCue> =
        cues.mapNotNull { cue ->
            val s = ((cue.startTimeMs - interceptMs) / scale).roundToLong()
            val e = ((cue.endTimeMs - interceptMs) / scale).roundToLong()
            if (s >= 0L) SubtitleSyncCue(s, e.coerceAtLeast(s + 100L), cue.text) else null
        }

    private fun jitterCues(cues: List<SubtitleSyncCue>, seed: Int): List<SubtitleSyncCue> {
        val rng = Random(seed)
        return cues.map { cue ->
            val s = (cue.startTimeMs + rng.nextInt(-150, 151)).coerceAtLeast(0L)
            val d = (cue.endTimeMs - cue.startTimeMs + rng.nextInt(-250, 251)).coerceAtLeast(600L)
            SubtitleSyncCue(s, s + d, cue.text)
        }.sortedBy { it.startTimeMs }
    }

    private fun realisticMovieCues(
        count: Int,
        startMs: Long = 15_000L,
        stepMinMs: Long = 1_800L,
        stepSpreadMs: Long = 4_400L,
        seed: Int = 42,
    ): List<SubtitleSyncCue> {
        val rng = Random(seed)
        var t = startMs
        return List(count) { i ->
            val dur = 900L + rng.nextLong(2_600L)
            val cue = SubtitleSyncCue(t, t + dur, "Dialogue line $i")
            t += dur + stepMinMs + rng.nextLong(stepSpreadMs) + if (i % 17 == 0) 6_000L else 0L
            cue
        }
    }

    private fun buildSrt(cues: List<SubtitleSyncCue>): String = buildString {
        cues.forEachIndexed { idx, cue ->
            fun fmt(ms: Long) = String.format("%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1_000) % 60, ms % 1_000)
            append(idx + 1).append('\n').append(fmt(cue.startTimeMs)).append(" --> ").append(fmt(cue.endTimeMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private data class MkvTrackSpec(
        val trackNumber: Long,
        val codecId: String,
        val language: String,
        val name: String,
        val forced: Boolean = false,
        val events: List<Pair<Long, Long>>,
    )

    private fun buildMkv(
        tracks: List<MkvTrackSpec>,
        includeSeekHead: Boolean = true,
        dummyClusterBytes: Int = 0,
        trackPositionsBeforeCueTime: Boolean = false,
        sortCuePoints: Boolean = true,
    ): ByteArray {
        val ebml = ebmlElement(0x1A45DFA3L, ebmlElement(0x4282L, "matroska".toByteArray()))
        val info = ebmlElement(0x1549A966L, ebmlUintElement(0x2AD7B1L, 1_000_000L))
        val tracksPayload = ByteArrayOutputStream().apply {
            for (spec in tracks) {
                val entry = ByteArrayOutputStream().apply {
                    write(ebmlUintElement(0xD7L, spec.trackNumber))
                    write(ebmlUintElement(0x83L, 17L))
                    write(ebmlElement(0x86L, spec.codecId.toByteArray()))
                    write(ebmlElement(0x22B59CL, spec.language.toByteArray()))
                    write(ebmlElement(0x536EL, spec.name.toByteArray()))
                    if (spec.forced) write(ebmlUintElement(0x55AAL, 1L))
                }.toByteArray()
                write(ebmlElement(0xAEL, entry))
            }
        }.toByteArray()
        val tracksEl = ebmlElement(0x1654AE6BL, tracksPayload)
        val filler = if (dummyClusterBytes > 0) ebmlElement(0x1F43B675L, ByteArray(dummyClusterBytes)) else ebmlElement(0xECL, ByteArray(1_024))

        val points = tracks.flatMap { s -> s.events.map { Triple(it.first, s.trackNumber, it.second) } }
            .let { if (sortCuePoints) it.sortedBy { p -> p.first } else it }
        val cuesPayload = ByteArrayOutputStream().apply {
            for ((timeMs, trackNum, durMs) in points) {
                val pos = ByteArrayOutputStream().apply {
                    write(ebmlUintElement(0xF7L, trackNum))
                    write(ebmlUintElement(0xF1L, 0L))
                    if (durMs > 0L) write(ebmlUintElement(0xB2L, durMs))
                }.toByteArray()
                val pt = ByteArrayOutputStream().apply {
                    if (trackPositionsBeforeCueTime) {
                        write(ebmlElement(0xB7L, pos)); write(ebmlUintElement(0xB3L, timeMs))
                    } else {
                        write(ebmlUintElement(0xB3L, timeMs)); write(ebmlElement(0xB7L, pos))
                    }
                }.toByteArray()
                write(ebmlElement(0xBBL, pt))
            }
        }.toByteArray()
        val cuesEl = ebmlElement(0x1C53BB6BL, cuesPayload)

        val segBody = ByteArrayOutputStream().apply {
            if (includeSeekHead) {
                fun makeSeekHead(infoPos: Long, tracksPos: Long, cuesPos: Long): ByteArray {
                    fun seek(id: Long, p: Long) = ebmlElement(0x4DBBL, ebmlElement(0x53ABL, encodeId(id)) + ebmlUintElement(0x53ACL, p))
                    return ebmlElement(0x114D9B74L, seek(0x1549A966L, infoPos) + seek(0x1654AE6BL, tracksPos) + seek(0x1C53BB6BL, cuesPos))
                }
                var seekHead = makeSeekHead(0L, 0L, 0L)
                repeat(4) {
                    val iPos = seekHead.size.toLong()
                    val tPos = iPos + info.size
                    seekHead = makeSeekHead(iPos, tPos, tPos + tracksEl.size + filler.size)
                }
                write(seekHead)
            }
            write(info); write(tracksEl); write(filler); write(cuesEl)
        }.toByteArray()
        return ebml + ebmlElement(0x18538067L, segBody)
    }

    private suspend fun serveMkvAndSrt(
        mkv: ByteArray,
        mkvPath: String,
        srtBody: String,
        srtPath: String,
        block: suspend (mkvUrl: String, srtUrl: String) -> Unit,
    ) = serveFiles(mapOf(mkvPath to mkv, srtPath to srtBody.toByteArray())) { server ->
        block(server.url(mkvPath).toString(), server.url(srtPath).toString())
    }

    private suspend fun serveFiles(
        files: Map<String, ByteArray>,
        onRequest: (() -> Unit)? = null,
        block: suspend (MockWebServer) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    onRequest?.invoke()
                    val data = files[request.path.orEmpty()] ?: return MockResponse().setResponseCode(404)
                    val range = request.getHeader("Range")
                    if (range != null && range.startsWith("bytes=")) {
                        val parts = range.removePrefix("bytes=").split("-")
                        val start = parts[0].toLong()
                        val end = parts[1].toLong().coerceAtMost(data.size.toLong() - 1L)
                        return MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                            .setBody(Buffer().write(data.copyOfRange(start.toInt(), end.toInt() + 1)))
                    }
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                }
            }
            server.start()
            block(server)
        }
    }

    private fun ebmlElement(id: Long, payload: ByteArray): ByteArray =
        encodeId(id) + encodeVintSize(payload.size.toLong()) + payload

    private fun ebmlUintElement(id: Long, value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (len * 8))) len++
        return ebmlElement(id, ByteArray(len) { i -> ((value ushr ((len - 1 - i) * 8)) and 0xFFL).toByte() })
    }

    private fun encodeId(id: Long): ByteArray {
        var len = 1
        while (len < 8 && id >= (1L shl (len * 8))) len++
        return ByteArray(len) { i -> ((id ushr ((len - 1 - i) * 8)) and 0xFFL).toByte() }
    }

    private fun encodeVintSize(size: Long): ByteArray {
        val len = when {
            size < (1L shl 7) - 1 -> 1
            size < (1L shl 14) - 1 -> 2
            size < (1L shl 21) - 1 -> 3
            else -> 4
        }
        val out = ByteArray(len) { i -> ((size ushr ((len - 1 - i) * 8)) and 0xFFL).toByte() }
        out[0] = (out[0].toInt() or (1 shl (8 - len))).toByte()
        return out
    }
}
