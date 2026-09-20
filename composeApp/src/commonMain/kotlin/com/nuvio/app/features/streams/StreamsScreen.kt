package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadLocationManager
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.navigation.LocalUseNativeNavigation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// ---------------------------------------------------------------------------
// Streams Screen
// ---------------------------------------------------------------------------

@Composable
fun StreamsScreen(
    type: String,
    videoId: String,
    parentMetaId: String,
    parentMetaType: String,
    title: String,
    logo: String? = null,
    poster: String? = null,
    background: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
    episodeThumbnail: String? = null,
    resumePositionMs: Long? = null,
    resumeProgressFraction: Float? = null,
    manualSelection: Boolean = false,
    startFromBeginning: Boolean = false,
    showLoadingScreen: Boolean = false,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit = { _, _, _ -> },
    onStreamActionOpen: (
        stream: StreamItem,
        openExternally: Boolean,
        resumePositionMs: Long?,
        resumeProgressFraction: Float?,
    ) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useNativeNavigation = LocalUseNativeNavigation.current
    val uiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val streamDisplaySettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val debridSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaScreenSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    remember {
        DownloadsRepository.ensureLoaded()
    }
    val isEpisode = seasonNumber != null && episodeNumber != null
    val clipboardManager = LocalClipboardManager.current
    val streamLinkCopiedText = stringResource(Res.string.streams_link_copied)
    val noDirectStreamLinkText = stringResource(Res.string.streams_no_direct_link)
    val downloadLocationPromptCancelledText = stringResource(Res.string.downloads_location_prompt_cancelled)
    var streamActionsTarget by remember(videoId) { mutableStateOf<StreamItem?>(null) }
    val downloadScope = rememberCoroutineScope()
    var preferredFilterApplied by remember(videoId) { mutableStateOf(false) }
    val episodeProgress = watchProgressUiState.progressForVideo(
        videoId = videoId,
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    val storedProgress = if (startFromBeginning) {
        null
    } else {
        episodeProgress
    }
    val resumeState = resolveStreamResumeState(
        progress = episodeProgress,
        initialPositionMs = resumePositionMs,
        initialProgressFraction = resumeProgressFraction,
        startFromBeginning = startFromBeginning,
    )
    val effectiveResumePositionMs = resumeState.positionMs
    val effectiveResumeProgressFraction = resumeState.progressFraction

    LaunchedEffect(type, videoId, seasonNumber, episodeNumber, manualSelection) {
        StreamsRepository.load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = seasonNumber,
            episode = episodeNumber,
            manualSelection = manualSelection,
        )
    }

    LaunchedEffect(uiState.groups, storedProgress?.providerAddonId, preferredFilterApplied) {
        if (preferredFilterApplied) return@LaunchedEffect
        val preferredAddonId = storedProgress?.providerAddonId ?: return@LaunchedEffect
        if (uiState.groups.any { it.addonId == preferredAddonId }) {
            StreamsRepository.selectFilter(preferredAddonId)
            preferredFilterApplied = true
        }
    }

    val heroArtwork = if (isEpisode) {
        episodeThumbnail ?: background ?: poster
    } else {
        background ?: poster
    }
    val isEpisodeWatched = episodeProgress?.isEffectivelyCompleted == true || watchedItemKeys(
        type = parentMetaType,
        id = parentMetaId,
        season = seasonNumber,
        episode = episodeNumber,
    ).any(watchedUiState.watchedKeys::contains)
    val blurEpisodeThumbnail = metaScreenSettings.blurUnwatchedEpisodes &&
        isEpisode &&
        !isEpisodeWatched &&
        !episodeThumbnail.isNullOrBlank()
    val reloadStreams: () -> Unit = {
        StreamsRepository.reload(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = seasonNumber,
            episode = episodeNumber,
            manualSelection = manualSelection,
        )
    }

    if (showLoadingScreen) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isTabletLayout = maxWidth >= 768.dp

        if (isTabletLayout) {
            TabletStreamsLayout(
                isEpisode = isEpisode,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                episodeThumbnail = episodeThumbnail,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                uiState = uiState,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks && !debridSettings.hasCustomStreamFormatting,
                resumePositionMs = effectiveResumePositionMs,
                resumeProgressFraction = effectiveResumeProgressFraction,
                onStreamSelected = { stream, positionMs, progressFraction ->
                    onStreamSelected(stream, positionMs, progressFraction)
                },
                onStreamLongPress = { stream -> streamActionsTarget = stream },
                onRefresh = reloadStreams,
            )
        } else {
            MobileStreamsLayout(
                isEpisode = isEpisode,
                backgroundMode = streamDisplaySettings.backgroundMode,
                title = title,
                logo = logo,
                heroArtwork = heroArtwork,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                blurEpisodeThumbnail = blurEpisodeThumbnail,
                uiState = uiState,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks && !debridSettings.hasCustomStreamFormatting,
                resumePositionMs = effectiveResumePositionMs,
                resumeProgressFraction = effectiveResumeProgressFraction,
                onStreamSelected = { stream, positionMs, progressFraction ->
                    onStreamSelected(stream, positionMs, progressFraction)
                },
                onStreamLongPress = { stream -> streamActionsTarget = stream },
                onRefresh = reloadStreams,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 12.dp, top = if (useNativeNavigation) 52.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp),
                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onBackground,
            )
        }


        StreamActionsSheet(
            stream = streamActionsTarget,
            externalPlayerEnabled = playerSettings.externalPlayerEnabled,
            onDismiss = { streamActionsTarget = null },
            onCopyLink = { stream ->
                val directUrl = stream.playableDirectUrl ?: stream.externalOpenUrl
                if (!directUrl.isNullOrBlank()) {
                    clipboardManager.setText(AnnotatedString(directUrl))
                    NuvioToastController.show(streamLinkCopiedText)
                } else if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                    downloadScope.launch {
                        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                            stream = stream,
                            season = seasonNumber,
                            episode = episodeNumber,
                        )
                        when (resolved) {
                            is DirectDebridPlayableResult.Success -> {
                                val resolvedUrl = resolved.stream.playableDirectUrl
                                if (!resolvedUrl.isNullOrBlank()) {
                                    clipboardManager.setText(AnnotatedString(resolvedUrl))
                                    NuvioToastController.show(streamLinkCopiedText)
                                } else {
                                    NuvioToastController.show(noDirectStreamLinkText)
                                }
                            }
                            else -> {
                                val message = resolved.toastMessage()
                                if (message != null) {
                                    NuvioToastController.show(message)
                                }
                            }
                        }
                    }
                } else {
                    NuvioToastController.show(noDirectStreamLinkText)
                }
            },
            onDownload = { stream ->
                downloadScope.launch {
                    val enqueue: (StreamItem) -> DownloadEnqueueResult = { targetStream ->
                        DownloadsRepository.enqueueFromStream(
                            contentType = type,
                            videoId = videoId,
                            parentMetaId = parentMetaId,
                            parentMetaType = parentMetaType,
                            title = title,
                            logo = logo,
                            poster = poster,
                            background = background,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            episodeTitle = episodeTitle,
                            episodeThumbnail = episodeThumbnail,
                            stream = targetStream,
                        )
                    }
                    suspend fun enqueueWithLocationPrompt(targetStream: StreamItem): DownloadEnqueueResult? {
                        val firstAttempt = enqueue(targetStream)
                        if (firstAttempt != DownloadEnqueueResult.MissingLocation) return firstAttempt
                        if (!DownloadLocationManager.ensureLocationSelectedOrPrompt()) {
                            NuvioToastController.show(downloadLocationPromptCancelledText)
                            return null
                        }
                        return enqueue(targetStream)
                    }

                    if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                            stream = stream,
                            season = seasonNumber,
                            episode = episodeNumber,
                        )
                        when (resolved) {
                            is DirectDebridPlayableResult.Success -> {
                                enqueueWithLocationPrompt(resolved.stream)?.let { result ->
                                    NuvioToastController.show(result.toastMessage())
                                }
                            }
                            else -> {
                                val message = resolved.toastMessage()
                                if (message != null) {
                                    NuvioToastController.show(message)
                                }
                            }
                        }
                    } else {
                        enqueueWithLocationPrompt(stream)?.let { result ->
                            NuvioToastController.show(result.toastMessage())
                        }
                    }
                }
            },
            onOpen = { stream, openExternally ->
                onStreamActionOpen(
                    stream,
                    openExternally,
                    effectiveResumePositionMs,
                    effectiveResumeProgressFraction,
                )
            },
        )
    }
}

@Composable
private fun MobileStreamsLayout(
    isEpisode: Boolean,
    backgroundMode: StreamBackgroundMode,
    title: String,
    logo: String?,
    heroArtwork: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    blurEpisodeThumbnail: Boolean,
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (backgroundMode == StreamBackgroundMode.Cinematic && heroArtwork != null) {
            AsyncImage(
                model = heroArtwork,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(22.dp),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isEpisode) 0.9f else 0.82f)),
            )
        }

        val streamBlendColor = MaterialTheme.colorScheme.background

        Column(modifier = Modifier.fillMaxSize()) {
            if (isEpisode && seasonNumber != null && episodeNumber != null) {
                EpisodeHeroBlock(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle ?: title,
                    thumbnail = heroArtwork,
                    blurred = blurEpisodeThumbnail,
                    showTitle = title,
                )
            } else {
                MovieHeroBlock(
                    title = title,
                    logo = logo,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (isEpisode && backgroundMode == StreamBackgroundMode.Cinematic) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        streamBlendColor.copy(alpha = 0.98f),
                                        streamBlendColor.copy(alpha = 0.84f),
                                        streamBlendColor.copy(alpha = 0.52f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if ((resumePositionMs != null && resumePositionMs > 0L) || (resumeProgressFraction != null && resumeProgressFraction > 0f)) {
                        ResumeBanner(
                            positionMs = resumePositionMs,
                            progressFraction = resumeProgressFraction,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    ProviderFilterRow(
                        groups = uiState.groups,
                        selectedFilter = uiState.selectedFilter,
                        onFilterSelected = { addonId -> StreamsRepository.selectFilter(addonId) },
                        onRefresh = onRefresh,
                    )

                    StreamList(
                        uiState = uiState,
                        debridEnabled = debridEnabled,
                        appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                        onStreamSelected = onStreamSelected,
                        onStreamLongPress = onStreamLongPress,
                        resumePositionMs = resumePositionMs,
                        resumeProgressFraction = resumeProgressFraction,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal data class StreamResumeState(
    val positionMs: Long? = null,
    val progressFraction: Float? = null,
)

internal fun resolveStreamResumeState(
    progress: WatchProgressEntry?,
    initialPositionMs: Long?,
    initialProgressFraction: Float?,
    startFromBeginning: Boolean,
): StreamResumeState {
    if (startFromBeginning || progress?.isResumable == false) return StreamResumeState()
    val fraction = (if (progress != null) progress.progressPercent?.div(100f) else initialProgressFraction)
        ?.takeIf { it > 0f }?.coerceIn(0f, 1f)
    val position = if (fraction != null) null
        else (progress?.lastPositionMs ?: initialPositionMs)?.takeIf { it > 0L }
    return StreamResumeState(positionMs = position, progressFraction = fraction)
}

@Composable
internal fun ResumeBanner(
    positionMs: Long?,
    progressFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val resumeText = when {
        progressFraction != null && progressFraction > 0f -> stringResource(
            Res.string.streams_resume_from_percent,
            (progressFraction * 100f).roundToInt(),
        )
        positionMs != null && positionMs > 0L -> stringResource(
            Res.string.streams_resume_from_time,
            positionMs.toPlaybackClock(),
        )
        else -> null
    } ?: return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = resumeText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Movie Hero
// ---------------------------------------------------------------------------

@Composable
private fun MovieHeroBlock(
    title: String,
    logo: String?,
    modifier: Modifier = Modifier,
) {
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                modifier = Modifier
                    .height(80.dp)
                    .fillMaxWidth(0.85f),
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Episode Hero
// ---------------------------------------------------------------------------

@Composable
private fun EpisodeHeroBlock(
    seasonNumber: Int,
    episodeNumber: Int,
    episodeTitle: String,
    thumbnail: String?,
    blurred: Boolean,
    showTitle: String,
    modifier: Modifier = Modifier,
) {
    val heroBlendColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        // Thumbnail image
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurred) Modifier.blur(18.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.1f)),
        )

        // Gradient overlay bottom-up
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            0.8f to Color.Black.copy(alpha = 0.42f),
                            0.93f to heroBlendColor.copy(alpha = 0.84f),
                            1.0f to heroBlendColor,
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        // Safe-area push-down for status bar, then content pinned to bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Episode label
            Text(
                text = stringResource(Res.string.streams_episode_badge, seasonNumber, episodeNumber),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Episode title
            Text(
                text = episodeTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Show title
            Text(
                text = showTitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Provider Filter Row
// ---------------------------------------------------------------------------

@Composable
internal fun ProviderFilterRow(
    groups: List<AddonStreamGroup>,
    selectedFilter: String?,
    onFilterSelected: (String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addonGroups = groups.filter { it.streams.isNotEmpty() || it.isLoading }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            icon = Icons.Rounded.Refresh,
            contentDescription = stringResource(Res.string.streams_refresh),
            isSelected = false,
            onClick = onRefresh,
        )
        // "All" chip
        FilterChip(
            label = stringResource(Res.string.collections_tab_all),
            isSelected = selectedFilter == null,
            onClick = { onFilterSelected(null) },
        )
        addonGroups.forEach { group ->
            FilterChip(
                label = group.addonName,
                isSelected = selectedFilter == group.addonId,
                onClick = { onFilterSelected(group.addonId) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "filter_chip_scale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_content",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(36.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        letterSpacing = 0.1.sp,
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stream List
// ---------------------------------------------------------------------------

@Composable
internal fun StreamList(
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    modifier: Modifier = Modifier,
) {
    val filteredGroups = uiState.filteredGroups
    val hasGroups = filteredGroups.isNotEmpty()
    val hasAnyStreams = filteredGroups.any { it.streams.isNotEmpty() }
    val anyLoading = filteredGroups.any { it.isLoading }
    val torrentNotSupportedText = stringResource(Res.string.streams_torrent_not_supported)
    val fetchingText = stringResource(Res.string.streams_fetching)
    val findingStreamsText = stringResource(Res.string.streams_finding_streams)
    val checkingMoreAddonsText = stringResource(Res.string.streams_checking_more_addons)
    val formatStreamSize = rememberStreamSizeLabelFormat()
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalStreamSizeLabelFormat provides formatStreamSize) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when {
            hasGroups && anyLoading && !hasAnyStreams -> {
                item {
                    LoadingStateBlock(findingStreamsText = findingStreamsText)
                }
            }

            !hasAnyStreams && !uiState.isAnyLoading -> {
                item {
                    EmptyStateBlock(reason = uiState.emptyStateReason)
                }
            }

            else -> {
                filteredGroups.forEachIndexed { groupIndex, group ->
                    streamSection(
                        sectionKey = streamSectionRenderKey(groupIndex = groupIndex, group = group),
                        group = group,
                        showHeader = uiState.selectedFilter == null,
                        debridEnabled = debridEnabled,
                        appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                        showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                        showAddonLogo = streamBadgeSettings.showAddonLogo,
                        badgePlacement = streamBadgeSettings.badgePlacement,
                        torrentNotSupportedText = torrentNotSupportedText,
                        fetchingText = fetchingText,
                        onStreamSelected = onStreamSelected,
                        onStreamLongPress = onStreamLongPress,
                        resumePositionMs = resumePositionMs,
                        resumeProgressFraction = resumeProgressFraction,
                    )
                }
                if (anyLoading) {
                    item {
                        FooterLoadingBlock(checkingMoreAddonsText = checkingMoreAddonsText)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(nuvioSafeBottomPadding(80.dp)))
                }
            }
        }
    }
    }
}

private fun LazyListScope.streamSection(
    sectionKey: String,
    group: AddonStreamGroup,
    showHeader: Boolean,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    torrentNotSupportedText: String,
    fetchingText: String,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
) {
    if (group.streams.isEmpty() && !group.isLoading) return

    if (showHeader) {
        item(key = "header_$sectionKey") {
            StreamSectionHeader(
                addonName = group.addonName,
                isLoading = group.isLoading,
                fetchingText = fetchingText,
            )
        }
    }

    val streamsBySource = group.streams.groupBy { stream ->
        stream.sourceName?.takeIf { it.isNotBlank() } ?: stream.addonName
    }
    val sortedSources = streamsBySource.keys.sortedBy { it.lowercase() }
    val showSourceHeaders = sortedSources.size > 1

    sortedSources.forEachIndexed { sourceIndex, sourceName ->
        val sourceStreams = streamsBySource[sourceName].orEmpty()
        if (showSourceHeaders) {
            item(key = "source_${sectionKey}_$sourceIndex") {
                StreamSourceHeader(sourceName = sourceName)
            }
        }

        itemsIndexed(
            items = sourceStreams,
            key = { index, stream ->
                streamCardRenderKey(
                    sectionKey = sectionKey,
                    sourceIndex = sourceIndex,
                    itemIndex = index,
                    stream = stream,
                )
            },
        ) { _, stream ->
            val isSelectable = stream.isSelectableForPlayback(debridEnabled)
            val isUnsupportedTorrentStream =
                stream.needsLocalDebridResolve &&
                    !AppFeaturePolicy.p2pEnabled &&
                    !(debridEnabled && stream.isAddonDebridCandidate)
            StreamCard(
                stream = stream,
                enabled = isSelectable || isUnsupportedTorrentStream,
                appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                showFileSizeBadges = showFileSizeBadges,
                showAddonLogo = showAddonLogo,
                badgePlacement = badgePlacement,
                onClick = {
                    if (isSelectable) {
                        onStreamSelected(stream, resumePositionMs, resumeProgressFraction)
                    } else if (isUnsupportedTorrentStream) {
                        NuvioToastController.show(torrentNotSupportedText)
                    }
                },
                onLongClick = {
                    if (stream.playableDirectUrl != null || stream.shouldOpenExternally || stream.isAddonDebridCandidate) {
                        onStreamLongPress(stream)
                    }
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

internal fun streamSectionRenderKey(
    groupIndex: Int,
    group: AddonStreamGroup,
): String = "$groupIndex:${group.addonId}"

internal fun streamCardRenderKey(
    sectionKey: String,
    sourceIndex: Int,
    itemIndex: Int,
    stream: StreamItem,
): String = buildString {
    append(sectionKey)
    append(':')
    append(sourceIndex)
    append(':')
    append(itemIndex)
    append(':')
    append(stream.url ?: stream.infoHash ?: stream.clientResolve?.infoHash ?: stream.streamLabel)
    stream.externalUrl?.let {
        append(':')
        append(it)
    }
}

// ---------------------------------------------------------------------------
// Stream Section Header
// ---------------------------------------------------------------------------

@Composable
private fun StreamSectionHeader(
    addonName: String,
    isLoading: Boolean,
    fetchingText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = addonName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        )
        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NuvioLoadingIndicator(
                    modifier = Modifier.size(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = fetchingText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StreamSourceHeader(
    sourceName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = sourceName,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamActionsSheet(
    stream: StreamItem?,
    externalPlayerEnabled: Boolean,
    onDismiss: () -> Unit,
    onCopyLink: (StreamItem) -> Unit,
    onDownload: (StreamItem) -> Unit,
    onOpen: (StreamItem, openExternally: Boolean) -> Unit,
) {
    if (stream == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stream.streamLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                stream.streamSubtitle
                    ?.takeIf { it.isNotBlank() }
                    ?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }

            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.ContentCopy,
                title = stringResource(Res.string.streams_copy_link),
                onClick = {
                    onCopyLink(stream)
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = stringResource(
                    if (externalPlayerEnabled) {
                        Res.string.streams_open_internal_player
                    } else {
                        Res.string.streams_open_external_player
                    },
                ),
                onClick = {
                    onOpen(stream, !externalPlayerEnabled)
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.Download,
                title = stringResource(Res.string.streams_download_file),
                onClick = {
                    onDownload(stream)
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
        }
    }
}

private fun Long.toPlaybackClock(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        buildString {
            append(hours)
            append(':')
            append(minutes.toString().padStart(2, '0'))
            append(':')
            append(seconds.toString().padStart(2, '0'))
        }
    } else {
        buildString {
            append(minutes)
            append(':')
            append(seconds.toString().padStart(2, '0'))
        }
    }
}

// ---------------------------------------------------------------------------
// State blocks
// ---------------------------------------------------------------------------

@Composable
private fun LoadingStateBlock(
    findingStreamsText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioLoadingIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = findingStreamsText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyStateBlock(
    reason: StreamsEmptyStateReason?,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String

    when (reason) {
        StreamsEmptyStateReason.NoAddonsInstalled,
        StreamsEmptyStateReason.NoCompatibleAddons -> {
            title = stringResource(Res.string.playback_unavailable)
            message = stringResource(Res.string.playback_unavailable_message)
        }

        StreamsEmptyStateReason.StreamFetchFailed -> {
            title = stringResource(Res.string.streams_empty_load_failed_title)
            message = stringResource(Res.string.streams_empty_load_failed_message)
        }

        StreamsEmptyStateReason.NoStreamsFound, null -> {
            title = stringResource(Res.string.compose_player_no_streams_found)
            message = stringResource(Res.string.streams_empty_no_streams_message)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FooterLoadingBlock(
    checkingMoreAddonsText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioLoadingIndicator(
            modifier = Modifier.size(14.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = checkingMoreAddonsText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
