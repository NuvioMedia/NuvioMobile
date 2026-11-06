package com.nuvio.app.features.details.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp")
class RatingStarsTest {
    @get:Rule
    val compose = createComposeRule()

    private val value = mutableIntStateOf(0)

    @Test
    fun tapsPickHalfStarStepsAndDragsFollowTheFinger() {
        show()
        val stars = compose.onNodeWithTag("stars")
        stars.performTouchInput { click(Offset(width * 0.75f, centerY)) }
        compose.runOnIdle { assertEquals(8, value.intValue) }
        stars.performTouchInput { click(Offset(width * 0.02f, centerY)) }
        compose.runOnIdle { assertEquals(1, value.intValue) }
        stars.performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width - 1f, centerY)) }
        compose.runOnIdle { assertEquals(10, value.intValue) }
    }

    @Test
    fun rightToLeftLayoutsStartFromTheRight() {
        show(LayoutDirection.Rtl)
        compose.onNodeWithTag("stars").performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.runOnIdle { assertEquals(8, value.intValue) }
    }

    @Test
    fun accessibilityAdjustmentsStayInRange() {
        show()
        val stars = compose.onNodeWithTag("stars")
        stars.performSemanticsAction(SemanticsActions.SetProgress) { it(6.4f) }
        compose.runOnIdle { assertEquals(6, value.intValue) }
        stars.performSemanticsAction(SemanticsActions.SetProgress) { it(42f) }
        compose.runOnIdle { assertEquals(10, value.intValue) }
    }

    private fun show(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            NuvioTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    RatingStars(
                        value = value.intValue,
                        onValueChange = { value.intValue = it },
                        modifier = Modifier.testTag("stars"),
                    )
                }
            }
        }
    }
}
