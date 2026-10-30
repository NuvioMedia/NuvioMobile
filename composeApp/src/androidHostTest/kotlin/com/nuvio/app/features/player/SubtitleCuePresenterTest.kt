@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.app.Application
import android.graphics.Bitmap
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
        assertEquals("First caption", presenter.text)
        style = SubtitleStyleState.DEFAULT
        assertEquals(cues, presenter.refresh())
        assertEquals("", presenter.text)
        style = style.copy(appearance = SubtitleAppearance(shadowEnabled = true))
        presenter.refresh()
        presenter.present(emptyList())
        assertEquals("", presenter.text)
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
        assertEquals("Caption", presenter.text)
        assertEquals(listOf(text), presenter.present(listOf(text), canStyle = false))
        assertEquals("", presenter.text)
    }
}
