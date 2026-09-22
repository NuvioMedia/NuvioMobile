package com.nuvio.app.features.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.features.membership.ProfileBackgroundCatalogItem
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.profile_background_custom
import nuvio.composeapp.generated.resources.profile_background_normal
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileBackgroundPicker(
    backgrounds: List<ProfileBackgroundCatalogItem>,
    selectedBackgroundId: String?,
    selectedBackgroundUrl: String?,
    customBackgroundUrl: String?,
    standardBackgroundColor: Color,
    onSelectionChange: (String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileBackgroundItem(
            label = stringResource(Res.string.profile_background_normal),
            isSelected = selectedBackgroundId == null && selectedBackgroundUrl == null,
            onClick = { onSelectionChange(null, null) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                lerp(MaterialTheme.colorScheme.surface, standardBackgroundColor, 0.3f),
                                lerp(MaterialTheme.colorScheme.background, standardBackgroundColor, 0.14f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
        }
        customBackgroundUrl?.trim()?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            ProfileBackgroundItem(
                label = stringResource(Res.string.profile_background_custom),
                isSelected = selectedBackgroundId == null && selectedBackgroundUrl == imageUrl,
                onClick = { onSelectionChange(null, imageUrl) },
            ) {
                val context = LocalPlatformContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .memoryCacheKey("custom-profile-background-$imageUrl")
                        .diskCacheKey("custom-profile-background-$imageUrl")
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        backgrounds.forEach { background ->
            ProfileBackgroundItem(
                label = background.displayName,
                isSelected = selectedBackgroundUrl == null && selectedBackgroundId == background.id,
                onClick = { onSelectionChange(background.id, null) },
            ) {
                AsyncImage(
                    model = background.landscapeImageBytes,
                    contentDescription = background.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun ProfileBackgroundItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = MaterialTheme.themePalette
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(144.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(81.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) palette.secondary else MaterialTheme.colorScheme.outline,
                    shape = shape,
                ),
        ) {
            content()
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .width(24.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(palette.accentBrush()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = palette.onSecondary,
                    )
                }
            }
        }
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
