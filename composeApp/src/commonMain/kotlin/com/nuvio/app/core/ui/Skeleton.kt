package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun SkeletonBlock(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp,
    cornerRadius: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .skeleton(RoundedCornerShape(cornerRadius)),
    )
}

@Composable
internal fun SkeletonPoster(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 0.68f,
    cornerRadius: Dp,
    showLabels: Boolean,
    showDetail: Boolean = true,
    labelSpacing: Dp = 8.dp,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(labelSpacing),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .skeleton(RoundedCornerShape(cornerRadius)),
        )
        if (showLabels) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.82f), height = 16.dp)
            if (showDetail) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.46f), height = 12.dp)
            }
        }
    }
}

@Composable
internal fun SkeletonPosterRow(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    showDetail: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val count = ((maxWidth - horizontalPadding) / (width + 10.dp)).toInt().coerceAtLeast(0) + 2
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(count) {
                SkeletonPoster(
                    modifier = Modifier.width(width),
                    aspectRatio = width / height,
                    cornerRadius = cornerRadius,
                    showLabels = showLabels,
                    showDetail = showDetail,
                    labelSpacing = 6.dp,
                )
            }
        }
    }
}
