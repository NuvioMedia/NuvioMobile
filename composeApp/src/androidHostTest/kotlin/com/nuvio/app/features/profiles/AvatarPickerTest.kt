package com.nuvio.app.features.profiles

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1400dp-h1400dp-mdpi")
class AvatarPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rowsFillTheWidthAcrossDensitiesAndLayoutDirections() {
        val sizes = listOf(
            GridSize(333, 3f, 5),
            GridSize(360, 2.625f, 5),
            GridSize(280, 2f, 4),
            GridSize(600, 1.25f, 8),
        )
        val configuration = mutableStateOf(sizes.first())
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var selectedId: String? = null
        compose.setContent {
            val size = configuration.value
            CompositionLocalProvider(
                LocalDensity provides Density(size.density),
                LocalLayoutDirection provides direction.value,
            ) {
                NuvioTheme {
                    Box(Modifier.width(size.width.dp)) {
                        AvatarPicker(
                            avatars = List(size.columns + 1) { index ->
                                AvatarCatalogItem(id = "$index", displayName = "Avatar $index")
                            },
                            selectedAvatarId = null,
                            onAvatarSelected = { selectedId = it.id },
                            modifier = Modifier.testTag("avatars"),
                        )
                    }
                }
            }
        }

        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (size in sizes) {
                compose.runOnIdle {
                    configuration.value = size
                    direction.value = layoutDirection
                }
                val grid = compose.onNodeWithTag("avatars").fetchSemanticsNode().boundsInRoot
                val first = compose.onNodeWithContentDescription("Avatar 0").fetchSemanticsNode().boundsInRoot
                val last = compose.onNodeWithContentDescription("Avatar ${size.columns - 1}")
                    .fetchSemanticsNode().boundsInRoot
                val nextRow = compose.onNodeWithContentDescription("Avatar ${size.columns}")
                    .fetchSemanticsNode().boundsInRoot

                assertEquals(first.top, last.top, "Last column wrapped at $size in $layoutDirection")
                if (layoutDirection == LayoutDirection.Ltr) {
                    assertEquals(grid.left, first.left)
                    assertEquals(grid.right, last.right)
                    assertEquals(first.left, nextRow.left)
                } else {
                    assertEquals(grid.right, first.right)
                    assertEquals(grid.left, last.left)
                    assertEquals(first.right, nextRow.right)
                }
                assertTrue(abs(first.width - last.width) <= 1f)
                assertEquals(first.width, first.height)
                assertEquals(first.width, nextRow.width)
                assertTrue(nextRow.top > first.bottom)
            }
        }
        compose.onNodeWithContentDescription("Avatar 0").performClick()
        compose.runOnIdle { assertEquals("0", selectedId) }
    }

    private data class GridSize(val width: Int, val density: Float, val columns: Int)
}
