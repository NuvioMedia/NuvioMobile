package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NuvioScreenHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arabicTitleUsesAvailableWidth() = assertTitleWidth(LayoutDirection.Rtl, "بحث")

    @Test fun englishTitleUsesAvailableWidth() = assertTitleWidth(LayoutDirection.Ltr, "Search")

    @Test fun titleLeavesRoomForBackButtonAndAction() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NuvioTheme {
                    NuvioScreenHeader(
                        title = "التنزيلات",
                        modifier = Modifier.size(320.dp, 100.dp).testTag("header"),
                        includeStatusBarPadding = false,
                        onBack = {},
                        actions = { Box(Modifier.size(40.dp).testTag("headerAction")) },
                    )
                }
            }
        }
        val titleBounds = compose.onNodeWithText("التنزيلات").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithTag("headerAction").fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot

        assertTrue(
            abs(titleBounds.left - actionBounds.right) <= 1f,
            "RTL title should fill the slot up to the action",
        )
        assertTrue(
            titleBounds.right <= backBounds.left + 1f,
            "RTL title should leave the back button's space clear",
        )
    }

    private fun assertTitleWidth(direction: LayoutDirection, title: String) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NuvioTheme {
                    NuvioScreenHeader(
                        title = title,
                        modifier = Modifier.size(320.dp, 100.dp).testTag("header"),
                        includeStatusBarPadding = false,
                    )
                }
            }
        }
        val titleWidth = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.width
        val headerWidth = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            abs(titleWidth - headerWidth) <= 1f,
            "Title should use the available header width in $direction layout",
        )
    }
}
