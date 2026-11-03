package com.nuvio.app.features.livetv

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioMediaActionOverlay
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioKeyboardFocusIndicator
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.live_tv_guide_earlier
import nuvio.composeapp.generated.resources.live_tv_guide_favorite_channel_count
import nuvio.composeapp.generated.resources.live_tv_guide_hour_range
import nuvio.composeapp.generated.resources.live_tv_guide_later
import nuvio.composeapp.generated.resources.live_tv_guide_loading
import nuvio.composeapp.generated.resources.live_tv_guide_next_24_hours
import nuvio.composeapp.generated.resources.live_tv_guide_no_favorites_body
import nuvio.composeapp.generated.resources.live_tv_guide_no_favorites_title
import nuvio.composeapp.generated.resources.live_tv_guide_now
import nuvio.composeapp.generated.resources.live_tv_guide_title
import nuvio.composeapp.generated.resources.live_tv_programme_detail_category
import nuvio.composeapp.generated.resources.live_tv_programme_detail_country
import nuvio.composeapp.generated.resources.live_tv_programme_detail_date
import nuvio.composeapp.generated.resources.live_tv_programme_detail_episode
import nuvio.composeapp.generated.resources.live_tv_programme_detail_language
import nuvio.composeapp.generated.resources.live_tv_programme_detail_rating
import nuvio.composeapp.generated.resources.live_tv_programme_detail_status
import nuvio.composeapp.generated.resources.live_tv_programme_flag_new
import nuvio.composeapp.generated.resources.live_tv_programme_flag_premiere
import nuvio.composeapp.generated.resources.live_tv_programme_flag_repeat
import nuvio.composeapp.generated.resources.live_tv_programme_open_live
import nuvio.composeapp.generated.resources.live_tv_programme_status_ended
import nuvio.composeapp.generated.resources.live_tv_programme_status_live
import nuvio.composeapp.generated.resources.live_tv_programme_status_upcoming
import nuvio.composeapp.generated.resources.live_tv_programme_watch_live
import org.jetbrains.compose.resources.stringResource

private const val GUIDE_MINUTE_MS = 60_000L
private const val GUIDE_HOUR_MS = 60L * GUIDE_MINUTE_MS
private const val GUIDE_PAGE_MS = 24L * GUIDE_HOUR_MS
private const val GUIDE_CLOCK_TICK_MS = 30_000L
private val GUIDE_CHANNEL_WIDTH = 136.dp
private val GUIDE_ROW_HEIGHT = 82.dp
private val GUIDE_TIME_HEADER_HEIGHT = 44.dp
private val GUIDE_DP_PER_MINUTE = 3.dp
private val GUIDE_HOUR_WIDTH = GUIDE_DP_PER_MINUTE * 60f
private val GUIDE_TIMELINE_WIDTH = GUIDE_HOUR_WIDTH * 24f

private data class SelectedGuideProgramme(
    val channel: LiveTvChannel,
    val programme: LiveTvProgramme,
)

@Composable
internal fun LiveTvFavoritesGuide(
    channels: List<LiveTvChannel>,
    favoriteUrls: Set<String>,
    programmesByChannel: Map<String, List<LiveTvProgramme>>,
    isEpgLoading: Boolean,
    onChannelClick: (LiveTvChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val favoriteChannels = remember(channels, favoriteUrls) {
        channels.filter { channel -> channel.streamUrl in favoriteUrls }
    }
    val favoriteProgrammes = remember(favoriteChannels, programmesByChannel) {
        favoriteChannels.flatMap { channel ->
            channel.tvgId?.let(programmesByChannel::get).orEmpty()
        }
    }

    var nowEpochMs by remember { mutableStateOf(LiveTvClock.nowEpochMs()) }
    var pageOffset by remember { mutableStateOf(0) }
    var basePageStartEpochMs by remember { mutableStateOf(nowEpochMs.floorToGuideHour()) }
    var selectedProgramme by remember { mutableStateOf<SelectedGuideProgramme?>(null) }
    val horizontalScroll = rememberScrollState()

    val latestProgrammeStopEpochMs = remember(favoriteProgrammes, nowEpochMs) {
        favoriteProgrammes.maxOfOrNull(LiveTvProgramme::stopEpochMs) ?: nowEpochMs
    }
    val maxPageOffset = remember(latestProgrammeStopEpochMs, basePageStartEpochMs) {
        if (latestProgrammeStopEpochMs <= basePageStartEpochMs) {
            0
        } else {
            ((latestProgrammeStopEpochMs - basePageStartEpochMs - 1L) / GUIDE_PAGE_MS)
                .toInt()
                .coerceAtLeast(0)
        }
    }
    val pageStartEpochMs = basePageStartEpochMs + (pageOffset * GUIDE_PAGE_MS)
    val pageEndEpochMs = pageStartEpochMs + GUIDE_PAGE_MS

    LaunchedEffect(Unit) {
        while (true) {
            delay(GUIDE_CLOCK_TICK_MS)
            nowEpochMs = LiveTvClock.nowEpochMs()
        }
    }
    LaunchedEffect(nowEpochMs, pageOffset) {
        if (pageOffset == 0) {
            val currentHour = nowEpochMs.floorToGuideHour()
            if (basePageStartEpochMs != currentHour) basePageStartEpochMs = currentHour
        }
    }
    LaunchedEffect(maxPageOffset) {
        if (pageOffset > maxPageOffset) pageOffset = maxPageOffset
    }
    LaunchedEffect(pageOffset, basePageStartEpochMs) {
        horizontalScroll.scrollTo(0)
    }

    PlatformBackHandler(enabled = true, onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .padding(horizontal = 16.dp),
    ) {
        NuvioScreenHeader(
            title = stringResource(Res.string.live_tv_guide_title),
            includeStatusBarPadding = true,
            onBack = onBack,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (pageOffset == 0) {
                        stringResource(Res.string.live_tv_guide_next_24_hours)
                    } else {
                        stringResource(
                            Res.string.live_tv_guide_hour_range,
                            pageOffset * 24,
                            (pageOffset + 1) * 24,
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        Res.string.live_tv_guide_favorite_channel_count,
                        favoriteChannels.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = pageOffset > 0,
                    onClick = { pageOffset-- },
                    modifier = Modifier.nuvioKeyboardFocusIndicator(
                        tokens.shapes.avatar,
                        enabled = pageOffset > 0,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = stringResource(Res.string.live_tv_guide_earlier),
                        tint = if (pageOffset > 0) tokens.colors.textPrimary else tokens.colors.textDisabled,
                    )
                }
                Surface(
                    onClick = { pageOffset = 0 },
                    enabled = pageOffset != 0,
                    modifier = Modifier.nuvioKeyboardFocusIndicator(
                        tokens.shapes.chip,
                        enabled = pageOffset != 0,
                    ),
                    color = tokens.colors.overlaySelected,
                    shape = tokens.shapes.chip,
                ) {
                    Text(
                        text = stringResource(Res.string.live_tv_guide_now),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pageOffset == 0) tokens.colors.textMuted else tokens.colors.textPrimary,
                    )
                }
                IconButton(
                    enabled = pageOffset < maxPageOffset,
                    onClick = { pageOffset++ },
                    modifier = Modifier.nuvioKeyboardFocusIndicator(
                        tokens.shapes.avatar,
                        enabled = pageOffset < maxPageOffset,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(Res.string.live_tv_guide_later),
                        tint = if (pageOffset < maxPageOffset) {
                            tokens.colors.textPrimary
                        } else {
                            tokens.colors.textDisabled
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            favoriteChannels.isEmpty() -> GuideMessage(
                title = stringResource(Res.string.live_tv_guide_no_favorites_title),
                body = stringResource(Res.string.live_tv_guide_no_favorites_body),
            )

            isEpgLoading && programmesByChannel.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = tokens.colors.accent)
                    Text(
                        text = stringResource(Res.string.live_tv_guide_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }

            else -> GuideGrid(
                channels = favoriteChannels,
                programmesByChannel = programmesByChannel,
                pageStartEpochMs = pageStartEpochMs,
                pageEndEpochMs = pageEndEpochMs,
                nowEpochMs = nowEpochMs,
                horizontalScroll = horizontalScroll,
                onChannelClick = onChannelClick,
                onProgrammeClick = { channel, programme ->
                    selectedProgramme = SelectedGuideProgramme(channel, programme)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    selectedProgramme?.let { selection ->
        LiveTvProgrammeDetailsOverlay(
            channel = selection.channel,
            programme = selection.programme,
            nowEpochMs = nowEpochMs,
            onDismiss = { selectedProgramme = null },
            onPlayLive = {
                selectedProgramme = null
                onChannelClick(selection.channel)
            },
        )
    }
}

@Composable
private fun GuideGrid(
    channels: List<LiveTvChannel>,
    programmesByChannel: Map<String, List<LiveTvProgramme>>,
    pageStartEpochMs: Long,
    pageEndEpochMs: Long,
    nowEpochMs: Long,
    horizontalScroll: ScrollState,
    onChannelClick: (LiveTvChannel) -> Unit,
    onProgrammeClick: (LiveTvChannel, LiveTvProgramme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GUIDE_TIME_HEADER_HEIGHT),
        ) {
            GuideChannelHeaderCell()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .horizontalScroll(horizontalScroll),
            ) {
                GuideTimeHeader(pageStartEpochMs)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(items = channels, key = LiveTvChannel::id) { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GUIDE_ROW_HEIGHT),
                ) {
                    GuideChannelCell(channel = channel, onClick = { onChannelClick(channel) })
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .horizontalScroll(horizontalScroll),
                    ) {
                        GuideProgrammeRow(
                            channel = channel,
                            programmes = channel.tvgId?.let(programmesByChannel::get).orEmpty(),
                            pageStartEpochMs = pageStartEpochMs,
                            pageEndEpochMs = pageEndEpochMs,
                            nowEpochMs = nowEpochMs,
                            onProgrammeClick = onProgrammeClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideMessage(title: String, body: String) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = tokens.colors.accent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun GuideChannelHeaderCell() {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(GUIDE_CHANNEL_WIDTH)
            .fillMaxHeight()
            .background(tokens.colors.background)
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "CHANNEL",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GuideChannelCell(channel: LiveTvChannel, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier
            .width(GUIDE_CHANNEL_WIDTH)
            .fillMaxHeight()
            .padding(end = 8.dp, bottom = 4.dp)
            .nuvioKeyboardFocusIndicator(shape),
        onClick = onClick,
        color = tokens.colors.surface,
        shape = shape,
        border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tokens.colors.overlaySelected),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tokens.colors.accent,
                    )
                }
            }
            Text(
                text = channel.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GuideTimeHeader(pageStartEpochMs: Long) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(GUIDE_TIMELINE_WIDTH)
            .height(GUIDE_TIME_HEADER_HEIGHT),
    ) {
        repeat(24) { hourIndex ->
            Box(
                modifier = Modifier
                    .offset(x = GUIDE_HOUR_WIDTH * hourIndex.toFloat())
                    .width(GUIDE_HOUR_WIDTH)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .width(NuvioTokens.Border.thin)
                        .fillMaxHeight()
                        .background(tokens.colors.borderSubtle),
                )
                Text(
                    text = LiveTvClock.formatLocalTime(pageStartEpochMs + (hourIndex * GUIDE_HOUR_MS)),
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun GuideProgrammeRow(
    channel: LiveTvChannel,
    programmes: List<LiveTvProgramme>,
    pageStartEpochMs: Long,
    pageEndEpochMs: Long,
    nowEpochMs: Long,
    onProgrammeClick: (LiveTvChannel, LiveTvProgramme) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val visibleProgrammes = remember(programmes, pageStartEpochMs, pageEndEpochMs) {
        programmes.filter { programme ->
            programme.stopEpochMs > pageStartEpochMs && programme.startEpochMs < pageEndEpochMs
        }
    }

    Box(
        modifier = Modifier
            .width(GUIDE_TIMELINE_WIDTH)
            .height(GUIDE_ROW_HEIGHT),
    ) {
        repeat(24) { hourIndex ->
            Box(
                modifier = Modifier
                    .offset(x = GUIDE_HOUR_WIDTH * hourIndex.toFloat())
                    .width(NuvioTokens.Border.thin)
                    .fillMaxHeight()
                    .background(tokens.colors.borderSubtle),
            )
        }

        if (visibleProgrammes.isEmpty()) {
            Text(
                text = "No EPG data",
                modifier = Modifier.padding(start = 12.dp, top = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        visibleProgrammes.forEach { programme ->
            val visibleStart = maxOf(programme.startEpochMs, pageStartEpochMs)
            val visibleStop = minOf(programme.stopEpochMs, pageEndEpochMs)
            val x = durationToGuideDp(visibleStart - pageStartEpochMs)
            val width = durationToGuideDp(visibleStop - visibleStart).coerceAtLeast(10.dp)
            val isCurrent = nowEpochMs in programme.startEpochMs until programme.stopEpochMs
            val shape = RoundedCornerShape(10.dp)

            Surface(
                modifier = Modifier
                    .offset(x = x, y = 4.dp)
                    .width(width)
                    .height(GUIDE_ROW_HEIGHT - 8.dp)
                    .padding(end = 4.dp)
                    .nuvioKeyboardFocusIndicator(shape),
                onClick = { onProgrammeClick(channel, programme) },
                color = if (isCurrent) tokens.colors.overlaySelected else tokens.colors.surfaceCard,
                shape = shape,
                border = BorderStroke(
                    width = if (isCurrent) NuvioTokens.Border.medium else NuvioTokens.Border.thin,
                    color = if (isCurrent) tokens.colors.accent else tokens.colors.borderSubtle,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = if (width >= 90.dp) 10.dp else 5.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = programme.title,
                        style = if (width >= 90.dp) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = tokens.colors.textPrimary,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = if (width >= 72.dp) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (width >= 72.dp) {
                        Text(
                            text = "${LiveTvClock.formatLocalTime(programme.startEpochMs)} - " +
                                LiveTvClock.formatLocalTime(programme.stopEpochMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) tokens.colors.accent else tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (nowEpochMs in pageStartEpochMs until pageEndEpochMs) {
            Box(
                modifier = Modifier
                    .offset(x = durationToGuideDp(nowEpochMs - pageStartEpochMs))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(tokens.colors.accent),
            )
        }
    }
}

@Composable
private fun LiveTvProgrammeDetailsOverlay(
    channel: LiveTvChannel,
    programme: LiveTvProgramme,
    nowEpochMs: Long,
    onDismiss: () -> Unit,
    onPlayLive: () -> Unit,
) {
    val isCurrent = nowEpochMs in programme.startEpochMs until programme.stopEpochMs
    val isUpcoming = nowEpochMs < programme.startEpochMs
    val liveRed = Color(0xFFFF334F)
    val pulseTransition = rememberInfiniteTransition(label = "liveTvPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveTvPulseAlpha",
    )
    val status = when {
        isCurrent -> stringResource(Res.string.live_tv_programme_status_live)
        isUpcoming -> stringResource(Res.string.live_tv_programme_status_upcoming)
        else -> stringResource(Res.string.live_tv_programme_status_ended)
    }
    val newLabel = stringResource(Res.string.live_tv_programme_flag_new)
    val premiereLabel = stringResource(Res.string.live_tv_programme_flag_premiere)
    val repeatLabel = stringResource(Res.string.live_tv_programme_flag_repeat)
    val flags = buildList {
        if (programme.isNew) add(newLabel)
        if (programme.isPremiere) add(premiereLabel)
        if (programme.isPreviouslyShown) add(repeatLabel)
    }.joinToString(" · ")

    NuvioMediaActionOverlay(
        artworkUrl = programme.iconUrl ?: channel.logoUrl,
        contentDescription = programme.title,
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp),
            color = Color(0xFF151519).copy(alpha = 0.97f),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            shadowElevation = 22.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color.White.copy(alpha = 0.09f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!channel.logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = channel.logoUrl,
                                    contentDescription = channel.name,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(5.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Tv,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                            }
                        }
                        Column {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            channel.group.takeIf(String::isNotBlank)?.let { group ->
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.58f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Surface(
                        color = if (isCurrent) liveRed.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer {
                                            alpha = pulse
                                            scaleX = pulse
                                            scaleY = pulse
                                        }
                                        .background(liveRed, RoundedCornerShape(999.dp)),
                                )
                            }
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) liveRed else Color.White.copy(alpha = 0.72f),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                programme.iconUrl?.takeIf(String::isNotBlank)?.let { thumbnailUrl ->
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = programme.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.06f)),
                        contentScale = ContentScale.Crop,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    programme.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                    Text(
                        text = "${LiveTvClock.formatLocalTime(programme.startEpochMs)} - " +
                            LiveTvClock.formatLocalTime(programme.stopEpochMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) liveRed else Color.White.copy(alpha = 0.64f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                programme.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ProgrammeDetailLine(
                        stringResource(Res.string.live_tv_programme_detail_category),
                        programme.categories.joinToString(" · ").takeIf(String::isNotBlank),
                    )
                    ProgrammeDetailLine(stringResource(Res.string.live_tv_programme_detail_episode), programme.episode)
                    ProgrammeDetailLine(stringResource(Res.string.live_tv_programme_detail_rating), programme.rating)
                    ProgrammeDetailLine(stringResource(Res.string.live_tv_programme_detail_date), programme.date)
                    ProgrammeDetailLine(stringResource(Res.string.live_tv_programme_detail_country), programme.country)
                    ProgrammeDetailLine(stringResource(Res.string.live_tv_programme_detail_language), programme.language)
                    ProgrammeDetailLine(
                        stringResource(Res.string.live_tv_programme_detail_status),
                        flags.takeIf(String::isNotBlank),
                    )
                    programme.credits
                        .groupBy(LiveTvProgrammeCredit::role)
                        .forEach { (role, credits) ->
                            ProgrammeDetailLine(
                                role.replaceFirstChar { it.uppercase() },
                                credits.joinToString { it.name },
                            )
                        }
                }

                Button(
                    onClick = onPlayLive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer {
                            val scale = if (isCurrent) 0.99f + (pulse * 0.01f) else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .nuvioKeyboardFocusIndicator(RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = liveRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isCurrent) {
                            stringResource(Res.string.live_tv_programme_watch_live)
                        } else {
                            stringResource(Res.string.live_tv_programme_open_live)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeDetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(82.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.46f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.84f),
        )
    }
}

private fun Long.floorToGuideHour(): Long = this - (this % GUIDE_HOUR_MS)

private fun durationToGuideDp(durationMs: Long): Dp =
    ((durationMs.toDouble() / GUIDE_MINUTE_MS.toDouble()) * GUIDE_DP_PER_MINUTE.value).dp
