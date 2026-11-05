package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp")
class SliderTest {
    @get:Rule
    val compose = createComposeRule()

    private val value = mutableFloatStateOf(0f)
    private var finished = 0

    @Test
    fun touchAndDragUpdateTheValueAndFinishEachGesture() {
        show(valueRange = -50f..50f)
        val slider = compose.onNodeWithTag("slider").assertTouchHeightIsEqualTo(48.dp)
        slider.performTouchInput { click(Offset(width * 0.75f, height * 0.85f)) }
        compose.runOnIdle {
            assertTrue(value.floatValue in 20f..30f)
            assertEquals(1, finished)
        }
        slider.performTouchInput {
            swipe(Offset(width * 0.75f, centerY), Offset(width * 0.25f, centerY))
        }
        compose.runOnIdle {
            assertTrue(value.floatValue in -30f..-20f)
            assertEquals(2, finished)
        }
    }

    @Test
    fun accessibilityAdjustmentsKeepTheConfiguredSteps() {
        value.floatValue = 10f
        show(valueRange = 10f..50f, steps = 3)
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(27f) }
        compose.runOnIdle {
            assertEquals(30f, value.floatValue)
            assertEquals(1, finished)
        }
    }

    @Test
    fun disabledSlidersIgnoreTouches() {
        value.floatValue = 25f
        show(enabled = false)
        compose.onNodeWithTag("slider").assertIsNotEnabled().performTouchInput {
            click(Offset(width * 0.75f, centerY))
            swipe(Offset(width * 0.25f, centerY), Offset(width * 0.75f, centerY))
        }
        compose.runOnIdle {
            assertEquals(25f, value.floatValue)
            assertEquals(0, finished)
        }
    }

    @Test
    fun rightToLeftSlidersIncreaseTowardTheLeft() {
        show(layoutDirection = LayoutDirection.Rtl)
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.runOnIdle {
            assertTrue(value.floatValue in 70f..80f)
            assertEquals(1, finished)
        }
    }

    private fun show(
        valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
        steps: Int = 0,
        enabled: Boolean = true,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Slider(
                        value = value.floatValue,
                        onValueChange = { value.floatValue = it },
                        onValueChangeFinished = { finished++ },
                        valueRange = valueRange,
                        steps = steps,
                        enabled = enabled,
                        modifier = Modifier.width(300.dp).testTag("slider"),
                    )
                }
            }
        }
    }
}
