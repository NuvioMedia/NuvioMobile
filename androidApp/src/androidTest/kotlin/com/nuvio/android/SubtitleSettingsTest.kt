package com.nuvio.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleAppearance
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubtitleSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun importsPreviewsAndRemovesACustomFontThroughTheSystemPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Subtitle test font.ttf")
            put(MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }))
        context.assets.open("composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_regular.ttf").use { input ->
            requireNotNull(resolver.openOutputStream(uri)).use(input::copyTo)
        }
        val original = PlayerSettingsRepository.uiState.value.subtitleStyle
        try {
            compose.runOnUiThread {
                PlayerSettingsRepository.setSubtitleStyle(SubtitleStyleState(
                    backgroundColor = Color.Black.copy(alpha = 0.65f),
                    appearance = SubtitleAppearance("serif", 16, true, 80, 3),
                ))
                compose.activity.setContent { NuvioTheme { SettingsScreen(initialPageName = "PlaybackSubtitles") } }
            }
            compose.onNodeWithText("Font").performScrollTo().assertIsDisplayed()
            screenshot("settings-preview.png")
            compose.onNodeWithText("Font").performClick()
            screenshot("font-picker.png")
            compose.onNodeWithText("Import font").performClick()
            compose.waitUntil(15_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Subtitle test font.ttf")?.isNotEmpty() == true
            }
            val document = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Subtitle test font.ttf").first()
            var target = document
            while (!target.isClickable && target.parent != null) target = target.parent
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15_000) { PlayerSettingsRepository.uiState.value.subtitleStyle.appearance.fontId.startsWith("custom-") }
            compose.onNode(hasText("Subtitle test font") and isSelected()).performScrollTo().assertIsDisplayed()
            screenshot("font-imported.png")
            compose.onNodeWithText("Done").performClick()
            screenshot("font-preview.png")
            compose.onNodeWithText("Font").performClick()
            compose.onNodeWithContentDescription("Remove Subtitle test font").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("default", PlayerSettingsRepository.uiState.value.subtitleStyle.appearance.fontId) }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Reset appearance").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(SubtitleAppearance(), PlayerSettingsRepository.uiState.value.subtitleStyle.appearance) }
        } finally {
            resolver.delete(uri, null, null)
            compose.runOnUiThread { PlayerSettingsRepository.setSubtitleStyle(original) }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "subtitle-tests").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
