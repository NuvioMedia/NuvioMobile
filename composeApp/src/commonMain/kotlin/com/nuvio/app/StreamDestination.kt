package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.resolveContentLanguage
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.serverPlaybackMessage
import com.nuvio.app.features.streams.QualityFirstAutoPlayPolicy
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlayPreferredQuality
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.streams.shouldShowAutoPlayLoading
import com.nuvio.app.features.streams.shouldUseLandscapeAutoPlayLoading
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.YouTubeStreamResolver
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString
import androidx.compose.runtime.CompositionLocalProvider
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.matches
import com.nuvio.app.features.party.partyMediaKey
import com.nuvio.app.features.streams.LocalSharedStream
import com.nuvio.app.features.streams.LocalStreamHighlight
import com.nuvio.app.features.streams.SharedStream

private data class PendingP2pStreamOpen(
    val stream: StreamItem,
    val resumePositionMs: Long?,
    val resumeProgressFraction: Float?,
    val forceExternal: Boolean,
    val forceInternal: Boolean,
    val isAutoPlay: Boolean,
)

@Composable
internal fun StreamDestination(
    route: StreamRoute,
    navController: NuvioNavigator,
    p2pEnabled: Boolean,
    openExternalPlayback: suspend (PlayerLaunch) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
    onLandscapeLoadingChanged: (Boolean) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val launch = remember(route.launchId) {
        StreamLaunchStore.get(route.launchId)
    }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            onBack()
        }
        return
    }
    val pauseDescription = launch.pauseDescription
    val streamRouteScope = rememberCoroutineScope()
    var autoPlayNavigationStarted by remember(route.launchId) { mutableStateOf(false) }
    var qualityPickerDismissed by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var pendingQualitySelection by rememberSaveable(route.launchId) { mutableStateOf<String?>(null) }
    var fallbackQualityAfterWait by rememberSaveable(route.launchId) { mutableStateOf<String?>(null) }
    var qualitySelectionStarted by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var resolvingDebridStream by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var resolvingYouTubeStream by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var preparingServerStream by remember(route.launchId) { mutableStateOf(false) }
    var pendingP2pStreamOpen by remember { mutableStateOf<PendingP2pStreamOpen?>(null) }
    val shouldResolveEpisodeVideoId =
        launch.parentMetaId != null &&
            launch.seasonNumber != null &&
            launch.episodeNumber != null
    var effectiveVideoId by rememberSaveable(
        launch.videoId,
        launch.parentMetaId,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        mutableStateOf(launch.videoId)
    }
    var hasResolvedVideoId by rememberSaveable(
        launch.videoId,
        launch.parentMetaId,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        mutableStateOf(!shouldResolveEpisodeVideoId)
    }

    LaunchedEffect(
        launch.videoId,
        launch.parentMetaId,
        launch.parentMetaType,
        launch.type,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        effectiveVideoId = launch.videoId
        if (!shouldResolveEpisodeVideoId) {
            hasResolvedVideoId = true
            return@LaunchedEffect
        }

        hasResolvedVideoId = false
        val metaType = launch.parentMetaType ?: launch.type
        val metaId = launch.parentMetaId ?: return@LaunchedEffect
        val resolvedVideoId = runCatching {
            MetaDetailsRepository.fetch(metaType, metaId)
        }.getOrNull()
            ?.videos
            ?.firstOrNull { video ->
                video.season == launch.seasonNumber &&
                    video.episode == launch.episodeNumber
            }
            ?.id
            ?.takeIf { it.isNotBlank() }

        effectiveVideoId = resolvedVideoId ?: launch.videoId
        hasResolvedVideoId = true
    }

    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val qualityFlowEnabled = !launch.manualSelection &&
        playerSettings.streamAutoPlayMode == StreamAutoPlayMode.SEMI_AUTOMATIC
    val addonUiState by remember {
        AddonRepository.initialize()
        AddonRepository.uiState
    }.collectAsStateWithLifecycle()
    val installedAddonNames = remember(addonUiState.addons) {
        addonUiState.addons.enabledAddons().mapNotNull { it.manifest?.name }.toSet()
    }

    fun p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
        "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

    fun resolveLaunchContentLanguage(fallbackLanguage: String? = null): String? {
        val meta = MetaDetailsRepository.peek(
            type = launch.parentMetaType ?: launch.type,
            id = launch.parentMetaId ?: effectiveVideoId,
        )
        return resolveContentLanguage(
            language = meta?.language?.takeIf { it.isNotBlank() } ?: fallbackLanguage,
            country = meta?.country,
        )
    }

    suspend fun serverResumePositionMs(stream: StreamItem): Long? {
        if (stream.serverTarget == null || launch.startFromBeginning) return null
        val saved = WatchProgressRepository.progressForVideo(
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
        )
        return ServerPlayback.newerResumePositionMs(stream.playableDirectUrl, saved?.lastUpdatedEpochMs)
    }

    fun openP2pStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        replaceStreamRoute: Boolean,
    ) {
        val infoHash = stream.p2pInfoHash ?: return
        val sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
        if (playerSettings.streamReuseLastLinkEnabled) {
            val cacheKey = StreamLinkCacheRepository.contentKey(
                type = launch.type,
                videoId = effectiveVideoId,
                parentMetaId = launch.parentMetaId,
                season = launch.seasonNumber,
                episode = launch.episodeNumber,
            )
            StreamLinkCacheRepository.save(
                contentKey = cacheKey,
                url = "",
                streamName = stream.streamLabel,
                addonName = stream.addonName,
                addonId = stream.addonId,
                requestHeaders = emptyMap(),
                responseHeaders = emptyMap(),
                filename = stream.behaviorHints.filename,
                videoSize = stream.behaviorHints.videoSize,
                infoHash = infoHash,
                fileIdx = stream.p2pFileIdx,
                sources = stream.sources,
                bingeGroup = stream.behaviorHints.bingeGroup,
                contentLanguage = resolveLaunchContentLanguage(),
            )
        }
        val playerLaunch = PlayerLaunch(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = sentinelUrl,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = stream.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = pauseDescription,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            torrentInfoHash = infoHash,
            torrentFileIdx = stream.p2pFileIdx,
            torrentFilename = stream.behaviorHints.filename,
            torrentTrackers = stream.p2pTrackers,
            initialPositionMs = resolvedResumePositionMs ?: 0L,
            initialProgressFraction = resolvedResumeProgressFraction,
            contentLanguage = resolveLaunchContentLanguage(),
            sourceFilename = stream.behaviorHints.filename,
            stream = stream,
        )

        autoPlayNavigationStarted = replaceStreamRoute
        val launchId = PlayerLaunchStore.put(playerLaunch)
        if (!qualityFlowEnabled || !replaceStreamRoute) StreamsRepository.cancelLoading()
        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
            if (replaceStreamRoute) {
                popUpTo<StreamRoute> { inclusive = true }
            }
        }
    }

    fun requestOrOpenP2pStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        forceExternal: Boolean,
        forceInternal: Boolean,
        isAutoPlay: Boolean,
    ) {
        if (stream.p2pInfoHash == null) {
            if (isAutoPlay) StreamsRepository.skipAutoPlayStream(stream)
            return
        }
        if (!P2pSettingsRepository.isVisible) {
            if (isAutoPlay) StreamsRepository.skipAutoPlayStream(stream)
            return
        }
        if (!p2pEnabled) {
            pendingP2pStreamOpen = PendingP2pStreamOpen(
                stream = stream,
                resumePositionMs = resolvedResumePositionMs,
                resumeProgressFraction = resolvedResumeProgressFraction,
                forceExternal = forceExternal,
                forceInternal = forceInternal,
                isAutoPlay = isAutoPlay,
            )
            return
        }
        openP2pStream(
            stream = stream,
            resolvedResumePositionMs = resolvedResumePositionMs,
            resolvedResumeProgressFraction = resolvedResumeProgressFraction,
            replaceStreamRoute = isAutoPlay,
        )
    }

    var reuseHandled by rememberSaveable(launch.videoId, effectiveVideoId) { mutableStateOf(false) }
    var reuseNavigated by remember { mutableStateOf(false) }
    LaunchedEffect(effectiveVideoId, hasResolvedVideoId, playerSettings.streamReuseLastLinkEnabled, launch.manualSelection, qualityFlowEnabled) {
        if (!hasResolvedVideoId) return@LaunchedEffect
        if (reuseHandled) return@LaunchedEffect
        reuseHandled = true
        if (launch.manualSelection || qualityFlowEnabled) return@LaunchedEffect
        if (!playerSettings.streamReuseLastLinkEnabled) return@LaunchedEffect
        val cacheKey = StreamLinkCacheRepository.contentKey(
            type = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId,
            season = launch.seasonNumber,
            episode = launch.episodeNumber,
        )
        val maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
        val cached = StreamLinkCacheRepository.getValid(cacheKey, maxAgeMs)
        if (cached != null) {
            if (cached.url.isBlank() && !cached.infoHash.isNullOrBlank()) {
                val cachedStream = StreamItem(
                    name = cached.streamName,
                    url = null,
                    infoHash = cached.infoHash,
                    fileIdx = cached.fileIdx,
                    sources = cached.sources,
                    addonName = cached.addonName,
                    addonId = cached.addonId,
                    behaviorHints = StreamBehaviorHints(
                        filename = cached.filename,
                        videoSize = cached.videoSize,
                        bingeGroup = cached.bingeGroup,
                    ),
                )
                requestOrOpenP2pStream(
                    stream = cachedStream,
                    resolvedResumePositionMs = launch.resumePositionMs,
                    resolvedResumeProgressFraction = launch.resumeProgressFraction,
                    forceExternal = false,
                    forceInternal = true,
                    isAutoPlay = true,
                )
                reuseNavigated = true
                return@LaunchedEffect
            }
            val playerLaunch = PlayerLaunch(
                profileId = launch.profileId,
                title = launch.title,
                sourceUrl = cached.url,
                sourceHeaders = sanitizePlaybackHeaders(cached.requestHeaders),
                sourceResponseHeaders = sanitizePlaybackResponseHeaders(cached.responseHeaders),
                externalSubtitles = emptyList(),
                streamType = cached.streamType,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                streamTitle = cached.streamName,
                streamSubtitle = null,
                bingeGroup = cached.bingeGroup,
                pauseDescription = pauseDescription,
                providerName = cached.addonName,
                providerAddonId = cached.addonId,
                contentType = launch.type,
                videoId = effectiveVideoId,
                parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                parentMetaType = launch.parentMetaType ?: launch.type,
                initialPositionMs = launch.resumePositionMs ?: 0L,
                initialProgressFraction = launch.resumeProgressFraction,
                contentLanguage = resolveLaunchContentLanguage(cached.contentLanguage),
                sourceFilename = cached.filename,
                stream = StreamItem(
                    name = cached.streamName,
                    url = cached.url,
                    addonName = cached.addonName,
                    addonId = cached.addonId,
                    streamType = cached.streamType,
                    behaviorHints = StreamBehaviorHints(
                        filename = cached.filename,
                        videoSize = cached.videoSize,
                        bingeGroup = cached.bingeGroup,
                    ),
                ),
            )
            if (playerSettings.externalPlayerEnabled) {
                openExternalPlayback(playerLaunch)
                StreamsRepository.cancelLoading()
                StreamsRepository.setOverlayVisible(false)
                reuseNavigated = true
                return@LaunchedEffect
            }
            StreamsRepository.clear()
            reuseNavigated = true
            autoPlayNavigationStarted = true
            val launchId = PlayerLaunchStore.put(playerLaunch)
            navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
                popUpTo<StreamRoute> { inclusive = true }
            }
        }
    }

    val streamsUiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val expectedStreamsRequestToken = StreamsRepository.requestToken(
        type = launch.type,
        videoId = effectiveVideoId,
        season = launch.seasonNumber,
        episode = launch.episodeNumber,
        manualSelection = launch.manualSelection,
        qualitySelectionMode = qualityFlowEnabled,
    )
    val allQualityStreams = remember(streamsUiState.groups) { streamsUiState.groups.flatMap { it.streams } }
    val qualityEligibleStreams = remember(
        allQualityStreams,
        playerSettings.streamAutoPlaySource,
        playerSettings.streamAutoPlaySelectedAddons,
        playerSettings.streamAutoPlaySelectedPlugins,
        installedAddonNames,
    ) {
        val sourceScopedStreams = when (playerSettings.streamAutoPlaySource) {
            StreamAutoPlaySource.ALL_SOURCES -> allQualityStreams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY ->
                allQualityStreams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY ->
                allQualityStreams.filter { it.addonName !in installedAddonNames }
        }
        sourceScopedStreams.filter { stream ->
            if (stream.serverTarget != null) {
                true
            } else if (stream.addonName in installedAddonNames) {
                playerSettings.streamAutoPlaySelectedAddons.isEmpty() ||
                    stream.addonName in playerSettings.streamAutoPlaySelectedAddons
            } else {
                playerSettings.streamAutoPlaySelectedPlugins.isEmpty() ||
                    stream.addonName in playerSettings.streamAutoPlaySelectedPlugins
            }
        }
    }
    val debridEnabledForQualitySelection = DebridSettingsRepository.snapshot().canResolvePlayableLinks
    val qualitySearchFinished = streamsUiState.requestToken == expectedStreamsRequestToken &&
        !streamsUiState.isAnyLoading && streamsUiState.groups.none { it.isLoading }
    val qualityPreferredAddonOrder = remember(qualityEligibleStreams) {
        qualityEligibleStreams.map { it.addonName }.distinct()
    }
    val topThreeQualityAddonNames = qualityPreferredAddonOrder.take(3).toSet()
    val showQualityPicker = qualityFlowEnabled &&
        !qualityPickerDismissed && !qualitySelectionStarted &&
        !autoPlayNavigationStarted && !reuseNavigated
    val qualityAutoPlayLoading = qualityFlowEnabled && qualitySelectionStarted &&
        streamsUiState.showDirectAutoPlayOverlay
    LaunchedEffect(pendingQualitySelection) {
        val selectedName = pendingQualitySelection ?: return@LaunchedEffect
        delay(6_500)
        if (pendingQualitySelection == selectedName) fallbackQualityAfterWait = selectedName
    }
    LaunchedEffect(
        pendingQualitySelection,
        fallbackQualityAfterWait,
        qualityEligibleStreams,
        qualitySearchFinished,
        streamsUiState.requestToken,
        expectedStreamsRequestToken,
    ) {
        val selectedName = pendingQualitySelection ?: return@LaunchedEffect
        if (streamsUiState.requestToken != expectedStreamsRequestToken) return@LaunchedEffect
        val requestedQuality = runCatching { StreamAutoPlayPreferredQuality.valueOf(selectedName) }.getOrNull()
            ?: return@LaunchedEffect
        val rankedCandidates = QualityFirstAutoPlayPolicy.rankCandidates(
            streams = qualityEligibleStreams,
            requestedQuality = requestedQuality,
            debridEnabled = debridEnabledForQualitySelection,
        )
        val targetHeight = requestedQuality.targetHeight
        val rankedTopThreeCandidates = QualityFirstAutoPlayPolicy.rankCandidates(
            streams = qualityEligibleStreams.filter { it.addonName in topThreeQualityAddonNames },
            requestedQuality = requestedQuality,
            debridEnabled = debridEnabledForQualitySelection,
        )
        val earlyStartCandidates = if (targetHeight == null) rankedTopThreeCandidates else
            rankedTopThreeCandidates.filter { QualityFirstAutoPlayPolicy.detectQualityHeight(it) == targetHeight }
        // Respect the intended threshold: more than ten distinct selectable links at this tier.
        val canStartEarlyFromTopThree = earlyStartCandidates.size > 10
        if (rankedCandidates.isNotEmpty() &&
            (canStartEarlyFromTopThree || qualitySearchFinished || fallbackQualityAfterWait == selectedName)
        ) {
            val started = StreamsRepository.beginQualityFirstAutoPlay(
                expectedRequestToken = expectedStreamsRequestToken,
                candidates = (earlyStartCandidates + rankedCandidates).distinct(),
            )
            pendingQualitySelection = null
            fallbackQualityAfterWait = null
            if (started) qualitySelectionStarted = true
            else NuvioToastController.show("Sources changed; please choose a quality again.")
        } else if (qualitySearchFinished) {
            pendingQualitySelection = null
            fallbackQualityAfterWait = null
            NuvioToastController.show("No playable links were found. Choose another quality or try again.")
        }
    }
    val showLoadingScreen = autoPlayNavigationStarted || resolvingDebridStream || resolvingYouTubeStream || preparingServerStream ||
        (qualityFlowEnabled && qualityAutoPlayLoading) ||
        (!qualityFlowEnabled && streamsUiState.shouldShowAutoPlayLoading(
            expectedRequestToken = expectedStreamsRequestToken,
            settings = playerSettings,
            manualSelection = launch.manualSelection,
        ))
    val useLandscapeLoading = autoPlayNavigationStarted ||
        (qualityFlowEnabled && qualitySelectionStarted && qualityAutoPlayLoading) ||
        (!qualityFlowEnabled && streamsUiState.shouldUseLandscapeAutoPlayLoading(
            expectedRequestToken = expectedStreamsRequestToken,
            settings = playerSettings,
            manualSelection = launch.manualSelection,
        ))
    SideEffect { onLandscapeLoadingChanged(useLandscapeLoading) }
    var autoPlayHandled by rememberSaveable(launch.videoId, effectiveVideoId) { mutableStateOf(false) }
    LaunchedEffect(
        streamsUiState.autoPlayStream,
        streamsUiState.requestToken,
        expectedStreamsRequestToken,
        reuseHandled,
        launch.manualSelection,
        qualityFlowEnabled,
        qualitySelectionStarted,
    ) {
        if (!reuseHandled) return@LaunchedEffect
        if (launch.manualSelection) return@LaunchedEffect
        if (qualityFlowEnabled && !qualitySelectionStarted) return@LaunchedEffect
        if (reuseNavigated) return@LaunchedEffect
        if (autoPlayHandled) return@LaunchedEffect
        if (streamsUiState.requestToken != expectedStreamsRequestToken) return@LaunchedEffect
        val selectedStream = streamsUiState.autoPlayStream ?: return@LaunchedEffect
        val stream = if (selectedStream.needsServerPreparation) {
            StreamsRepository.setOverlayVisible(true, getString(Res.string.player_loading_preparing))
            runCatching { ServerPlayback.prepare(selectedStream) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { error ->
                    if (!StreamsRepository.skipAutoPlayStream(selectedStream)) {
                        NuvioToastController.show(error.serverPlaybackMessage())
                    }
                    return@LaunchedEffect
                }
        } else if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(selectedStream)) {
            StreamsRepository.setOverlayVisible(true, getString(Res.string.debrid_resolving_stream))
            when (
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = selectedStream,
                    season = launch.seasonNumber,
                    episode = launch.episodeNumber,
                )
            ) {
                is DirectDebridPlayableResult.Success -> resolved.stream
                else -> {
                    val hasNextCandidate = StreamsRepository.skipAutoPlayStream(selectedStream)
                    if (!hasNextCandidate) {
                        resolved.toastMessage()?.let { NuvioToastController.show(it) }
                    }
                    if (!hasNextCandidate && resolved == DirectDebridPlayableResult.Stale) {
                        StreamsRepository.reload(
                            type = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = launch.seasonNumber,
                            episode = launch.episodeNumber,
                            manualSelection = launch.manualSelection,
                            qualitySelectionMode = qualityFlowEnabled,
                        )
                    }
                    return@LaunchedEffect
                }
            }
        } else {
            selectedStream
        }
        val sourceUrl = stream.playableDirectUrl
        if (sourceUrl == null && stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
            autoPlayHandled = true
            requestOrOpenP2pStream(
                stream = stream,
                resolvedResumePositionMs = launch.resumePositionMs,
                resolvedResumeProgressFraction = launch.resumeProgressFraction,
                forceExternal = false,
                forceInternal = true,
                isAutoPlay = true,
            )
            StreamsRepository.consumeAutoPlay()
            return@LaunchedEffect
        }
        if (sourceUrl == null) {
            StreamsRepository.skipAutoPlayStream(selectedStream)
            return@LaunchedEffect
        }
        autoPlayHandled = true
        val serverResumeMs = serverResumePositionMs(stream)
        if (playerSettings.streamReuseLastLinkEnabled && stream.serverTarget == null) {
            val cacheKey = StreamLinkCacheRepository.contentKey(
                type = launch.type,
                videoId = effectiveVideoId,
                parentMetaId = launch.parentMetaId,
                season = launch.seasonNumber,
                episode = launch.episodeNumber,
            )
            StreamLinkCacheRepository.save(
                contentKey = cacheKey,
                url = sourceUrl,
                streamName = stream.streamLabel,
                addonName = stream.addonName,
                addonId = stream.addonId,
                requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                filename = stream.behaviorHints.filename,
                videoSize = stream.behaviorHints.videoSize,
                bingeGroup = stream.behaviorHints.bingeGroup,
                streamType = stream.streamType,
                contentLanguage = resolveLaunchContentLanguage(),
            )
        }
        val playerLaunch = PlayerLaunch(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            externalSubtitles = stream.externalSubtitles,
            streamType = stream.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = pauseDescription,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            initialPositionMs = serverResumeMs ?: launch.resumePositionMs ?: 0L,
            initialProgressFraction = launch.resumeProgressFraction.takeIf { serverResumeMs == null },
            contentLanguage = resolveLaunchContentLanguage(),
            sourceFilename = stream.behaviorHints.filename,
            stream = stream,
        )
        if (playerSettings.externalPlayerEnabled && stream.serverTarget == null) {
            openExternalPlayback(playerLaunch)
            StreamsRepository.consumeAutoPlay()
            if (!qualityFlowEnabled) StreamsRepository.cancelLoading()
            return@LaunchedEffect
        }
        StreamsRepository.consumeAutoPlay()
        if (!qualityFlowEnabled) StreamsRepository.cancelLoading()
        autoPlayNavigationStarted = true
        val launchId = PlayerLaunchStore.put(playerLaunch)
        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
            popUpTo<StreamRoute> { inclusive = true }
        }
    }

    if (!hasResolvedVideoId) {
        if (showLoadingScreen) {
            StreamLoadingScreen(
                launch = launch,
                state = StreamsUiState(),
                showStatus = playerSettings.showPlayerLoadingStatus,
                resolvingDebridStream = false,
                onBack = onBack,
            )
            return
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            NuvioLoadingIndicator()
        }
        return
    }

    fun openSelectedStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        forceExternal: Boolean,
        forceInternal: Boolean,
        saveForReuse: Boolean = true,
    ) {
        if (stream.needsServerPreparation) {
            if (preparingServerStream) return
            streamRouteScope.launch {
                preparingServerStream = true
                val prepared = runCatching { ServerPlayback.prepare(stream) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrElse { error ->
                        preparingServerStream = false
                        NuvioToastController.show(error.serverPlaybackMessage())
                        return@launch
                    }
                val serverResumeMs = serverResumePositionMs(prepared)
                preparingServerStream = false
                openSelectedStream(
                    stream = prepared,
                    resolvedResumePositionMs = serverResumeMs ?: resolvedResumePositionMs,
                    resolvedResumeProgressFraction = resolvedResumeProgressFraction.takeIf { serverResumeMs == null },
                    forceExternal = false,
                    forceInternal = true,
                )
            }
            return
        }
        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            if (resolvingDebridStream) return
            streamRouteScope.launch {
                resolvingDebridStream = true
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = stream,
                    season = launch.seasonNumber,
                    episode = launch.episodeNumber,
                )
                resolvingDebridStream = false
                when (resolved) {
                    is DirectDebridPlayableResult.Success -> openSelectedStream(
                        stream = resolved.stream,
                        resolvedResumePositionMs = resolvedResumePositionMs,
                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                        forceExternal = forceExternal,
                        forceInternal = forceInternal,
                    )
                    else -> {
                        resolved.toastMessage()?.let { NuvioToastController.show(it) }
                        if (resolved == DirectDebridPlayableResult.Stale) {
                            StreamsRepository.reload(
                                type = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId,
                                season = launch.seasonNumber,
                                episode = launch.episodeNumber,
                                manualSelection = launch.manualSelection,
                            )
                        }
                    }
                }
            }
            return
        }
        if (stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
            requestOrOpenP2pStream(
                stream = stream,
                resolvedResumePositionMs = resolvedResumePositionMs,
                resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                forceExternal = forceExternal,
                forceInternal = forceInternal,
                isAutoPlay = false,
            )
            return
        }
        if (stream.youTubeIdToResolve != null) {
            if (resolvingYouTubeStream) return
            streamRouteScope.launch {
                resolvingYouTubeStream = true
                val resolved = YouTubeStreamResolver.Default.resolve(stream)
                resolvingYouTubeStream = false
                if (resolved == null) {
                    NuvioToastController.show(getString(Res.string.youtube_resolution_failed))
                    return@launch
                }
                openSelectedStream(
                    stream = resolved,
                    resolvedResumePositionMs = resolvedResumePositionMs,
                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                    forceExternal = forceExternal,
                    forceInternal = forceInternal,
                    // The resolved URL expires after a few hours, so it isn't kept for reuse.
                    saveForReuse = false,
                )
            }
            return
        }
        if (stream.shouldOpenExternally) {
            val opened = stream.externalOpenUrl?.let { url -> openExternalStreamUrl(url) } == true
            if (opened && (!qualityFlowEnabled || !qualitySelectionStarted)) {
                StreamsRepository.cancelLoading()
            }
            return
        }
        val sourceUrl = stream.playableDirectUrl ?: return
        if (saveForReuse && playerSettings.streamReuseLastLinkEnabled && stream.serverTarget == null) {
            val cacheKey = StreamLinkCacheRepository.contentKey(
                type = launch.type,
                videoId = effectiveVideoId,
                parentMetaId = launch.parentMetaId,
                season = launch.seasonNumber,
                episode = launch.episodeNumber,
            )
            StreamLinkCacheRepository.save(
                contentKey = cacheKey,
                url = sourceUrl,
                streamName = stream.streamLabel,
                addonName = stream.addonName,
                addonId = stream.addonId,
                requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                filename = stream.behaviorHints.filename,
                videoSize = stream.behaviorHints.videoSize,
                bingeGroup = stream.behaviorHints.bingeGroup,
                streamType = stream.streamType,
                contentLanguage = resolveLaunchContentLanguage(),
            )
        }
        val playerLaunch = PlayerLaunch(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            externalSubtitles = stream.externalSubtitles,
            streamType = stream.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = pauseDescription,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            initialPositionMs = resolvedResumePositionMs ?: 0L,
            initialProgressFraction = resolvedResumeProgressFraction,
            contentLanguage = resolveLaunchContentLanguage(),
            sourceFilename = stream.behaviorHints.filename,
            stream = stream,
        )

        if (!forceInternal && (forceExternal || playerSettings.externalPlayerEnabled)) {
            streamRouteScope.launch {
                openExternalPlayback(playerLaunch)
                if (!qualityFlowEnabled || !qualitySelectionStarted) StreamsRepository.cancelLoading()
            }
            return
        }

        val launchId = PlayerLaunchStore.put(playerLaunch)
        if (!qualityFlowEnabled || !qualitySelectionStarted) StreamsRepository.cancelLoading()
        navController.navigate(
            PlayerRoute(launchId = launchId, title = playerLaunch.title)
        )
    }

    LaunchedEffect(reuseNavigated) {
        if (reuseNavigated) {
            StreamsRepository.setOverlayVisible(false)
        }
    }

    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyStream by PartyRepository.sharedStream.collectAsStateWithLifecycle()
    val party = partyState.party
    val contentKey = partyMediaKey(
        metaType = launch.parentMetaType ?: launch.type,
        metaId = launch.parentMetaId ?: effectiveVideoId,
        season = launch.seasonNumber,
        episode = launch.episodeNumber,
    )
    val partySource = party?.media?.takeIf { it.key == contentKey }?.source
    val partySourceLabel = stringResource(Res.string.party_source)
    val streamHighlight: (StreamItem) -> String? = remember(partySource, partySourceLabel) {
        { stream -> if (partySource?.matches(stream) == true) partySourceLabel else null }
    }
    val sharedLabel = stringResource(Res.string.party_shared_by, party?.member(party.hostMemberId)?.displayName.orEmpty())
    val isHost = party?.isHost == true
    val sharedStream = remember(partyStream, sharedLabel, isHost, contentKey) {
        partyStream
            ?.takeIf { !isHost && it.mediaKey == contentKey }
            ?.let { SharedStream(label = sharedLabel, stream = it.toStreamItem()) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalStreamHighlight provides streamHighlight,
            LocalSharedStream provides sharedStream,
        ) {
            StreamsScreen(
                showLoadingScreen = showLoadingScreen,
                type = launch.type,
                videoId = effectiveVideoId,
                parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                parentMetaType = launch.parentMetaType ?: launch.type,
                title = launch.title,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                resumePositionMs = launch.resumePositionMs,
                resumeProgressFraction = launch.resumeProgressFraction,
                manualSelection = launch.manualSelection,
                qualitySelectionMode = qualityFlowEnabled,
                startFromBeginning = launch.startFromBeginning,
                onStreamSelected = { stream, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                    openSelectedStream(
                        stream = stream,
                        resolvedResumePositionMs = resolvedResumePositionMs,
                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                        forceExternal = false,
                        forceInternal = false,
                        saveForReuse = stream != sharedStream?.stream,
                    )
                },
                onStreamActionOpen = { stream, openExternally, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                    openSelectedStream(
                        stream = stream,
                        resolvedResumePositionMs = resolvedResumePositionMs,
                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                        forceExternal = openExternally,
                        forceInternal = !openExternally,
                    )
                },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }
        pendingP2pStreamOpen?.let { pending ->
            P2pConsentDialog(
                onEnableP2p = {
                    P2pSettingsRepository.setP2pEnabled(true)
                    pendingP2pStreamOpen = null
                    openP2pStream(
                        stream = pending.stream,
                        resolvedResumePositionMs = pending.resumePositionMs,
                        resolvedResumeProgressFraction = pending.resumeProgressFraction,
                        replaceStreamRoute = pending.isAutoPlay,
                    )
                },
                onDismiss = {
                    if (pending.isAutoPlay) {
                        StreamsRepository.skipAutoPlayStream(pending.stream)
                        StreamsRepository.consumeAutoPlay()
                    }
                    pendingP2pStreamOpen = null
                },
            )
        }
        if (showLoadingScreen) {
            StreamLoadingScreen(
                launch = launch,
                state = streamsUiState.takeIf { it.requestToken == expectedStreamsRequestToken } ?: StreamsUiState(),
                showStatus = playerSettings.showPlayerLoadingStatus,
                resolvingDebridStream = resolvingDebridStream,
                resolvingYouTubeStream = resolvingYouTubeStream,
                onBack = onBack,
                preparingPlayback = preparingServerStream,
            )
        }
    }
    if (showQualityPicker) {
        fun qualityLabel(quality: StreamAutoPlayPreferredQuality): String = when (quality) {
            StreamAutoPlayPreferredQuality.P2160 -> "4K (2160p)"
            StreamAutoPlayPreferredQuality.P1440 -> "2K (1440p)"
            StreamAutoPlayPreferredQuality.P1080 -> "1080p"
            StreamAutoPlayPreferredQuality.P720 -> "720p"
            StreamAutoPlayPreferredQuality.P480 -> "480p"
            StreamAutoPlayPreferredQuality.P360 -> "360p"
            StreamAutoPlayPreferredQuality.ANY -> "Best available"
        }
        fun profileGlyph(quality: StreamAutoPlayPreferredQuality): String = when (quality) {
            StreamAutoPlayPreferredQuality.P2160 -> "4K"
            StreamAutoPlayPreferredQuality.P1440 -> "2K"
            StreamAutoPlayPreferredQuality.P1080, StreamAutoPlayPreferredQuality.P720 -> "HD"
            StreamAutoPlayPreferredQuality.P480, StreamAutoPlayPreferredQuality.P360 -> "SD"
            StreamAutoPlayPreferredQuality.ANY -> "✦"
        }
        val options = listOf(StreamAutoPlayPreferredQuality.P2160, StreamAutoPlayPreferredQuality.P1080,
            StreamAutoPlayPreferredQuality.P720, StreamAutoPlayPreferredQuality.P480)
        val streamCount = qualityEligibleStreams.size
        val sourceStatus = when {
            !qualitySearchFinished && streamCount == 0 -> "Searching providers in the background…"
            !qualitySearchFinished -> "$streamCount links found · continuing search in the background…"
            streamCount == 0 -> "Search complete · no eligible links found"
            else -> "$streamCount eligible links fetched"
        }
        val qualityCounts = remember(qualityEligibleStreams, debridEnabledForQualitySelection) {
            options.associateWith { quality ->
                QualityFirstAutoPlayPolicy.rankCandidates(
                    streams = qualityEligibleStreams,
                    requestedQuality = quality,
                    debridEnabled = debridEnabledForQualitySelection,
                ).count { QualityFirstAutoPlayPolicy.detectQualityHeight(it) == quality.targetHeight }
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.74f)),
            contentAlignment = Alignment.Center,
        ) {
            val landscape = maxWidth > maxHeight
            Surface(
                modifier = Modifier.fillMaxWidth(if (landscape) 0.92f else 0.90f)
                    .widthIn(max = if (landscape) 760.dp else 440.dp)
                    .heightIn(max = maxHeight * 0.92f),
                shape = RoundedCornerShape(30.dp),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Select playback quality", style = MaterialTheme.typography.headlineSmall)
                    Text(sourceStatus, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    options.chunked(2).forEach { rowQualities ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top) {
                            rowQualities.forEach { quality ->
                                val count = qualityCounts[quality] ?: 0
                                TextButton(
                                    onClick = { pendingQualitySelection = quality.name; fallbackQualityAfterWait = null },
                                    modifier = Modifier.weight(1f).heightIn(min = 124.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Surface(
                                            modifier = Modifier.size(if (landscape) 58.dp else 68.dp),
                                            shape = CircleShape,
                                            color = if (pendingQualitySelection == quality.name)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = if (pendingQualitySelection == quality.name)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSecondaryContainer,
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(profileGlyph(quality), style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                        Text(qualityLabel(quality), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text("$count links", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                            repeat(2 - rowQualities.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (pendingQualitySelection != null) {
                        val pendingLabel = runCatching {
                            qualityLabel(StreamAutoPlayPreferredQuality.valueOf(pendingQualitySelection!!))
                        }.getOrDefault("selected quality")
                        Text("Looking for $pendingLabel. A lower available quality may be tried after about 7 seconds if no exact matches arrive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        pendingQualitySelection = null
                        fallbackQualityAfterWait = null
                        qualityPickerDismissed = true
                    }) { Text("Cancel") }
                }
            }
        }
    }

}
