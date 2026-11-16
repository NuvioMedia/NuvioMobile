package com.nuvio.app.features.player

import android.app.Application
import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleFontImportTest {
    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun importedFontSurvivesReloadAndDuplicateImport() = runTest {
        PlayerSettingsStorage.initialize(RuntimeEnvironment.getApplication())
        val bytes = Res.readBytes("font/jetbrains_sans_regular.ttf")
        val font = SubtitleFonts.import("Custom.ttf", bytes)
        try {
            assertEquals(font, SubtitleFonts.installed().single { it.id == font.id })
            assertEquals(font, SubtitleFonts.import("Renamed.ttf", bytes))
            assertNotNull(SubtitleFontStorage.fontFamily(font.id))
            assertFailsWith<IllegalArgumentException> { SubtitleFonts.import("Invalid.ttf", bytes.copyOf(30)) }
            assertEquals(listOf(font), SubtitleFonts.installed())
            SubtitleFonts.remove(font.id)
            assertNull(SubtitleFontStorage.read(font.id))
        } finally {
            SubtitleFonts.remove(font.id)
        }
    }
}
