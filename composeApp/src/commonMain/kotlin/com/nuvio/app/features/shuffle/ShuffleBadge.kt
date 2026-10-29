package com.nuvio.app.features.shuffle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.shuffle_badge
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ShuffleBadge(modifier: Modifier = Modifier) {
    Icon(Icons.Default.Shuffle, contentDescription = stringResource(Res.string.shuffle_badge),
        tint = Color.White, modifier = modifier.background(Color.Black.copy(alpha = 0.8f), CircleShape)
            .padding(6.dp).size(16.dp))
}
