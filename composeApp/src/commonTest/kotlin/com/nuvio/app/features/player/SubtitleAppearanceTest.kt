package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleAppearanceTest {
    @Test
    fun oldAndInvalidSettingsKeepExistingAppearance() {
        listOf(null, "", "{", "null").forEach { assertEquals(SubtitleAppearance(), decodeSubtitleAppearance(it)) }
        assertFalse(SubtitleStyleState.DEFAULT.usesStyledOverlay)
        assertEquals(SubtitleAppearance(shadowEnabled = true), decodeSubtitleAppearance("""{"shadowEnabled":true,"future":1}"""))
    }

    @Test
    fun syncedValuesAreBoundedAndCannotReferenceFiles() {
        val style = decodeSubtitleAppearance("""{"fontId":"../../font.ttf","backgroundRadius":999,"shadowOpacity":-1,"shadowSize":999}""")
        assertEquals("default", style.fontId)
        assertEquals(24, style.backgroundRadius)
        assertEquals(0, style.shadowOpacity)
        assertEquals(8, style.shadowSize)
    }

    @Test
    fun resetKeepsSubtitleSelectionPreferences() {
        val reset = SubtitleStyleState(
            stripSdh = true, useForcedSubtitles = true, showOnlyPreferredLanguages = true,
            backgroundColor = Color.Red, appearance = SubtitleAppearance(fontId = "serif", shadowEnabled = true),
        ).resetAppearance()
        assertTrue(reset.stripSdh && reset.useForcedSubtitles && reset.showOnlyPreferredLanguages)
        assertEquals(SubtitleStyleState.DEFAULT.backgroundColor, reset.backgroundColor)
        assertEquals(SubtitleAppearance(), reset.appearance)
    }

    @Test
    fun styledAndImageTracksStayWithTheirNativeRenderer() {
        listOf("ass", "ssa", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "", "unknown").forEach {
            assertFalse(SubtitleRendering.supportsTextOverlay(it), it)
        }
        listOf("subrip", "webvtt", "mov_text", "SRT").forEach {
            assertTrue(SubtitleRendering.supportsTextOverlay(it), it)
        }
    }

    @Test
    fun fontImportRejectsTruncatedTablesAndUnsupportedContainers() {
        val truncated = byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        assertFalse(isSupportedSubtitleFont("font.ttf", truncated))
        assertFalse(isSupportedSubtitleFont("font.otf", "this is not a font".encodeToByteArray()))
        assertFalse(isSupportedSubtitleFont("font.woff", truncated))
        val overflowing = truncated + ByteArray(16) { -1 }
        assertFalse(isSupportedSubtitleFont("font.ttf", overflowing))
    }
}
