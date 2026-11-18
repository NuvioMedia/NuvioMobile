package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.ArtworkBackdrop
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.platformPhysicalTopInset
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.isIos
import com.nuvio.app.navigation.LocalUseNativeNavigation
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailFloatingHeader(
    meta: MetaDetails,
    isSaved: Boolean,
    progress: Float,
    backgroundColor: Color? = null,
    background: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useNativeNavigation = LocalUseNativeNavigation.current
    val safeAreaTop = if (useNativeNavigation) {
        platformPhysicalTopInset()
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    }
    val headerTopPadding = (safeAreaTop - 6.dp).coerceAtLeast(safeAreaTop * 0.8f)
    val interactive = progress > 0.05f
    val surfaceColor = backgroundColor ?: if (isIos) {
        MaterialTheme.colorScheme.surface.copy(alpha = 1.0f)
    } else {
        MaterialTheme.colorScheme.background
    }
    var logoLoadError by remember(meta.id, meta.logo) {
        mutableStateOf(false)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = lerp((-20).dp, 0.dp, progress).toPx()
                shadowElevation = 4.dp.toPx()
                shape = RectangleShape
            },
    ) {
        val logoWidth = (maxWidth * 0.6f).coerceAtMost(240.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .then(if (background == null) Modifier.background(surfaceColor) else Modifier),
        ) {
            if (background != null) {
                Box(modifier = Modifier.matchParentSize()) { background() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = headerTopPadding, start = 16.dp, end = 16.dp)
                    .height(56.dp)
                    .graphicsLayer { alpha = progress },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (interactive && !useNativeNavigation) {
                    NuvioBackButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        buttonSize = 40.dp,
                        iconSize = 24.dp,
                    )
                } else {
                    // Native iOS navigation owns the back button, but retaining
                    // this slot keeps the Compose logo centered as the floating
                    // header replaces the hero while scrolling.
                    Box(modifier = Modifier.size(40.dp))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!meta.logo.isNullOrBlank() && !logoLoadError) {
                        AsyncImage(
                            model = meta.logo,
                            contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                            modifier = Modifier
                                .width(logoWidth)
                                .widthIn(max = 240.dp)
                                .height(42.dp),
                            onError = { logoLoadError = true },
                        )
                    } else {
                        Text(
                            text = meta.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                DetailFloatingHeaderAction(
                    isSaved = isSaved,
                    enabled = interactive,
                    onClick = onToggleSaved,
                )
            }

            if (isIos) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color.White.copy(alpha = 0.15f)),
                )
            }
        }
    }
}

@Composable
internal fun CinematicHeaderBackdrop(
    image: ImageBitmap?,
    screenHeight: Dp,
    alignment: Alignment = Alignment.Center,
) {
    ArtworkBackdrop(
        image = image,
        alignment = alignment,
        modifier = Modifier
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .height(screenHeight),
    )
}

@Composable
private fun DetailFloatingHeaderAction(
    isSaved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Add,
            contentDescription = if (isSaved) {
                stringResource(Res.string.hero_remove_from_library)
            } else {
                stringResource(Res.string.hero_add_to_library)
            },
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}
