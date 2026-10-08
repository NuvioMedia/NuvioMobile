package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.extractReleaseYearForDisplay
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.formatRuntimeForDisplay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.detail_logo_content_description
import nuvio.composeapp.generated.resources.details_season_count
import nuvio.composeapp.generated.resources.details_director
import nuvio.composeapp.generated.resources.details_writer
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun TabletDetailBackdrop(
    meta: MetaDetails,
    cinematic: Boolean,
    scrollOffsetPx: () -> Float,
    heroHeightPx: () -> Int,
    heroTrailerSourceUrl: String?,
    heroTrailerSourceAudioUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerPlayWhenReady: () -> Boolean,
    heroTrailerMuted: Boolean,
    heroGradientColor: Color?,
    onBackdropLoaded: (Painter, ImageBitmap?) -> Unit,
    onHeroTrailerReady: () -> Unit,
    onHeroTrailerFinished: () -> Unit,
) {
    val backgroundColor = heroGradientColor ?: MaterialTheme.colorScheme.background
    val scrollProgress = {
        val heroHeight = heroHeightPx()
        if (heroHeight <= 0) 0f else (scrollOffsetPx() / (heroHeight * 0.75f)).coerceIn(0f, 1f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BackdropArtwork(
            meta = meta,
            gradientColor = backgroundColor,
            blur = false,
            heroTrailerSourceUrl = heroTrailerSourceUrl,
            heroTrailerSourceAudioUrl = heroTrailerSourceAudioUrl,
            heroTrailerReady = heroTrailerReady,
            heroTrailerPlayWhenReady = heroTrailerPlayWhenReady,
            heroTrailerMuted = heroTrailerMuted,
            onBackdropLoaded = onBackdropLoaded,
            onHeroTrailerReady = onHeroTrailerReady,
            onHeroTrailerFinished = onHeroTrailerFinished,
        )
        if (cinematic) {
            BackdropArtwork(
                meta = meta,
                gradientColor = backgroundColor,
                blur = true,
                modifier = Modifier.graphicsLayer { alpha = scrollProgress() },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = scrollProgress() * if (cinematic) 0.36f else 0.86f
                }
                .background(backgroundColor),
        )
    }
}

@Composable
private fun BackdropArtwork(
    meta: MetaDetails,
    gradientColor: Color,
    blur: Boolean,
    modifier: Modifier = Modifier,
    heroTrailerSourceUrl: String? = null,
    heroTrailerSourceAudioUrl: String? = null,
    heroTrailerReady: Boolean = false,
    heroTrailerPlayWhenReady: () -> Boolean = { false },
    heroTrailerMuted: Boolean = true,
    onBackdropLoaded: (Painter, ImageBitmap?) -> Unit = { _, _ -> },
    onHeroTrailerReady: () -> Unit = {},
    onHeroTrailerFinished: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val opacity = NuvioTokens.Opacity
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "tablet_detail_hero_trailer_alpha",
    )
    val gradientIntensity by animateFloatAsState(
        targetValue = if (heroTrailerReady) 0.3f else 1f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "tablet_detail_hero_gradient_intensity",
    )
    val artworkModifier = Modifier
        .fillMaxSize()
        .then(if (blur) Modifier.blur(30.dp) else Modifier)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientColor),
    ) {
        val imageUrl = meta.background ?: meta.poster
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = meta.name,
                modifier = artworkModifier,
                alignment = BiasAlignment(0f, BackdropVerticalBias),
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    onBackdropLoaded(state.painter, loadedBackdropImageBitmap(state.result))
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                colorScheme.surfaceVariant.copy(alpha = 0.48f),
                                colorScheme.background,
                                colorScheme.surfaceVariant.copy(alpha = 0.48f),
                            ),
                        ),
                    ),
            )
        }

        if (heroTrailerSourceUrl != null) {
            HeroTrailerPlayerSurface(
                sourceUrl = heroTrailerSourceUrl,
                sourceAudioUrl = heroTrailerSourceAudioUrl,
                playWhenReady = heroTrailerPlayWhenReady(),
                muted = heroTrailerMuted,
                modifier = artworkModifier.graphicsLayer {
                    alpha = trailerAlpha
                },
                onReady = onHeroTrailerReady,
                onEnded = onHeroTrailerFinished,
                onError = onHeroTrailerFinished,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val sideFade = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to gradientColor,
                            0.12f to gradientColor.copy(alpha = 0.98f * gradientIntensity),
                            0.34f to gradientColor.copy(alpha = opacity.overlayHeavy * gradientIntensity),
                            0.62f to gradientColor.copy(alpha = opacity.overlayLight * gradientIntensity),
                            0.86f to gradientColor.copy(alpha = opacity.subtle * gradientIntensity),
                            1.00f to Color.Transparent,
                        ),
                        startX = if (isRtl) size.width else 0f,
                        endX = if (isRtl) 0f else size.width,
                    )
                    val bottomSpread = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to gradientColor.copy(alpha = 0.12f * gradientIntensity),
                            0.55f to gradientColor.copy(alpha = 0.12f * gradientIntensity),
                            1.00f to Color.Transparent,
                        ),
                        center = Offset(if (isRtl) size.width else 0f, size.height * 1.15f),
                        radius = size.width * 0.72f,
                    )
                    onDrawBehind {
                        drawRect(sideFade)
                        drawRect(bottomSpread)
                    }
                },
        )
    }
}

@Composable
fun TabletDetailHero(
    meta: MetaDetails,
    showOverview: Boolean,
    showOverallRatings: Boolean,
    isMdbListActive: Boolean,
    horizontalPadding: Dp,
    heroTrailerSourceUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerMuted: Boolean,
    onHeroTrailerMuteToggle: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    actions: (@Composable () -> Unit)?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val space = NuvioTokens.Space
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "tablet_detail_hero_controls_alpha",
    )
    var logoLoadError by remember(meta.id, meta.logo) { mutableStateOf(false) }
    val logoUrl = meta.logo?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TabletHeroHeight)
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 760.dp)
                .padding(
                    start = horizontalPadding,
                    end = space.s32,
                    top = TabletHeroTopInset,
                    bottom = space.s40,
                ),
        ) {
            if (logoUrl != null && !logoLoadError) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .height(120.dp),
                    alignment = Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    onError = { logoLoadError = true },
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = NuvioTokens.Type.displayMd,
                        lineHeight = NuvioTokens.LineHeight.displayMd,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showOverview) {
                Spacer(modifier = Modifier.height(space.s20))
                TabletHeroMetaRow(meta = meta, showImdbRating = showOverallRatings && !isMdbListActive)
                if (isMdbListActive && meta.externalRatings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(space.s12))
                    DetailRatingsRow(
                        ratings = meta.externalRatings,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                }
            }
            if (meta.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(space.s12))
                Text(
                    text = meta.genres.take(4).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = NuvioTokens.Type.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showOverview) {
                val credit = when {
                    meta.director.isNotEmpty() -> stringResource(Res.string.details_director) to meta.director
                    meta.writer.isNotEmpty() -> stringResource(Res.string.details_writer) to meta.writer
                    else -> null
                }
                credit?.let { (label, names) ->
                    Spacer(modifier = Modifier.height(space.s8))
                    MetaLabelValueRow(label = label, value = names.joinToString(", "))
                }
            }
            meta.description?.takeIf { showOverview && it.isNotBlank() }?.let { synopsis ->
                Spacer(modifier = Modifier.height(space.s16))
                ExpandableDescription(
                    text = synopsis,
                    collapsedMaxLines = 4,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = NuvioTokens.Type.bodyLg,
                        lineHeight = NuvioTokens.LineHeight.bodyLg,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                )
            }
            if (actions != null) {
                Spacer(modifier = Modifier.height(space.s28))
                actions()
            }
        }

        if (heroTrailerSourceUrl != null) {
            Surface(
                onClick = onHeroTrailerMuteToggle,
                enabled = heroTrailerReady,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + space.s8,
                        end = horizontalPadding,
                    )
                    .size(40.dp)
                    .graphicsLayer { alpha = trailerAlpha },
                shape = CircleShape,
                color = colorScheme.surfaceVariant.copy(alpha = 0.82f),
                contentColor = colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (heroTrailerMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabletHeroMetaRow(meta: MetaDetails, showImdbRating: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val seasonCount = remember(meta.videos) {
        meta.videos.mapNotNull { it.season }.filter { it > 0 }.toSet().size
    }
    val metaItems = buildList {
        meta.releaseInfo?.let(::extractReleaseYearForDisplay)?.let { add(it.toString()) }
        if (seasonCount > 0) add(pluralStringResource(Res.plurals.details_season_count, seasonCount, seasonCount))
        formatRuntimeForDisplay(meta.runtime)?.let(::add)
    }
    val validImdbRating = meta.imdbRating
        ?.takeIf { showImdbRating }
        ?.takeIf { raw -> raw.toDoubleOrNull()?.let { it > 0.0 } == true }
    val itemStyle = MaterialTheme.typography.titleSmall.copy(
        fontSize = NuvioTokens.Type.bodyLg,
        fontWeight = FontWeight.Bold,
        letterSpacing = NuvioTokens.LetterSpacing.none,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
    ) {
        metaItems.forEach { item ->
            Text(
                text = item,
                style = itemStyle,
                color = colorScheme.onBackground,
                maxLines = 1,
            )
        }
        meta.ageRating?.takeIf { it.isNotBlank() }?.let { rating ->
            DetailHeroMetaBadge(text = rating)
        }
        if (validImdbRating != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImdbRatingSourceLabel(
                    storeTextStyle = itemStyle,
                    storeTextColor = ImdbYellow,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = validImdbRating,
                    style = itemStyle,
                    color = ImdbYellow,
                )
            }
        }
    }
}

private val TabletHeroHeight = 660.dp
private val TabletHeroTopInset = 96.dp
private const val BackdropVerticalBias = -0.6f
