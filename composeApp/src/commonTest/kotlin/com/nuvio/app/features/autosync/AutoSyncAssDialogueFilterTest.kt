package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AutoSyncAssDialogueFilterTest {
    private fun time(ms: Long): String {
        val cs = ms / 10
        fun two(value: Long) = value.toString().padStart(2, '0')
        return "${cs / 360_000}:${two(cs / 6_000 % 60)}:${two(cs / 100 % 60)}.${two(cs % 100)}"
    }

    private fun dialogue(startMs: Long, endMs: Long, style: String, text: String, effect: String = "") =
        "Dialogue: 0,${time(startMs)},${time(endMs)},$style,,0,0,0,$effect,$text"

    private fun fansubEpisode(): String = buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine(
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, " +
                "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        )
        appendLine("Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,10,1")
        appendLine("Style: Default - Italics,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,1,0,0,100,100,0,0,1,2,1,2,10,10,10,1")
        appendLine("Style: OP Romaji,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,8,10,10,10,1")
        appendLine("Style: Signs,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,5,10,10,10,1")
        appendLine("Style: Caption,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,10,1")
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        // Opening karaoke, one event per syllable.
        for (index in 0 until 40) {
            val start = 5_000L + index * 400L
            appendLine(dialogue(start, start + 400L, "OP Romaji", "{\\k40}la$index"))
        }
        for (index in 0 until 30) {
            val start = 120_000L + index * 4_000L
            val style = if (index % 5 == 0) "Default - Italics" else "Default"
            appendLine(dialogue(start, start + 2_500L, style, "Line {\\i1}number{\\i0} $index"))
        }
        // Signs over the dialogue, in their own style and typeset in the dialogue style.
        for (index in 0 until 10) {
            val start = 121_000L + index * 9_000L
            appendLine(dialogue(start, start + 6_000L, "Signs", "{\\an5}Shop"))
            appendLine(dialogue(start + 500L, start + 3_000L, "Default", "{\\pos(320,40)}Station"))
        }
        // An unnamed style that is only ever typeset.
        for (index in 0 until 6) {
            appendLine(dialogue(200_000L + index * 1_000L, 201_000L + index * 1_000L, "Caption", "{\\move(0,0,10,10)}Note"))
        }
        appendLine(dialogue(300_000L, 302_000L, "Default", "Scrolling", effect = "Scroll up;0;100;20"))
        appendLine(dialogue(310_000L, 311_000L, "Default", "{\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}"))
    }

    @Test
    fun parserKeepsOnlyTheDialogueOfAFansubFile() {
        val cues = AutoSyncSubtitleCueParser.parse(fansubEpisode(), sourceUrl = "episode.ass")

        assertEquals(30, cues.size)
        assertTrue(cues.all { it.text.startsWith("Line number") })
    }

    @Test
    fun aFileWithTooLittleDialogueKeepsEveryEvent() {
        val body = buildString {
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            for (index in 0 until 25) {
                val start = 10_000L + index * 3_000L
                appendLine(dialogue(start, start + 2_000L, "Sign", "{\\pos(10,10)}Text $index"))
            }
        }

        assertEquals(25, AutoSyncSubtitleCueParser.parse(body, sourceUrl = "signs.ass").size)
    }

    @Test
    fun legacySsaMiddleAlignmentIsNotDialogue() {
        val body = buildString {
            appendLine("[V4 Styles]")
            appendLine("Format: Name, Fontname, Fontsize, Alignment, AlphaLevel")
            appendLine("Style: Default,Arial,20,2,0")
            appendLine("Style: Board,Arial,20,10,0")
            appendLine("[Events]")
            appendLine("Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            for (index in 0 until 25) {
                val start = 10_000L + index * 3_000L
                appendLine("Dialogue: Marked=0,${time(start)},${time(start + 2_000L)},Default,,0,0,0,,Line $index")
                appendLine("Dialogue: Marked=0,${time(start)},${time(start + 2_000L)},Board,,0,0,0,,Board $index")
            }
        }

        val cues = AutoSyncSubtitleCueParser.parse(body, sourceUrl = "legacy.ssa")

        assertEquals(25, cues.size)
        assertTrue(cues.all { it.text.startsWith("Line") })
    }

    @Test
    fun indexedTimingDropsKaraokeBurstsAndLongSigns() {
        val dialogue = (0 until 40).map { index ->
            val start = 100_000L + index * 3_000L
            SubtitleSyncCue(startTimeMs = start, endTimeMs = start + 2_000L, text = "")
        }
        val karaoke = (0 until 60).map { index ->
            val start = 10_000L + index * 150L
            SubtitleSyncCue(startTimeMs = start, endTimeMs = start + 600L, text = "")
        }
        val effects = (0 until 10).map { index ->
            val start = 50_000L + index * 2_000L
            SubtitleSyncCue(startTimeMs = start, endTimeMs = start + 80L, text = "")
        }
        val title = SubtitleSyncCue(startTimeMs = 90_000L, endTimeMs = 110_000L, text = "")

        val filtered = AutoSyncAssDialogueFilter.indexedDialogueCues(
            cues = dialogue + karaoke + effects + title,
            estimatedEndStartsMs = emptySet(),
        )

        assertEquals(dialogue, filtered)
    }

    @Test
    fun indexedTimingIgnoresEstimatedDurations() {
        val cues = (0 until 30).map { index ->
            val start = 100_000L + index * 3_000L
            SubtitleSyncCue(startTimeMs = start, endTimeMs = start + 100L, text = "")
        }

        val filtered = AutoSyncAssDialogueFilter.indexedDialogueCues(
            cues = cues,
            estimatedEndStartsMs = cues.mapTo(HashSet()) { it.startTimeMs },
        )

        assertEquals(cues, filtered)
    }
}
