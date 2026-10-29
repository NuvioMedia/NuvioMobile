package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncSubtitleTextRetimerTest {
    @Test
    fun srtShiftsTimestampsAndKeepsText() {
        val body = """
            1
            00:00:01,000 --> 00:00:02,500
            <i>Hello</i>

            2
            00:00:04,000 --> 00:00:05,000
            World
        """.trimIndent()

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, shifted(body, offsetMs = 1_500L))

        assertEquals(
            """
                1
                00:00:02,500 --> 00:00:04,000
                <i>Hello</i>

                2
                00:00:05,500 --> 00:00:06,500
                World
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun webVttKeepsCueSettingsAndAddsHoursOnlyWhenNeeded() {
        val body = """
            WEBVTT

            59:58.000 --> 59:59.500 align:start line:10%
            Late line
        """.trimIndent()

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, shifted(body, offsetMs = 3_000L))

        assertEquals(
            """
                WEBVTT

                01:00:01.000 --> 01:00:02.500 align:start line:10%
                Late line
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun assRewritesOnlyStartAndEndFieldsFromFormat() {
        val body = """
            [Script Info]
            Title: Test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\an8}Top, with comma
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,note
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(1_000L, 2_500L, 1_250L, 2_750L)),
            interceptMs = 250.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            body.replace(
                "Dialogue: 0,0:00:01.00,0:00:02.50,",
                "Dialogue: 0,0:00:01.25,0:00:02.75,",
            ),
            retimed,
        )
    }

    @Test
    fun timesWithoutANearbyCueUseTheWholeFilmTransform() {
        val body = """
            1
            00:10:00,000 --> 00:10:01,000
            Only cue the matcher dropped
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(1_000L, 2_000L, 1_000L, 2_000L)),
            scale = 1.001,
            interceptMs = -400.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            """
                1
                00:10:00,200 --> 00:10:01,201
                Only cue the matcher dropped
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun retimedCueRunningIntoTheNextIsCutAtItsStart() {
        val body = """
            1
            00:00:01,000 --> 00:00:02,000
            First

            2
            00:00:02,000 --> 00:00:03,000
            Second
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(
                AutoSyncRetimedCue(1_000L, 2_000L, 1_000L, 2_600L),
                AutoSyncRetimedCue(2_000L, 3_000L, 2_400L, 3_400L),
            ),
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)!!

        assertTrue(retimed.contains("00:00:01,000 --> 00:00:02,400"))
        assertTrue(retimed.contains("00:00:02,400 --> 00:00:03,400"))
    }

    @Test
    fun formatsWithoutRewritableTimingReturnNull() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">x</p></body></tt>"""
        val delayOnly = timeline(cues = emptyList(), interceptMs = 1_000.0)

        assertNull(AutoSyncSubtitleTextRetimer.retime(ttml, delayOnly))
        assertNull(AutoSyncSubtitleTextRetimer.retime("no timing here", delayOnly))
    }

    private fun shifted(srtOrVtt: String, offsetMs: Long): AutoSyncTimelineRetimeResult =
        timeline(
            cues = AutoSyncSubtitleCueParser.parse(srtOrVtt).map { cue ->
                AutoSyncRetimedCue(
                    originalStartTimeMs = cue.startTimeMs,
                    originalEndTimeMs = cue.endTimeMs,
                    startTimeMs = cue.startTimeMs + offsetMs,
                    endTimeMs = cue.endTimeMs + offsetMs,
                )
            },
            interceptMs = offsetMs.toDouble(),
        )

    private fun timeline(
        cues: List<AutoSyncRetimedCue>,
        scale: Double = 1.0,
        interceptMs: Double = 0.0,
    ) = AutoSyncTimelineRetimeResult(
        cues = cues,
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 1.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = cues.size,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentScale = scale,
        alignmentInterceptMs = interceptMs,
    )
}
