package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.nuvio
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NavigationBarPreview(style: NavBarStyle, isTablet: Boolean, glowEnabled: Boolean) {
    val tokens = MaterialTheme.nuvio
    val hazeState = rememberHazeState()
    val barScrollState = remember(style, isTablet) {
        NuvioNavBarScrollState().apply {
            if (isTablet || style == NavBarStyle.COMPACT) collapse()
        }
    }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val items = listOf(
        FloatingNavigationItem(stringResource(Res.string.compose_nav_home), selectedIndex == 0, { selectedIndex = 0 }, icon = Icons.Default.Home),
        FloatingNavigationItem(stringResource(Res.string.compose_nav_search), selectedIndex == 1, { selectedIndex = 1 }, drawable = Res.drawable.sidebar_search),
        FloatingNavigationItem(stringResource(Res.string.compose_nav_library), selectedIndex == 2, { selectedIndex = 2 }, drawable = Res.drawable.sidebar_library),
        FloatingNavigationItem(stringResource(Res.string.compose_nav_profile), selectedIndex == 3, { selectedIndex = 3 }, icon = Icons.Default.Person),
    )
    Column(Modifier.padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(156.dp).clip(tokens.shapes.card)) {
            Column(
                Modifier.matchParentSize()
                    .hazeSource(hazeState)
                    .then(if (!isTablet && style == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(barScrollState.nestedScrollConnection) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .background(tokens.colors.surfaceCard)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { column ->
                            Box(
                                Modifier.weight(1f).height(72.dp).clip(tokens.shapes.compactCard)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                tokens.colors.accent.copy(alpha = 0.25f + (row + column) * 0.12f),
                                                tokens.colors.surfacePopover,
                                            ),
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
            if (style == NavBarStyle.CLASSIC && !isTablet) {
                NuvioClassicNavigationBar(
                    Modifier.align(Alignment.BottomCenter).background(tokens.colors.background),
                ) {
                    items.forEach { item ->
                        if (item.icon != null) {
                            NavItem(item.selected, item.onClick, item.icon, item.label)
                        } else if (item.drawable != null) {
                            NavItem(item.selected, item.onClick, item.drawable, item.label)
                        }
                    }
                }
            } else {
                FloatingNavigationBar(
                    items = items,
                    modifier = Modifier.align(if (isTablet) Alignment.TopCenter else Alignment.BottomCenter)
                        .then(if (isTablet) Modifier.widthIn(max = 416.dp) else Modifier),
                    scrollState = barScrollState,
                    hazeState = hazeState,
                    contentPadding = PaddingValues(vertical = 16.dp),
                    compactSize = isTablet,
                    glowEnabled = glowEnabled,
                )
            }
        }
        Text(
            text = stringResource(
                if (!isTablet && style == NavBarStyle.ADAPTIVE) Res.string.settings_nav_bar_preview_hint
                else Res.string.settings_nav_bar_preview_tap_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
        )
    }
}
