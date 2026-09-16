package com.nuvio.app.features.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_close
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OpeningOverlay(
    artwork: String?,
    logo: String?,
    title: String?,
    onBack: () -> Unit,
    horizontalSafePadding: Dp,
    modifier: Modifier = Modifier,
    message: String? = null,
    progress: Float? = null,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 700, delayMillis = 400, easing = LinearEasing),
        label = "openingOverlayContentAlpha",
    )
    val pulse = rememberInfiniteTransition(label = "openingOverlayContentPulse")
    val contentScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "openingOverlayContentScale",
    )
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val logoWidth = minOf(320.dp, maxWidth - 48.dp)
        val logoHeight = minOf(180.dp, maxHeight * 0.4f)
        val titleFontSize = if (maxWidth < 600.dp) 30.sp else 42.sp
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.3f),
                                0.35f to Color.Black.copy(alpha = 0.6f),
                                0.7f to Color.Black.copy(alpha = 0.8f),
                                1f to Color.Black.copy(alpha = 0.9f),
                            ),
                        ),
                    ),
            )
        }

        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                .padding(top = 20.dp, start = horizontalSafePadding, end = horizontalSafePadding + 20.dp),
            containerColor = Color.Black.copy(alpha = 0.3f),
            contentColor = Color.White,
            buttonSize = 44.dp,
            iconSize = 24.dp,
            contentDescription = stringResource(Res.string.compose_player_close),
        )

        val targetProgress = progress?.coerceIn(0f, 1f)
        val animatedProgress by animateFloatAsState(
            targetValue = targetProgress ?: 0f,
            animationSpec = tween(
                durationMillis = if ((targetProgress ?: 0f) >= 0.999f) 160 else 400,
                easing = LinearEasing,
            ),
            label = "openingOverlayP2pProgress",
        )
        val progressActive = targetProgress != null
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            content = {
                Box(contentAlignment = Alignment.Center) {
                    if (logoUrl != null && !logoLoadError) {
                        Box(
                            modifier = Modifier
                                .width(logoWidth)
                                .height(logoHeight),
                        ) {
                            AsyncImage(
                                model = logoUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = if (progressActive) 0.25f else contentAlpha
                                        if (!progressActive) {
                                            scaleX = contentScale
                                            scaleY = contentScale
                                        }
                                    },
                                contentScale = ContentScale.Fit,
                                onError = { logoLoadError = true },
                            )
                            if (progressActive) {
                                AsyncImage(
                                    model = logoUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            clipRect(right = size.width * animatedProgress) {
                                                this@drawWithContent.drawContent()
                                            }
                                        },
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    } else if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            style = MaterialTheme.nuvioTypeScale.displayMd.copy(
                                fontSize = titleFontSize,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .graphicsLayer {
                                    alpha = contentAlpha
                                    scaleX = contentScale
                                    scaleY = contentScale
                                },
                        )
                    } else {
                        NuvioLoadingIndicator(
                            color = Color(0xFFE50914),
                            modifier = Modifier.size(54.dp),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val showHorizontalProgress = progressActive && (logoUrl == null || logoLoadError)
                    Spacer(modifier = Modifier.height(16.dp))
                    Crossfade(
                        targetState = message?.takeIf { it.isNotBlank() },
                        animationSpec = tween(260),
                        label = "openingLoadingMessage",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    ) { loadingMessage ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (loadingMessage != null) {
                                Text(
                                    text = loadingMessage,
                                    color = Color.White.copy(alpha = 0.72f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                )
                            }
                        }
                    }
                    if (showHorizontalProgress) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .height(4.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress)
                                    .height(4.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val artworkContent = measurables[0].measure(looseConstraints)
            val artworkTop = (constraints.maxHeight - artworkContent.height) / 2
            val statusTop = artworkTop + artworkContent.height
            val statusContent = measurables[1].measure(
                looseConstraints.copy(maxHeight = constraints.maxHeight - statusTop),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                artworkContent.placeRelative((constraints.maxWidth - artworkContent.width) / 2, artworkTop)
                statusContent.placeRelative((constraints.maxWidth - statusContent.width) / 2, statusTop)
            }
        }
    }
}
