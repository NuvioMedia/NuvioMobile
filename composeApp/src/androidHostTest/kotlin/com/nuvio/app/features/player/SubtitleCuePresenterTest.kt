@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.media3.common.text.Cue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleCuePresenterTest {
    @Test
    fun liveStyleChangesRetainPausedCuesAndClearAtCueEnd() {
        var style = SubtitleStyleState.DEFAULT
        val presenter = SubtitleCuePresenter { style }
        val cues = listOf(Cue.Builder().setText("First caption").build())
        assertEquals(cues, presenter.present(cues))
        style = style.copy(appearance = SubtitleAppearance(fontId = "serif"))
        assertTrue(presenter.refresh().isEmpty())
        assertEquals("First caption", presenter.cues.bottomText.text)
        style = SubtitleStyleState.DEFAULT
        assertEquals(cues, presenter.refresh())
        assertTrue(presenter.cues.isEmpty)
        style = style.copy(appearance = SubtitleAppearance(shadowEnabled = true))
        presenter.refresh()
        presenter.present(emptyList())
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun bitmapCuesAndStyledTracksKeepTheirNativeOutput() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(fontId = "mono")) }
        val text = Cue.Builder().setText("Caption").build()
        val bitmap = Cue.Builder().setBitmap(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
            .setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.2f).setBitmapHeight(0.1f).build()
        assertEquals(listOf(bitmap), presenter.present(listOf(text, bitmap)))
        assertEquals("Caption", presenter.cues.bottomText.text)
        assertEquals(listOf(text), presenter.present(listOf(text), canStyle = false))
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun topPositionedCuesAndItalicSpansReachTheOverlay() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val italic = SpannableString("Italic line").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val caption = Cue.Builder().setText(italic).build()
        val sign = Cue.Builder().setText("Sign").setLine(0.08f, Cue.LINE_TYPE_FRACTION).build()

        presenter.present(listOf(caption, sign))

        assertEquals("Sign", presenter.cues.topText.text)
        assertEquals("Italic line", presenter.cues.bottomText.text)
        assertEquals(FontStyle.Italic, presenter.cues.bottomText.spanStyles.single().item.fontStyle)
    }

    @Test
    fun cueColorsAndSrtAlignmentReachTheOverlay() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val colored = SpannableString("Yellow").apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val topLeft = Cue.Builder().setText(colored)
            .setLine(0.08f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0.08f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build()

        presenter.present(listOf(topLeft))

        val block = presenter.cues.blocks.single()
        assertEquals(SubtitleVerticalPosition.TOP, block.vertical)
        assertEquals(SubtitleHorizontalPosition.START, block.horizontal)
        assertEquals(Color.Yellow, block.text.spanStyles.single().item.color)
    }

    @Test
    fun lineNumberCuesUseTheTopForNonNegativeLines() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val top = Cue.Builder().setText("Top row").setLine(0f, Cue.LINE_TYPE_NUMBER).build()
        val bottom = Cue.Builder().setText("Bottom row").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()

        presenter.present(listOf(top, bottom))

        assertEquals("Top row", presenter.cues.topText.text)
        assertEquals("Bottom row", presenter.cues.bottomText.text)
    }
}
