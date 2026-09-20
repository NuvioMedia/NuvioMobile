package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.heroStretchHeight
import com.nuvio.app.core.ui.heroStretchZoom
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.heroTrailerFullscreenChromeAlpha
import com.nuvio.app.features.trailer.TrailerPlaybackState
import com.nuvio.app.features.trailer.TrailerPlayerControls
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailHero(
    meta: MetaDetails,
    isTablet: Boolean = false,
    scrollOffset: () -> Int = { 0 },
    stretchPx: () -> Float = { 0f },
    contentMaxWidth: Dp = 560.dp,
    onHeightChanged: (Int) -> Unit = {},
    heroTrailerSourceUrl: String? = null,
    heroTrailerSourceAudioUrl: String? = null,
    heroTrailerReady: Boolean = false,
    heroTrailerPlayWhenReady: () -> Boolean = { false },
    heroTrailerMuted: Boolean = true,
    heroGradientColor: Color? = null,
    onBackdropLoaded: (Painter, ImageBitmap?) -> Unit = { _, _ -> },
    onHeroTrailerMuteToggle: () -> Unit = {},
    onHeroTrailerReady: () -> Unit = {},
    onHeroTrailerEnded: () -> Unit = {},
    onHeroTrailerError: () -> Unit = {},
    fullscreenProgress: () -> Float = { 0f },
    showFullscreenControls: Boolean = false,
    fullscreenTitle: String = "",
    onExitFullscreen: (() -> Unit)? = null,
    lockHeroSize: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val liveHeroHeight = detailHeroHeight(maxWidth, isTablet)
        var lockedHeroHeight by remember { mutableStateOf<Dp?>(null) }
        SideEffect {
            lockedHeroHeight = if (lockHeroSize) {
                lockedHeroHeight ?: liveHeroHeight
            } else {
                null
            }
        }
        val heroHeight = lockedHeroHeight ?: liveHeroHeight
        val trailerAlpha by animateFloatAsState(
            targetValue = if (heroTrailerReady) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "detail_hero_trailer_alpha",
        )
        val muteIconSize = if (isTablet) 20.dp else 22.dp
        val heroChromeTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            8.dp +
            ((40.dp - muteIconSize) / 2)
        val bottomGradientColor = heroGradientColor ?: MaterialTheme.colorScheme.background
        var logoLoadError by remember(meta.id, meta.logo) {
            mutableStateOf(false)
        }
        val logoUrl = meta.logo?.takeIf { it.isNotBlank() }
        val playbackState = remember(heroTrailerSourceUrl) { TrailerPlaybackState() }

        val heroBaseHeightPx = with(LocalDensity.current) { heroHeight.roundToPx() }
        LaunchedEffect(heroBaseHeightPx) { onHeightChanged(heroBaseHeightPx) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heroStretchHeight(heroHeight, stretchPx)
                .graphicsLayer {
                    clip = true
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val imageUrl = meta.background ?: meta.poster
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = meta.name,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(heroHeight)
                            .heroStretchZoom(stretchPx)
                            .graphicsLayer {
                                translationY = scrollOffset() * 0.5f
                                scaleX = 1.08f
                                scaleY = 1.08f
                            },
                        alignment = if (isTablet) Alignment.TopCenter else Alignment.Center,
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            onBackdropLoaded(
                                state.painter,
                                loadedBackdropImageBitmap(state.result),
                            )
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
                if (heroTrailerSourceUrl != null) {
                    HeroTrailerPlayerSurface(
                        sourceUrl = heroTrailerSourceUrl,
                        sourceAudioUrl = heroTrailerSourceAudioUrl,
                        playWhenReady = heroTrailerPlayWhenReady() && playbackState.playWhenReady,
                        muted = heroTrailerMuted,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxSize()
                            .graphicsLayer {
                                val fullscreen = fullscreenProgress()
                                val chromeAlpha = heroTrailerFullscreenChromeAlpha(fullscreen)
                                alpha = trailerAlpha
                                translationY = scrollOffset() * 0.5f * chromeAlpha
                                val idleZoom = 1.08f
                                val zoom = idleZoom + (1f - idleZoom) * fullscreen
                                scaleX = zoom
                                scaleY = zoom
                            },
                        onReady = onHeroTrailerReady,
                        onEnded = onHeroTrailerEnded,
                        onError = onHeroTrailerError,
                        onControllerReady = { playbackState.controller = it },
                        onSnapshot = { playbackState.snapshot = it },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                enabled = heroTrailerReady && !showFullscreenControls,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onHeroTrailerMuteToggle,
                            ),
                    )
                    AnimatedContent(
                        targetState = heroTrailerMuted,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = heroChromeTopPadding,
                                end = if (isTablet) 32.dp else 22.dp,
                            )
                            .graphicsLayer {
                                alpha = trailerAlpha * 0.72f * heroTrailerFullscreenChromeAlpha(fullscreenProgress())
                            },
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(120)) + scaleIn(
                                initialScale = 0.82f,
                                animationSpec = tween(160),
                            )) togetherWith (fadeOut(animationSpec = tween(90)) + scaleOut(
                                targetScale = 1.12f,
                                animationSpec = tween(100),
                            ))
                        },
                        label = "detail_hero_trailer_mute_icon",
                    ) { muted ->
                        Icon(
                            imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(muteIconSize),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isTablet) 360.dp else 320.dp)
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            alpha = heroTrailerFullscreenChromeAlpha(fullscreenProgress())
                        }
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Transparent,
                                    0.16f to bottomGradientColor.copy(alpha = 0.04f),
                                    0.32f to bottomGradientColor.copy(alpha = 0.14f),
                                    0.50f to bottomGradientColor.copy(alpha = 0.34f),
                                    0.68f to bottomGradientColor.copy(alpha = 0.62f),
                                    0.84f to bottomGradientColor.copy(alpha = 0.84f),
                                    1.00f to bottomGradientColor,
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 32.dp else 18.dp)
                        .padding(bottom = 8.dp)
                        .graphicsLayer {
                            alpha = heroTrailerFullscreenChromeAlpha(fullscreenProgress())
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (logoUrl != null && !logoLoadError) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                            modifier = Modifier
                                .fillMaxWidth(if (isTablet) 0.56f else 0.6f)
                                .widthIn(max = contentMaxWidth)
                                .height(if (isTablet) 72.dp else 80.dp),
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Fit,
                            onError = { logoLoadError = true },
                        )
                    } else {
                        Text(
                            text = meta.name,
                            style = if (isTablet) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (meta.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = meta.genres.take(3).joinToString(" \u2022 "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showFullscreenControls && heroTrailerSourceUrl != null,
                    enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 140)),
                ) {
                    TrailerPlayerControls(
                        state = playbackState,
                        canControlPlayback = heroTrailerReady,
                        title = fullscreenTitle,
                        onExitFullscreen = onExitFullscreen,
                    )
                }
            }
        }
    }
}

private fun detailHeroHeight(maxWidth: Dp, isTablet: Boolean): Dp =
    if (!isTablet) {
        (maxWidth * 1.33f).coerceIn(420.dp, 760.dp)
    } else {
        (maxWidth * 0.42f).coerceIn(300.dp, 420.dp)
    }
