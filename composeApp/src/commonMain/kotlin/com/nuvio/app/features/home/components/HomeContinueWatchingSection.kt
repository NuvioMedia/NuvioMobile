package com.nuvio.app.features.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DisintegratingContainer
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.ScopedDisintegrationTracker
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.WatchProgressCompletionPercentThreshold
import com.nuvio.app.features.watchprogress.continueWatchingItemKey
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.computeAirDateBadgeText
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val ContinueWatchingStatusBadgeShape = RoundedCornerShape(4.dp)
private val ContinueWatchingNewEpisodeBadgeColor = Color(0xFF1D4ED8)
private val ContinueWatchingNewSeasonBadgeColor = Color(0xFFB45309)
private const val ContinueWatchingLandscapeCardScale = 1.2f
internal val HomeContinueWatchingSectionBottomPadding = 12.dp

internal fun continueWatchingLandscapeCardWidth(basePosterWidthDp: Int): Dp =
    (landscapePosterWidth(basePosterWidthDp).value * ContinueWatchingLandscapeCardScale).dp

internal fun continueWatchingLandscapeCardHeight(basePosterWidthDp: Int): Dp =
    landscapePosterHeightForWidth(continueWatchingLandscapeCardWidth(basePosterWidthDp))

internal fun continueWatchingSectionHeightEstimate(
    style: ContinueWatchingSectionStyle,
    layout: ContinueWatchingLayout,
    basePosterWidthDp: Int,
): Dp {
    val headerHeight = NuvioTokens.Space.s40
    val headerToRowGap = NuvioTokens.Space.s8 + NuvioTokens.Space.s2
    val rowHeight = when (style) {
        ContinueWatchingSectionStyle.Card -> continueWatchingLandscapeCardHeight(basePosterWidthDp)
        ContinueWatchingSectionStyle.Wide -> layout.wideCardHeight
        ContinueWatchingSectionStyle.Poster -> layout.posterCardHeight + layout.posterTitleBlockHeight
    }
    return headerHeight + headerToRowGap + rowHeight + HomeContinueWatchingSectionBottomPadding
}

internal fun continueWatchingHeroViewportReserveHeight(
    style: ContinueWatchingSectionStyle,
    layout: ContinueWatchingLayout,
    basePosterWidthDp: Int,
): Dp {
    val bottomNavigationClearance = when (style) {
        ContinueWatchingSectionStyle.Card,
        ContinueWatchingSectionStyle.Wide -> NuvioTokens.Space.s24
        ContinueWatchingSectionStyle.Poster -> 0.dp
    }
    return continueWatchingSectionHeightEstimate(
        style = style,
        layout = layout,
        basePosterWidthDp = basePosterWidthDp,
    ) + bottomNavigationClearance
}

private fun continueWatchingProgressPercent(progressFraction: Float): Int =
    (progressFraction * 100f).roundToInt().coerceIn(1, 99)

@Composable
private fun localizedContinueWatchingMetaLine(item: ContinueWatchingItem): String =
    when {
        item.seasonNumber != null && item.episodeNumber != null ->
            stringResource(Res.string.compose_player_episode_code_full, item.seasonNumber, item.episodeNumber)
        item.isCloudLibraryItem() ->
            stringResource(Res.string.library_source_cloud)
        else ->
            stringResource(Res.string.media_movie)
    }

private fun ContinueWatchingItem.isCloudLibraryItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private fun ContinueWatchingItem.continueWatchingArtworkUrl(
    useEpisodeThumbnails: Boolean,
): String? = when {
    isNextUp && useEpisodeThumbnails -> firstNonBlank(
        episodeThumbnail,
        poster,
        background,
        imageUrl,
    )
    isNextUp -> firstNonBlank(
        poster,
        background,
        episodeThumbnail,
        imageUrl,
    )
    useEpisodeThumbnails -> firstNonBlank(
        episodeThumbnail,
        poster,
        background,
        imageUrl,
    )
    else -> firstNonBlank(
        poster,
        background,
        episodeThumbnail,
        imageUrl,
    )
}

private fun ContinueWatchingItem.continueWatchingPosterArtworkUrl(
    useEpisodeThumbnails: Boolean,
): String? {
    if (seasonNumber == null || episodeNumber == null) {
        return continueWatchingArtworkUrl(useEpisodeThumbnails)
    }

    val normalizedEpisodeThumbnail = episodeThumbnail?.trim()?.takeIf { it.isNotBlank() }
    val nonEpisodeImageUrl = imageUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != normalizedEpisodeThumbnail }

    return firstNonBlank(
        poster,
        background,
        nonEpisodeImageUrl,
        if (useEpisodeThumbnails) episodeThumbnail else null,
        imageUrl,
    )
}

private fun ContinueWatchingItem.continueWatchingCardArtworkUrl(
    useEpisodeThumbnails: Boolean,
    preferBackdropForNextUp: Boolean,
): String? = when {
    isNextUp && preferBackdropForNextUp -> firstNonBlank(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
    isNextUp && useEpisodeThumbnails -> firstNonBlank(
        episodeThumbnail,
        background,
        poster,
        imageUrl,
    )
    isNextUp -> firstNonBlank(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
    useEpisodeThumbnails -> firstNonBlank(
        episodeThumbnail,
        background,
        poster,
        imageUrl,
    )
    else -> firstNonBlank(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() }?.trim()

internal fun ContinueWatchingItem.shouldBlurContinueWatchingArtwork(
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    artworkUrl: String?,
): Boolean {
    if (!blurUnwatchedEpisodes || !useEpisodeThumbnails) return false
    val thumbnail = episodeThumbnail?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val artwork = artworkUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val isUnwatched = isNextUp || progressFraction < WatchProgressCompletionPercentThreshold / 100f
    return isUnwatched && artwork == thumbnail
}

@Composable
internal fun HomeContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    style: ContinueWatchingSectionStyle,
    dataSourceKey: WatchProgressSource,
    useEpisodeThumbnails: Boolean = true,
    blurNextUp: Boolean = false,
    modifier: Modifier = Modifier,
    title: String? = null,
    sectionPadding: Dp? = null,
    layout: ContinueWatchingLayout? = null,
    listState: LazyListState = rememberLazyListState(),
    onItemClick: ((ContinueWatchingItem) -> Unit)? = null,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    disintegrationRequest: DisintegrationRequest<String>? = null,
) {
    if (items.isEmpty()) return

    if (sectionPadding != null && layout != null) {
        HomeContinueWatchingSectionContent(
            items = items,
            dataSourceKey = dataSourceKey,
            style = style,
            useEpisodeThumbnails = useEpisodeThumbnails,
            blurNextUp = blurNextUp,
            modifier = modifier.fillMaxWidth(),
            title = title,
            sectionPadding = sectionPadding,
            layout = layout,
            listState = listState,
            onItemClick = onItemClick,
            onItemLongPress = onItemLongPress,
            disintegrationRequest = disintegrationRequest,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeContinueWatchingSectionContent(
                items = items,
                dataSourceKey = dataSourceKey,
                style = style,
                useEpisodeThumbnails = useEpisodeThumbnails,
                blurNextUp = blurNextUp,
                modifier = Modifier.fillMaxWidth(),
                title = title,
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                layout = rememberContinueWatchingLayout(maxWidth.value, rememberPosterCardStyleUiState()),
                listState = listState,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
                disintegrationRequest = disintegrationRequest,
            )
        }
    }
}

@Composable
private fun HomeContinueWatchingSectionContent(
    items: List<ContinueWatchingItem>,
    dataSourceKey: WatchProgressSource,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    modifier: Modifier,
    title: String?,
    sectionPadding: Dp,
    layout: ContinueWatchingLayout,
    listState: LazyListState,
    onItemClick: ((ContinueWatchingItem) -> Unit)?,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)?,
    disintegrationRequest: DisintegrationRequest<String>?,
) {
    key(dataSourceKey) {
        val disintegration = remember {
            ScopedDisintegrationTracker<WatchProgressSource, String, ContinueWatchingItem>(
                itemKey = ::continueWatchingItemKey,
            )
        }
        val displayEntries = disintegration.sync(dataSourceKey, items, disintegrationRequest)

        NuvioShelfSection(
            title = title ?: stringResource(Res.string.compose_settings_page_continue_watching),
            entries = displayEntries,
            modifier = modifier,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            itemSpacing = layout.itemGap,
            key = { entry -> entry.key },
            animatePlacement = true,
            state = listState,
        ) { entry ->
            val item = entry.item
            val onClick = if (entry.exiting) null else onItemClick?.let { { it(item) } }
            val onLongClick = if (entry.exiting) null else onItemLongPress?.let { { it(item) } }
            DisintegratingContainer(
                disintegrating = entry.exiting,
                onDisintegrated = { disintegration.onDisintegrated(entry.key) },
            ) {
                when (style) {
                    ContinueWatchingSectionStyle.Card -> ContinueWatchingCard(
                        item = item,
                        useEpisodeThumbnails = useEpisodeThumbnails,
                        blurNextUp = blurNextUp,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    ContinueWatchingSectionStyle.Wide -> ContinueWatchingWideCard(
                        item = item,
                        layout = layout,
                        useEpisodeThumbnails = useEpisodeThumbnails,
                        blurNextUp = blurNextUp,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    ContinueWatchingSectionStyle.Poster -> ContinueWatchingPosterCard(
                        item = item,
                        layout = layout,
                        useEpisodeThumbnails = useEpisodeThumbnails,
                        blurNextUp = blurNextUp,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingStylePreview(
    style: ContinueWatchingSectionStyle,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (style) {
            ContinueWatchingSectionStyle.Card -> CardStylePreview()
            ContinueWatchingSectionStyle.Wide -> WideCardPreview()
            ContinueWatchingSectionStyle.Poster -> PosterCardPreview()
        }
    }
}

@Composable
private fun CardStylePreview() {
    Box(
        modifier = Modifier
            .width(100.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.60f to MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                            1.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.90f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .width(26.dp)
                .height(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.80f)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.75f)),
            )
        }
        NuvioProgressBar(
            progress = 0.55f,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 5.dp, vertical = 3.dp)
                .fillMaxWidth(),
            height = 3.dp,
            trackColor = Color.Black.copy(alpha = 0.30f),
        )
    }
}

@Composable
private fun WideCardPreview() {
    Row(
        modifier = Modifier
            .width(100.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            )
            NuvioProgressBar(
                progress = 0.6f,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
            )
        }
    }
}

@Composable
private fun PosterCardPreview() {
    Column(
        modifier = Modifier
            .width(60.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                NuvioProgressBar(
                    progress = 0.45f,
                    modifier = Modifier.width(40.dp),
                    height = 4.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 6.dp, top = 1.dp)
                    .width(16.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            )
        }
    }
}

private data class ContinueWatchingLandscapeCardMetrics(
    val width: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val textGap: Dp,
    val badgeInset: Dp,
    val badgeHorizontalPadding: Dp,
    val badgeVerticalPadding: Dp,
    val progressHorizontalPadding: Dp,
    val progressBottomPadding: Dp,
    val progressHeight: Dp,
    val titleTextSize: TextUnit,
    val metaTextSize: TextUnit,
    val badgeTextSize: TextUnit,
)

private fun continueWatchingLandscapeCardMetrics(
    basePosterWidthDp: Int,
    cornerRadiusDp: Int,
): ContinueWatchingLandscapeCardMetrics {
    val width = continueWatchingLandscapeCardWidth(basePosterWidthDp)
    return when {
        basePosterWidthDp <= 108 -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 8.dp,
            textGap = 1.dp,
            badgeInset = 6.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
            progressHorizontalPadding = 8.dp,
            progressBottomPadding = 3.dp,
            progressHeight = 3.dp,
            titleTextSize = 12.sp,
            metaTextSize = 9.sp,
            badgeTextSize = 8.sp,
        )
        basePosterWidthDp <= 120 -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 9.dp,
            textGap = 1.dp,
            badgeInset = 6.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
            progressHorizontalPadding = 8.dp,
            progressBottomPadding = 3.dp,
            progressHeight = 3.dp,
            titleTextSize = 13.sp,
            metaTextSize = 10.sp,
            badgeTextSize = 9.sp,
        )
        else -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 10.dp,
            textGap = 2.dp,
            badgeInset = 7.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
            progressHorizontalPadding = 9.dp,
            progressBottomPadding = 4.dp,
            progressHeight = 3.dp,
            titleTextSize = 14.sp,
            metaTextSize = 10.sp,
            badgeTextSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val cardMetrics = remember(posterCardStyle.widthDp, posterCardStyle.cornerRadiusDp) {
        continueWatchingLandscapeCardMetrics(
            basePosterWidthDp = posterCardStyle.widthDp,
            cornerRadiusDp = posterCardStyle.cornerRadiusDp,
        )
    }
    val todayIsoDate = CurrentDateProvider.todayIsoDate()
    val airDateText = if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
        computeAirDateBadgeText(item.released, todayIsoDate)
    } else {
        null
    }
    val preferBackdropForNextUp = item.isNextUp && airDateText != null && !item.isReleaseAlert
    val imageUrl = item.continueWatchingCardArtworkUrl(
        useEpisodeThumbnails = useEpisodeThumbnails,
        preferBackdropForNextUp = preferBackdropForNextUp,
    )
    val shouldBlurArtwork = item.shouldBlurContinueWatchingArtwork(
        blurUnwatchedEpisodes = blurNextUp,
        useEpisodeThumbnails = useEpisodeThumbnails,
        artworkUrl = imageUrl,
    )
    val episodeCode = if (item.seasonNumber != null && item.episodeNumber != null) {
        stringResource(Res.string.streams_episode_badge, item.seasonNumber, item.episodeNumber)
    } else {
        null
    }
    val episodeTitle = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: airDateText
    val badgeText = continueWatchingCardBadgeText(item = item, airDateText = airDateText)
    val backgroundColor = MaterialTheme.colorScheme.background
    val badgeBackground = continueWatchingBadgeBackground(item)

    Box(
        modifier = Modifier
            .width(cardMetrics.width)
            .aspectRatio(PosterLandscapeAspectRatio)
            .clip(RoundedCornerShape(cardMetrics.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .nuvioCardDepth(
                shape = RoundedCornerShape(cardMetrics.cornerRadius),
                surface = NuvioCardDepthSurface.ContinueWatching,
            )
            .posterCardClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                zoomImageUrl = imageUrl,
                zoomCornerRadius = cardMetrics.cornerRadius,
            ),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = cloudLibraryDisplayArtworkUrl(imageUrl),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier)
                    .drawWithContent {
                        drawContent()

                        val startY = size.height * 0.45f
                        val gradient = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.60f to backgroundColor.copy(alpha = 0.70f),
                                1.0f to backgroundColor.copy(alpha = 0.95f),
                            ),
                            startY = startY,
                            endY = size.height,
                        )

                        drawRect(
                            brush = gradient,
                            topLeft = Offset(-2f, startY),
                            size = Size(size.width + 4f, (size.height - startY) + 4f),
                        )
                    },
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(cardMetrics.contentPadding),
            verticalArrangement = Arrangement.spacedBy(cardMetrics.textGap),
        ) {
            if (episodeCode != null) {
                Text(
                    text = episodeCode,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = cardMetrics.titleTextSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeTitle != null) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(cardMetrics.badgeInset)
                .clip(ContinueWatchingStatusBadgeShape)
                .background(badgeBackground)
                .padding(
                    horizontal = cardMetrics.badgeHorizontalPadding,
                    vertical = cardMetrics.badgeVerticalPadding,
                ),
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = cardMetrics.badgeTextSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 1,
            )
        }
        if (item.progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        horizontal = cardMetrics.progressHorizontalPadding,
                        vertical = cardMetrics.progressBottomPadding,
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .height(cardMetrics.progressHeight)
                    .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun continueWatchingCardBadgeText(
    item: ContinueWatchingItem,
    airDateText: String?,
): String {
    if (item.progressFraction > 0f) {
        if (item.durationMs <= 0L) {
            return stringResource(
                Res.string.home_continue_watching_watched,
                "${continueWatchingProgressPercent(item.progressFraction)}%",
            )
        }
        val effectivePositionMs = when {
            item.resumePositionMs > 0L -> item.resumePositionMs
            else -> (item.durationMs * item.progressFraction.coerceIn(0f, 1f)).toLong()
        }
        val remainingMinutes = ((item.durationMs - effectivePositionMs).coerceAtLeast(0L) / 60_000L)
            .coerceAtLeast(1L)
        val hours = remainingMinutes / 60L
        val minutes = remainingMinutes % 60L
        return if (hours > 0L) {
            stringResource(Res.string.home_continue_watching_hours_minutes_left, hours, minutes)
        } else {
            stringResource(Res.string.home_continue_watching_minutes_left, remainingMinutes)
        }
    }

    return when {
        item.isReleaseAlert && item.isNewSeasonRelease -> stringResource(Res.string.cw_new_season)
        item.isReleaseAlert -> stringResource(Res.string.cw_new_episode)
        airDateText != null -> airDateText
        else -> stringResource(Res.string.home_continue_watching_up_next)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingWideCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val cornerRadius = rememberPosterCardStyleUiState().cornerRadiusDp.dp
    Row(
        modifier = Modifier
            .width(layout.wideCardWidth)
            .height(layout.wideCardHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(cornerRadius),
            )
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
    ) {
        val artworkUrl = item.continueWatchingArtworkUrl(useEpisodeThumbnails)
        val shouldBlurArtwork = item.shouldBlurContinueWatchingArtwork(
            blurUnwatchedEpisodes = blurNextUp,
            useEpisodeThumbnails = useEpisodeThumbnails,
            artworkUrl = artworkUrl,
        )
        ArtworkPanel(
            imageUrl = artworkUrl,
            width = layout.widePosterStripWidth,
            blurred = shouldBlurArtwork,
            contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(layout.wideContentPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val isCompact = layout.wideCardWidth < 350.dp
            val wideMetaLine = localizedContinueWatchingMetaLine(item)
            val episodeTitle = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = layout.wideTitleSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                        val todayIsoDate = CurrentDateProvider.todayIsoDate()
                        val badgeText = when {
                            item.isReleaseAlert -> {
                                if (item.isNewSeasonRelease) stringResource(Res.string.cw_new_season)
                                else stringResource(Res.string.cw_new_episode)
                            }
                            else -> {
                                computeAirDateBadgeText(item.released, todayIsoDate)
                                    ?: stringResource(Res.string.home_continue_watching_up_next)
                            }
                        }
                        UpNextBadge(item = item, text = badgeText, compact = isCompact, textSize = layout.wideBadgeTextSize)
                    }
                }
                Text(
                    text = wideMetaLine,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = layout.wideMetaSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episodeTitle != null) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = layout.wideMetaSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (item.progressFraction > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NuvioProgressBar(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        height = layout.progressHeight,
                        trackColor = Color.White.copy(alpha = 0.10f),
                    )
                    Text(
                        text = stringResource(
                            Res.string.home_continue_watching_watched,
                            "${continueWatchingProgressPercent(item.progressFraction)}%",
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = layout.progressLabelSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingPosterCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val cornerRadius = rememberPosterCardStyleUiState().cornerRadiusDp.dp
    val imageUrl = item.continueWatchingPosterArtworkUrl(useEpisodeThumbnails)
    Column(
        modifier = Modifier.width(layout.posterCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.posterCardHeight)
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .nuvioCardDepth(
                    shape = RoundedCornerShape(cornerRadius),
                    surface = NuvioCardDepthSurface.ContinueWatching,
                )
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = imageUrl,
                    zoomCornerRadius = cornerRadius,
                ),
        ) {
            val shouldBlurArtwork = item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = blurNextUp,
                useEpisodeThumbnails = useEpisodeThumbnails,
                artworkUrl = imageUrl,
            )
            if (imageUrl != null) {
                AsyncImage(
                    model = cloudLibraryDisplayArtworkUrl(imageUrl),
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier),
                    contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
                )
            }
            if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    val todayIsoDate = CurrentDateProvider.todayIsoDate()
                    val badgeText = when {
                        item.isReleaseAlert -> {
                            if (item.isNewSeasonRelease) stringResource(Res.string.cw_new_season)
                            else stringResource(Res.string.cw_new_episode)
                        }
                        else -> {
                            computeAirDateBadgeText(item.released, todayIsoDate)
                                ?: stringResource(Res.string.home_continue_watching_up_next)
                        }
                    }
                    UpNextBadge(item = item, text = badgeText, compact = true, textSize = layout.posterBadgeTextSize)
                }
            }
            if (item.progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    NuvioProgressBar(
                        progress = item.progressFraction,
                        modifier = Modifier.width(layout.posterCardWidth - 32.dp),
                        height = layout.progressHeight,
                        trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(layout.posterTitleBlockHeight),
            ) {
                Text(
                    text = item.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = layout.posterTitleSize,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.seasonNumber != null && item.episodeNumber != null) {
                Text(
                    text = stringResource(
                        Res.string.streams_episode_badge,
                        item.seasonNumber,
                        item.episodeNumber,
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = layout.posterMetaSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtworkPanel(
    imageUrl: String?,
    width: Dp,
    blurred: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = cloudLibraryDisplayArtworkUrl(imageUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurred) Modifier.blur(18.dp) else Modifier),
                contentScale = contentScale,
            )
        }
    }
}

@Composable
private fun continueWatchingBadgeBackground(item: ContinueWatchingItem): Color = when {
    item.isNewSeasonRelease -> ContinueWatchingNewSeasonBadgeColor
    item.isReleaseAlert -> ContinueWatchingNewEpisodeBadgeColor
    else -> MaterialTheme.colorScheme.background.copy(alpha = 0.80f)
}

@Composable
private fun UpNextBadge(
    item: ContinueWatchingItem,
    text: String,
    compact: Boolean,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 4.dp else 12.dp))
            .background(continueWatchingBadgeBackground(item))
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

internal data class ContinueWatchingLayout(
    val itemGap: Dp,
    val wideCardWidth: Dp,
    val wideCardHeight: Dp,
    val widePosterStripWidth: Dp,
    val wideContentPadding: Dp,
    val posterCardWidth: Dp,
    val posterCardHeight: Dp,
    val progressHeight: Dp,
    val wideTitleSize: androidx.compose.ui.unit.TextUnit,
    val wideMetaSize: androidx.compose.ui.unit.TextUnit,
    val posterTitleSize: androidx.compose.ui.unit.TextUnit,
    val posterTitleBlockHeight: Dp,
    val posterMetaSize: androidx.compose.ui.unit.TextUnit,
    val progressLabelSize: androidx.compose.ui.unit.TextUnit,
    val wideBadgeTextSize: androidx.compose.ui.unit.TextUnit,
    val posterBadgeTextSize: androidx.compose.ui.unit.TextUnit,
)

internal fun rememberContinueWatchingLayout(
    maxWidthDp: Float,
    posterCardStyle: PosterCardStyleUiState = PosterCardStyleUiState(),
): ContinueWatchingLayout {
    return when {
        maxWidthDp >= 1440f -> ContinueWatchingLayout(
            itemGap = 20.dp,
            wideCardWidth = 400.dp,
            wideCardHeight = 160.dp,
            widePosterStripWidth = 100.dp,
            wideContentPadding = 16.dp,
            posterCardWidth = posterCardStyle.widthDp.dp,
            posterCardHeight = posterCardStyle.heightDp.dp,
            progressHeight = 6.dp,
            wideTitleSize = 20.sp,
            wideMetaSize = 16.sp,
            posterTitleSize = 16.sp,
            posterTitleBlockHeight = 40.dp,
            posterMetaSize = 14.sp,
            progressLabelSize = 14.sp,
            wideBadgeTextSize = 14.sp,
            posterBadgeTextSize = 12.sp,
        )
        maxWidthDp >= 1024f -> ContinueWatchingLayout(
            itemGap = 18.dp,
            wideCardWidth = 350.dp,
            wideCardHeight = 140.dp,
            widePosterStripWidth = 90.dp,
            wideContentPadding = 14.dp,
            posterCardWidth = posterCardStyle.widthDp.dp,
            posterCardHeight = posterCardStyle.heightDp.dp,
            progressHeight = 5.dp,
            wideTitleSize = 18.sp,
            wideMetaSize = 15.sp,
            posterTitleSize = 15.sp,
            posterTitleBlockHeight = 40.dp,
            posterMetaSize = 13.sp,
            progressLabelSize = 13.sp,
            wideBadgeTextSize = 13.sp,
            posterBadgeTextSize = 10.sp,
        )
        maxWidthDp >= 768f -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 320.dp,
            wideCardHeight = 130.dp,
            widePosterStripWidth = 85.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = posterCardStyle.widthDp.dp,
            posterCardHeight = posterCardStyle.heightDp.dp,
            progressHeight = 4.dp,
            wideTitleSize = 17.sp,
            wideMetaSize = 14.sp,
            posterTitleSize = 14.sp,
            posterTitleBlockHeight = 38.dp,
            posterMetaSize = 12.sp,
            progressLabelSize = 12.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
        else -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 280.dp,
            wideCardHeight = 120.dp,
            widePosterStripWidth = 80.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = posterCardStyle.widthDp.dp,
            posterCardHeight = posterCardStyle.heightDp.dp,
            progressHeight = 4.dp,
            wideTitleSize = 16.sp,
            wideMetaSize = 13.sp,
            posterTitleSize = 14.sp,
            posterTitleBlockHeight = 38.dp,
            posterMetaSize = 12.sp,
            progressLabelSize = 11.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
    }
}
