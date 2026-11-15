package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyledSubtitleCuesTest {

    @Test
    fun italicTagsBecomeItalicText() {
        val cues = parseMpvSubtitleText("""{\i1}Italic line{\i0}\NPlain line""")

        assertEquals("Italic line\nPlain line", cues.bottomText.text)
        val italic = cues.bottomText.spanStyles.single()
        assertEquals(FontStyle.Italic, italic.item.fontStyle)
        assertEquals(0, italic.start)
        assertEquals("Italic line".length, italic.end)
    }

    @Test
    fun topAlignedEventsRenderAtTheTop() {
        val cues = parseMpvSubtitleText("Dialogue\n{\\an8}Sign")

        assertEquals("Sign", cues.topText.text)
        assertEquals("Dialogue", cues.bottomText.text)
    }

    @Test
    fun otherOverrideTagsAreDroppedWithoutChangingTheStyle() {
        val cues = parseMpvSubtitleText("""{\bord2\blur3\an2}Plain {\b1}bold{\b0}""")

        assertEquals("Plain bold", cues.bottomText.text)
        assertTrue(cues.topText.isEmpty())
        assertEquals(FontWeight.Bold, cues.bottomText.spanStyles.single().item.fontWeight)
    }

    @Test
    fun plainTextAndBlankInputAreHandled() {
        assertEquals("Hello\nthere", parseMpvSubtitleText(" Hello\nthere ").bottomText.text)
        assertTrue(parseMpvSubtitleText("").isEmpty)
    }

    @Test
    fun alignmentTagsPlaceEventsInAllNinePositions() {
        val cues = parseMpvSubtitleText("{\\an9}Top right\n{\\an4}Middle left\nDialogue")

        assertEquals(
            listOf(
                Triple("Top right", SubtitleVerticalPosition.TOP, SubtitleHorizontalPosition.END),
                Triple("Middle left", SubtitleVerticalPosition.MIDDLE, SubtitleHorizontalPosition.START),
                Triple("Dialogue", SubtitleVerticalPosition.BOTTOM, SubtitleHorizontalPosition.CENTER),
            ),
            cues.blocks.map { Triple(it.text.text, it.vertical, it.horizontal) },
        )
    }

    @Test
    fun colorTagsUseTheAssBlueGreenRedOrderAndResetClearsThem() {
        val cues = parseMpvSubtitleText("""{\c&H0000FF&}Red{\r} plain {\1c&H00FF00&}green""")

        assertEquals("Red plain green", cues.bottomText.text)
        assertEquals(listOf(Color.Red, Color.Green), cues.bottomText.spanStyles.map { it.item.color })
    }

    @Test
    fun anEmptyColorTagResetsToTheDefaultColor() {
        val cues = parseMpvSubtitleText("""{\c&H00FFFF&}John:{\c} where are you?""")

        assertEquals("John: where are you?", cues.bottomText.text)
        val yellow = cues.bottomText.spanStyles.single()
        assertEquals(Color.Yellow, yellow.item.color)
        assertEquals("John:".length, yellow.end)
    }

    @Test
    fun colorTagsWithoutTheClosingAmpersandStillApply() {
        assertEquals(Color.Red, parseMpvSubtitleText("""{\1c&H0000FF}Red""").bottomText.spanStyles.single().item.color)
    }

    @Test
    fun fileColorsFollowTheTextOpacity() {
        assertEquals(Color.Yellow.copy(alpha = 0.5f), subtitleFileColor(Color.Yellow, textAlpha = 0.5f))
    }
}
