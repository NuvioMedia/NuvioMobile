package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.SkeletonPosterRow
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.skeleton

@Composable
fun HomeSkeletonHero(
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
    ) {
        val layout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )
        val containerWidth = maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight)
                .skeleton(RectangleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.02f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.34f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.bottomFadeHeight)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        horizontal = layout.contentHorizontalPadding,
                        vertical = layout.contentVerticalPadding,
                    ),
                horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(layout.contentWidthFraction)
                        .widthIn(max = layout.contentMaxWidth),
                    horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    val logoWidth = containerWidth
                        .times(layout.contentWidthFraction * layout.logoWidthFraction)
                        .coerceAtMost(layout.contentMaxWidth * layout.logoWidthFraction)

                    Box(
                        modifier = Modifier.width(logoWidth).height(logoWidth / 2.6f),
                        contentAlignment = if (layout.isTablet) Alignment.BottomStart else Alignment.BottomCenter,
                    ) {
                        SkeletonBlock(width = logoWidth, height = 32.dp, cornerRadius = 8.dp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SkeletonBlock(width = 156.dp, height = 10.dp)
                }
                if (!layout.isTablet) {
                    Spacer(modifier = Modifier.height(14.dp))
                    SkeletonBlock(
                        width = 160.dp,
                        height = 48.dp,
                        cornerRadius = 40.dp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Spacer(modifier = Modifier.height(14.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun HomeSkeletonRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val skeletonWidth = if (posterCardStyle.catalogLandscapeModeEnabled) {
        landscapePosterWidth(posterCardStyle.widthDp)
    } else {
        posterCardStyle.widthDp.dp
    }
    val skeletonHeight = if (posterCardStyle.catalogLandscapeModeEnabled) {
        landscapePosterHeightForWidth(skeletonWidth)
    } else {
        posterCardStyle.heightDp.dp
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sectionPadding = horizontalPadding ?: homeSectionHorizontalPaddingForWidth(maxWidth.value)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.padding(horizontal = sectionPadding).height(32.dp)) {
                SkeletonBlock(
                    modifier = Modifier.align(Alignment.CenterStart),
                    width = 128.dp,
                    height = 16.dp,
                )
            }
            SkeletonPosterRow(
                width = skeletonWidth,
                height = skeletonHeight,
                cornerRadius = posterCardStyle.cornerRadiusDp.dp,
                horizontalPadding = sectionPadding,
                showLabels = !posterCardStyle.hideLabelsEnabled,
                showDetail = !posterCardStyle.catalogLandscapeModeEnabled,
            )
        }
    }
}
