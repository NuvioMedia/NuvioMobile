package com.nuvio.app.features.player

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyledSubtitleCuesTest {

    @Test
    fun italicTagsBecomeItalicText() {
        val cues = parseMpvSubtitleText("""{\i1}Italic line{\i0}\NPlain line""")

        assertEquals("Italic line\nPlain line", cues.bottom.text)
        val italic = cues.bottom.spanStyles.single()
        assertEquals(FontStyle.Italic, italic.item.fontStyle)
        assertEquals(0, italic.start)
        assertEquals("Italic line".length, italic.end)
    }

    @Test
    fun topAlignedEventsRenderAtTheTop() {
        val cues = parseMpvSubtitleText("Dialogue\n{\\an8}Sign")

        assertEquals("Sign", cues.top.text)
        assertEquals("Dialogue", cues.bottom.text)
    }

    @Test
    fun otherOverrideTagsAreDroppedWithoutChangingTheStyle() {
        val cues = parseMpvSubtitleText("""{\bord2\blur3\an2}Plain {\b1}bold{\b0}""")

        assertEquals("Plain bold", cues.bottom.text)
        assertTrue(cues.top.isEmpty())
        assertEquals(FontWeight.Bold, cues.bottom.spanStyles.single().item.fontWeight)
    }

    @Test
    fun plainTextAndBlankInputAreHandled() {
        assertEquals("Hello\nthere", parseMpvSubtitleText(" Hello\nthere ").bottom.text)
        assertTrue(parseMpvSubtitleText("").isEmpty)
    }
}
