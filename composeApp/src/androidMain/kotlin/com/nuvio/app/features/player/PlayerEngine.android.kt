package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.SpannableString
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.util.AttributeSet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import com.nuvio.app.features.streams.redactedForLogs
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.app.features.autosync.AutoSyncExtractorsFactory
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.LIBMPV_AUTOSYNC_FILE_PREFIX
import com.nuvio.app.features.autosync.rememberAutoSyncCoordinator
import com.nuvio.app.features.autosync.rememberLibmpvAutoSyncCoordinator
import com.nuvio.app.R
import com.nuvio.app.features.streams.normalizeStreamType
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import com.nuvio.tv.libass.media.widget.AssSubtitleSurfaceView
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NuvioPlayer"
private const val PLAYER_DIAGNOSTIC_TAG = "NuvioPlayerDiag"

private class PlaybackDiagnostics {
    var prepareStartedAtMs: Long = 0L
    var attempt: Int = 0
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: AndroidPlaybackEngine?,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizePlaybackHeaders(sourceHeaders),
        sanitizePlaybackResponseHeaders(sourceResponseHeaders),
        normalizeStreamType(streamType).orEmpty(),
        useYoutubeChunkedPlayback,
        initialPositionRequestKey.orEmpty(),
    )
    val requestedEngine = playbackEngine ?: playerSettings.androidPlaybackEngine
    val activeEngine by remember(playerSourceKey, requestedEngine) {
        mutableStateOf(requestedEngine.initialAndroidEngine())
    }

    when (activeEngine) {
        ResolvedAndroidPlaybackEngine.ExoPlayer -> ExoPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            externalSubtitles = externalSubtitles,
            streamType = streamType,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            modifier = modifier,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            initialPositionRequestKey = initialPositionRequestKey,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        ResolvedAndroidPlaybackEngine.Libmpv -> {
            LaunchedEffect(initialPositionRequestKey) {
                initialPositionRequestKey?.let { key ->
                    onInitialPositionHandled(key, false)
                }
            }
            LibmpvPlayerSurface(
                sourceUrl = sourceUrl,
                sourceAudioUrl = sourceAudioUrl,
                sourceHeaders = sourceHeaders,
                externalSubtitles = externalSubtitles,
                modifier = modifier,
                playWhenReady = playWhenReady,
                resizeMode = resizeMode,
                videoOutput = playerSettings.androidLibmpvVideoOutput,
                hardwareDecodingEnabled = playerSettings.androidLibmpvHardwareDecodingEnabled,
                yuv420pEnabled = playerSettings.androidLibmpvYuv420pEnabled,
                preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
                onControllerReady = onControllerReady,
                onSnapshot = onSnapshot,
                onError = onError,
            )
        }
    }
}

private enum class ResolvedAndroidPlaybackEngine {
    ExoPlayer,
    Libmpv,
}

private fun AndroidPlaybackEngine.initialAndroidEngine(): ResolvedAndroidPlaybackEngine =
    when (this) {
        AndroidPlaybackEngine.Auto,
        AndroidPlaybackEngine.ExoPlayer -> ResolvedAndroidPlaybackEngine.ExoPlayer
        AndroidPlaybackEngine.Libmpv -> ResolvedAndroidPlaybackEngine.Libmpv
    }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()

    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }

    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    val sanitizedSourceResponseHeaders = remember(sourceResponseHeaders) {
        sanitizePlaybackResponseHeaders(sourceResponseHeaders)
    }
    val normalizedStreamType = remember(streamType) {
        normalizeStreamType(streamType)
    }
    val useLibass = playerSettings.useLibass
    val libassRenderMode = playerSettings.libassRenderMode
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType.orEmpty(),
        useYoutubeChunkedPlayback,
        initialPositionRequestKey.orEmpty(),
    )
    // Tracks whether an ASS track was actively selected and video effects need activation.
    // When this flips to true, the player remember key changes and player rebuilds with effects.
    var videoEffectsNeeded by remember(playerSourceKey) { mutableStateOf(false) }
    val playbackDiagnostics = remember(playerSourceKey) { PlaybackDiagnostics() }
    var subtitleDelayMs by remember(playerSourceKey) { mutableStateOf(0) }
    var selectedExternalSubtitleMimeType by remember(playerSourceKey) { mutableStateOf<String?>(null) }
    val latestSubtitleDelayMs = rememberUpdatedState(subtitleDelayMs)
    val latestExternalSubtitleMimeType = rememberUpdatedState(selectedExternalSubtitleMimeType)
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var videoAspectRatio by remember(playerSourceKey) { mutableStateOf(0f) }
    val latestVideoAspectRatio = rememberUpdatedState(videoAspectRatio)
    var currentSubtitleStyle by remember { mutableStateOf(SubtitleStyleState.DEFAULT) }
    val subtitleStyleGuard = remember { SubtitleStyleGuard() }
    var decoderPriorityOverride by remember(playerSourceKey) { mutableStateOf<Int?>(null) }
    var fallbackStartPositionMs by remember(playerSourceKey) { mutableStateOf<Long?>(null) }
    val effectiveDecoderPriority = decoderPriorityOverride ?: playerSettings.decoderPriority

    var resolvedMediaItem by remember(playerSourceKey, externalSubtitles) {
        mutableStateOf(
            playbackMediaItemFromUrl(
                url = sourceUrl,
                responseHeaders = sanitizedSourceResponseHeaders,
                streamType = normalizedStreamType,
            ).buildUpon()
                .setMediaId(sourceUrl)
                .apply {
                    val subtitleConfigs = startupSubtitleConfigurations(externalSubtitles)
                    if (subtitleConfigs.isNotEmpty()) {
                        setSubtitleConfigurations(subtitleConfigs)
                    }
                }
                .build(),
        )
    }
    var probeAttempted by remember(playerSourceKey) { mutableStateOf(false) }

    val isLocalFileSource = remember(resolvedMediaItem) {
        resolvedMediaItem.isLocalFileSource()
    }

    val extractorsFactory = remember(sourceUrl) {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            .let { factory -> // AutoSync hook: no observation while disabled.
                AutoSyncPreferencesRepository.ensureLoaded()
                if (AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) {
                    AutoSyncExtractorsFactory(factory, sourceUrl)
                } else factory
            }
    }
    val dataSourceFactory = remember(
        context,
        sourceUrl,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        useYoutubeChunkedPlayback,
        externalSubtitles,
        normalizedStreamType,
        playerSettings.vodCacheEnabled,
        playerSettings.vodCacheSizeMode,
        playerSettings.vodCacheSizeMb,
    ) {
        val upstream = PlatformPlaybackDataSourceFactory.create(
            context = context,
            defaultRequestHeaders = sanitizedSourceHeaders,
            defaultResponseHeaders = sanitizedSourceResponseHeaders,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            useLongReadTimeout = isLoopbackPlaybackSource(sourceUrl),
            externalSubtitles = externalSubtitles,
        )
        val mimeType = playbackMediaItemFromUrl(
            url = sourceUrl,
            responseHeaders = sanitizedSourceResponseHeaders,
            streamType = normalizedStreamType,
        ).localConfiguration?.mimeType
        val adaptive = mimeType == MimeTypes.APPLICATION_M3U8 ||
            mimeType == MimeTypes.APPLICATION_MPD ||
            mimeType == MimeTypes.APPLICATION_SS
        if (!playerSettings.vodCacheEnabled || useYoutubeChunkedPlayback || adaptive) {
            upstream
        } else {
            VodCache.wrap(
                context = context,
                upstream = upstream,
                url = sourceUrl,
                enabled = true,
                sizeMode = playerSettings.vodCacheSizeMode,
                sizeMb = playerSettings.vodCacheSizeMb,
            )
        }
    }

    fun ExoPlayer.setPlaybackMediaItem(videoMediaItem: MediaItem, startPositionMs: Long? = null) {
        if (!sourceAudioUrl.isNullOrBlank()) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            val videoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
            val audioSource = mediaSourceFactory.createMediaSource(playbackMediaItemFromUrl(sourceAudioUrl))
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            if (startPositionMs != null) {
                setMediaSource(mergedSource, startPositionMs.coerceAtLeast(0L))
            } else {
                setMediaSource(mergedSource)
            }
        } else if (startPositionMs != null) {
            setMediaItem(videoMediaItem, startPositionMs.coerceAtLeast(0L))
        } else {
            setMediaItem(videoMediaItem)
        }
    }

    val exoPlayer = remember(
        sourceUrl,
        sourceAudioUrl,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType,
        useYoutubeChunkedPlayback,
        effectiveDecoderPriority,
        initialPositionRequestKey,
        playerSettings.exoNativeMemoryEnabled,
        playerSettings.bufferEngineEnabled,
        playerSettings.minBufferMs,
        playerSettings.maxBufferMs,
        playerSettings.bufferForPlaybackMs,
        playerSettings.bufferForPlaybackAfterRebufferMs,
        playerSettings.backBufferDurationMs,
        playerSettings.targetBufferSizeMb,
        videoEffectsNeeded,
    ) {
        applyExoPlayerNativeMemory(playerSettings.exoNativeMemoryEnabled)
        val renderersFactory = SubtitleOffsetRenderersFactory(
            context = context,
            subtitleDelayUsProvider = { latestSubtitleDelayMs.value.toLong() * 1_000L },
            shouldNormalizeCuePositionProvider = {
                latestExternalSubtitleMimeType.value == MimeTypes.TEXT_VTT
            },
            shouldStripSdhProvider = { currentSubtitleStyle.stripSdh },
            videoBoundsFractionProvider = {
                playerViewRef?.videoBoundsFraction(latestVideoAspectRatio.value)
            },
        )
            .setExtensionRendererMode(effectiveDecoderPriority)
            .setEnableDecoderFallback(true)
            .setMapDV7ToHevc(playerSettings.mapDV7ToHevc)

        val trackSelector = DefaultTrackSelector(context).apply {
            var parameters = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            if (playerSettings.tunnelingEnabled) {
                parameters = parameters.setTunnelingEnabled(true)
            }
            val captioningManager = context.getSystemService(Context.CAPTIONING_SERVICE)
                as? android.view.accessibility.CaptioningManager
            if (captioningManager != null) {
                if (!captioningManager.isEnabled) {
                    parameters = parameters.setIgnoredTextSelectionFlags(
                        parameters.build().ignoredTextSelectionFlags or C.SELECTION_FLAG_DEFAULT
                    )
                }
                captioningManager.locale?.let { locale ->
                    parameters = parameters.setPreferredTextLanguage(locale.isO3Language)
                }
            }
            if (playerSettings.subtitleStyle.useForcedSubtitles) {
                parameters = parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                parameters = parameters.setIgnoredTextSelectionFlags(
                    parameters.build().ignoredTextSelectionFlags or C.SELECTION_FLAG_FORCED
                )
            }
            setParameters(parameters)
        }

        val loadControl = NuvioExoPlayerPerformanceHelper.buildLoadControl(
            context,
            PlaybackBufferSettings(
                enabled = playerSettings.bufferEngineEnabled,
                minBufferMs = playerSettings.minBufferMs,
                maxBufferMs = playerSettings.maxBufferMs,
                bufferForPlaybackMs = playerSettings.bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs = playerSettings.bufferForPlaybackAfterRebufferMs,
                backBufferDurationMs = playerSettings.backBufferDurationMs,
                targetBufferSizeMb = playerSettings.targetBufferSizeMb,
            ),
            localFileSource = isLocalFileSource,
        )

        val playerBuilder = ExoPlayer.Builder(context)
            .setAudioAttributes(playbackAudioAttributes, true)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)

        val player = if (useLibass) {
            // Set render scale before building the player
            val scaleValue = when (playerSettings.libassRenderScale) {
                "THREE_QUARTER" -> 0.75f
                "HALF" -> 0.5f
                "THIRD" -> 0.33f
                "QUARTER" -> 0.25f
                else -> 1.0f
            }
            com.nuvio.tv.libass.media.AssHandler.setRenderScale(scaleValue)
            playerBuilder.buildWithAssSupportCompat(
                context = context,
                dataSourceFactory = dataSourceFactory,
                extractorsFactory = extractorsFactory,
                renderersFactory = renderersFactory
            ).also { builtPlayer ->
                // Apply video effects only when ASS track was detected as selected.
                // On first init videoEffectsNeeded is false. When onTracksChanged
                // detects a selected ASS track, it flips to true which triggers a
                // player rebuild via the remember key.
                if (videoEffectsNeeded) {
                    val effectiveMode = if (playerSettings.tunnelingEnabled) "OVERLAY" else playerSettings.libassRenderMode
                    if (effectiveMode != "OVERLAY") {
                        builtPlayer.getAssHandlerCompat()?.let { handler ->
                            builtPlayer.setVideoEffects(
                                listOf(com.nuvio.tv.libass.media.effect.AssVideoEffectAsync(handler))
                            )
                        }
                    }
                }
            }
        } else {
            val mediaSourceFactory = DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory,
            )

            playerBuilder
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }

        player.applySubtitleTrackPreferences(
            preferredLanguage = playerSettings.preferredSubtitleLanguage,
            useForcedSubtitles = playerSettings.subtitleStyle.useForcedSubtitles,
            autoSelectionApplied = false,
            hasActiveSubtitle = false,
            useCustomSubtitles = false,
        )
        player
    }

    val nowPlayingController = remember(context, exoPlayer) {
        AndroidPlayerNowPlayingController(
            context = context,
            controls = AndroidPlayerNowPlayingController.PlaybackControls(
                play = {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                },
                pause = exoPlayer::pause,
                seekTo = { positionMs -> exoPlayer.seekTo(positionMs.coerceAtLeast(0L)) },
                seekBy = { offsetMs ->
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                },
            ),
        )
    }

    fun dispatchExoPlayerSnapshot() {
        val snapshot = exoPlayer.snapshot()
        latestOnSnapshot.value(snapshot)
        nowPlayingController.syncPlayback(snapshot)
    }

    DisposableEffect(nowPlayingController) {
        onDispose { nowPlayingController.release() }
    }

    LaunchedEffect(exoPlayer, resolvedMediaItem, initialPositionRequestKey) {
        val mediaItem = resolvedMediaItem
        val requestedStartPositionMs = fallbackStartPositionMs
            ?: initialPositionMs?.takeIf { it > 0L }
        playbackDiagnostics.attempt += 1
        playbackDiagnostics.prepareStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            PLAYER_DIAGNOSTIC_TAG,
            "prepare begin attempt=${playbackDiagnostics.attempt} " +
                "source=${diagnosticPlaybackSource(sourceUrl)} audioSource=${!sourceAudioUrl.isNullOrBlank()} " +
                "mime=${mediaItem.localConfiguration?.mimeType ?: "auto"} " +
                "startPositionMs=${requestedStartPositionMs ?: 0L}",
        )
        exoPlayer.setPlaybackMediaItem(mediaItem, requestedStartPositionMs)
        if (fallbackStartPositionMs == null) {
            initialPositionRequestKey?.let { key ->
                latestOnInitialPositionHandled.value(
                    key,
                    requestedStartPositionMs != null,
                )
            }
        }
        exoPlayer.prepare()
    }

    val pendingSubtitleTrackIndex = remember { mutableListOf<Int>() }
    val pendingAudioTrackSelection = remember { mutableListOf<TrackSelectionSnapshot>() }
    var subtitleSelectionJob by remember { mutableStateOf<Job?>(null) }
    val isInPip = rememberIsInPictureInPicture()
    // Player size outside PiP, so PiP subtitles keep the full-screen size relative to the video.
    var playerSizeOutsidePip by remember { mutableStateOf(IntSize.Zero) }
    val pipVideoHeightPx by rememberUpdatedState(
        if (isInPip) displayedVideoHeightPx(playerSizeOutsidePip, exoPlayer.videoSize) ?: 0f else null,
    )

    val sidecarController = remember(exoPlayer, coroutineScope) {
        SidecarSubtitleController(
            scope = coroutineScope,
            getPlayer = { exoPlayer },
            getSubtitleDelayMs = { latestSubtitleDelayMs.value },
        )
    }

    val autoSyncCoordinator = rememberAutoSyncCoordinator( // AutoSync hook
        scope = coroutineScope,
        player = exoPlayer,
        sidecar = sidecarController,
        playerSourceKey = playerSourceKey,
        sourceUrl = sourceUrl,
        sourceHeaders = sanitizedSourceHeaders,
        externalSubtitles = externalSubtitles,
        useLibass = useLibass,
        preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
        onMimeTypeSelected = { selectedExternalSubtitleMimeType = it },
        onSubtitleDelayChanged = { subtitleDelayMs = it },
    )

    fun syncPlayerViewKeepScreenOn() {
        playerViewRef?.keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
    }

    fun preserveAudioSelectionForReload(reason: String) {
        pendingAudioTrackSelection.clear()
        val selection = exoPlayer.captureSelectedTrack(C.TRACK_TYPE_AUDIO) ?: return
        pendingAudioTrackSelection.add(selection)
        Log.d(TAG, "$reason: preserving audio track index=${selection.index} id=${selection.id}")
    }

    DisposableEffect(exoPlayer) {
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            exoPlayer.pause()
        }
        PlayerPictureInPictureManager.registerTogglePlaybackCallback {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                exoPlayer.play()
            }
        }

        fun reportPlayerError(error: PlaybackException) {
            if (
                playerSettings.decoderPriority == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON &&
                effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER &&
                error.isDecoderFailure()
            ) {
                Log.w(
                    TAG,
                    "Decoder failure (${error.errorCodeName}); retrying with app decoders",
                    error,
                )
                fallbackStartPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                decoderPriorityOverride = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                latestOnError.value(null)
                return
            }
            latestOnError.value(error.localizedMessage ?: runBlocking { getString(Res.string.player_unable_to_play_stream) })
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                syncPlayerViewKeepScreenOn()
                Log.e(
                    PLAYER_DIAGNOSTIC_TAG,
                    "error attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName ?: "none"} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)} " +
                        "bufferedMs=${exoPlayer.bufferedPosition.coerceAtLeast(0L)} " +
                        "durationMs=${exoPlayer.duration.coerceAtLeast(0L)} " +
                        "message=${diagnosticPlayerMessage(error.message)} " +
                        "causeChain=${diagnosticThrowableChain(error)}",
                    error,
                )

                val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

                if (isSourceError && !probeAttempted) {
                    probeAttempted = true
                    coroutineScope.launch {
                        val probedMime = withContext(Dispatchers.IO) {
                            probeMimeType(sourceUrl, sanitizedSourceHeaders)
                        }
                        if (probedMime != null) {
                            Log.d(TAG, "Playback failed with source error. Probed MIME type: $probedMime. Retrying...")
                            resolvedMediaItem = resolvedMediaItem.buildUpon()
                                .setMimeType(probedMime)
                                .build()
                            latestOnError.value(null)
                            return@launch
                        }
                        reportPlayerError(error)
                    }
                    return
                }

                reportPlayerError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG, "onPlaybackStateChanged: $stateName")
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "state=$stateName attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)} " +
                        "bufferedMs=${exoPlayer.bufferedPosition.coerceAtLeast(0L)} " +
                        "durationMs=${exoPlayer.duration.coerceAtLeast(0L)} " +
                        "playWhenReady=${exoPlayer.playWhenReady} " +
                        "terminalError=${exoPlayer.playerError?.errorCodeName ?: "none"}",
                )
                if (playbackState == Player.STATE_READY) {
                    fallbackStartPositionMs = null
                    latestOnError.value(null)
                    exoPlayer.logCurrentTracks("STATE_READY")
                }
                syncPlayerViewKeepScreenOn()
                dispatchExoPlayerSnapshot()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "isPlaying=$isPlaying attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)}",
                )
                syncPlayerViewKeepScreenOn()
                dispatchExoPlayerSnapshot()
            }

            override fun onRenderedFirstFrame() {
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "firstFrame attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)}",
                )
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                dispatchExoPlayerSnapshot()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                latestOnSnapshot.value(exoPlayer.snapshot())
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
                playerViewRef?.post {
                    playerViewRef?.updateAssMargins(videoAspectRatio)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                Log.d(TAG, "onTracksChanged: ${tracks.groups.size} groups total")
                exoPlayer.logCurrentTracks("onTracksChanged")
                pendingAudioTrackSelection.firstOrNull()?.let { selection ->
                    if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                        pendingAudioTrackSelection.clear()
                        val restored = exoPlayer.restoreTrackSelection(selection)
                        Log.d(TAG, "onTracksChanged: restored pending audio selection=$restored")
                    }
                }
                if (pendingSubtitleTrackIndex.isNotEmpty() && tracks.groups.isNotEmpty()) {
                    val idx = pendingSubtitleTrackIndex.removeAt(0)
                    Log.d(TAG, "onTracksChanged: applying pending subtitle selection index=$idx")
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, idx < 0)
                        .build()
                    if (idx >= 0) {
                        exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, idx)
                    }
                }
                // Trigger player rebuild with video effects when ASS track becomes selected
                // and render mode is VIDEO_EFFECT.
                if (!videoEffectsNeeded && useLibass && libassRenderMode != "OVERLAY") {
                    val hasSelectedAss = tracks.groups.any { group ->
                        group.isSelected && group.type == C.TRACK_TYPE_TEXT &&
                            (0 until group.length).any { i ->
                                group.isTrackSelected(i) &&
                                    group.getTrackFormat(i).sampleMimeType == MimeTypes.TEXT_SSA
                            }
                    }
                    val hasDvVideo = tracks.groups.any { group ->
                        group.isSelected && group.type == C.TRACK_TYPE_VIDEO &&
                            (0 until group.length).any { i ->
                                group.getTrackFormat(i).sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
                            }
                    }
                    if (hasSelectedAss && !hasDvVideo) {
                        Log.d(TAG, "onTracksChanged: ASS track selected, triggering video effect rebuild")
                        // Save current subtitle selection to restore after rebuild.
                        // Find the selected text track index in the current tracks.
                        var selectedSubIdx = -1
                        var textIdx = 0
                        for (group in tracks.groups) {
                            if (group.type != C.TRACK_TYPE_TEXT) continue
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    selectedSubIdx = textIdx
                                }
                                textIdx++
                            }
                        }
                        if (selectedSubIdx >= 0) {
                            pendingSubtitleTrackIndex.clear()
                            pendingSubtitleTrackIndex.add(selectedSubIdx)
                        }
                        videoEffectsNeeded = true
                    }
                }
                dispatchExoPlayerSnapshot()
            }

        }
        exoPlayer.addListener(listener)
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            PlayerPictureInPictureManager.registerTogglePlaybackCallback(null)
            exoPlayer.removeListener(listener)
            playerViewRef?.keepScreenOn = false
            subtitleSelectionJob?.cancel()
            sidecarController.stopSidecarAddonSubtitle(clearView = true)
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> exoPlayer.playWhenReady = latestPlayWhenReady.value
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    val hasActiveNowPlayingSession = nowPlayingController.isActive
                    if ((!isInPictureInPicture && !hasActiveNowPlayingSession) || isFinishing) {
                        exoPlayer.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerViewRef?.releaseLibassOverlay()
            exoPlayer.releaseWithAssSupportCompat()
            if (VodCache.playheadBytesProvider != null) {
                VodCache.playheadBytesProvider = null
            }
            VodCache.evictCachedSession()
        }
    }

    DisposableEffect(exoPlayer, playerSettings.vodCacheEnabled) {
        if (!playerSettings.vodCacheEnabled) {
            return@DisposableEffect onDispose { }
        }
        val provider = {
            val duration = exoPlayer.duration
            val position = exoPlayer.currentPosition
            val bitrate = exoPlayer.videoFormat?.bitrate ?: 0
            if (duration > 0L && position > 0L && bitrate > 0) {
                position * bitrate.toLong() / 8_000L
            } else {
                0L
            }
        }
        VodCache.playheadBytesProvider = provider
        onDispose {
            if (VodCache.playheadBytesProvider === provider) {
                VodCache.playheadBytesProvider = null
            }
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        exoPlayer.playWhenReady = latestPlayWhenReady.value
        syncPlayerViewKeepScreenOn()
        dispatchExoPlayerSnapshot()
    }

    val latestChapterSourceUrl = rememberUpdatedState(sourceUrl)
    val latestChapterSourceHeaders = rememberUpdatedState(sanitizedSourceHeaders)
    LaunchedEffect(exoPlayer) {
        onControllerReady(
            autoSyncCoordinator.wrap(object : PlayerEngineController { // AutoSync hook
                override val playbackEngine = AndroidPlaybackEngine.ExoPlayer

                private val audioTrackCache = AudioTrackCache()

                override fun play() {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }

                // Media3's Matroska extractor skips chapters; read them from the file, if allowed.
                override suspend fun getChapters(): List<PlayerChapter> {
                    if (!PlayerSettingsRepository.uiState.value.androidExoChaptersEnabled) return emptyList()
                    return MatroskaChapterLoader.load(latestChapterSourceUrl.value, latestChapterSourceHeaders.value)
                }

                override fun pause() {
                    exoPlayer.pause()
                }

                override fun seekTo(positionMs: Long) {
                    exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
                }

                override fun seekBy(offsetMs: Long) {
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                }

                override fun retry() {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }

                override fun setPlaybackSpeed(speed: Float) {
                    exoPlayer.setPlaybackSpeed(speed)
                }

                override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
                    nowPlayingController.updateMetadata(info)
                }

                override fun clearNowPlayingInfo() {
                    nowPlayingController.clear()
                }

                override fun getAudioTracks(): List<AudioTrack> =
                    audioTrackCache.tracks(exoPlayer, context)

                override suspend fun getMediaInfo(): PlayerMediaInfo {
                    val video = exoPlayer.videoFormat
                    val audio = exoPlayer.audioFormat
                    return PlayerMediaInfo(
                        videoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(video?.sampleMimeType),
                        videoWidth = video?.width?.takeIf { it > 0 },
                        videoHeight = video?.height?.takeIf { it > 0 },
                        videoFrameRate = video?.frameRate?.takeIf { it > 0f },
                        videoBitrate = video?.bitrate?.takeIf { it > 0 },
                        audioCodec = CustomDefaultTrackNameProvider.formatNameFromMime(audio?.sampleMimeType),
                        audioChannels = audio?.channelCount?.takeIf { it > 0 },
                        audioSampleRate = audio?.sampleRate?.takeIf { it > 0 },
                        hdr = video?.hdrFormats().orEmpty(),
                        bitDepth = video?.colorInfo?.lumaBitdepth?.takeIf { it > 0 },
                    )
                }

                override fun getSubtitleTracks(): List<SubtitleTrack> {
                    val tracks = exoPlayer.extractSubtitleTracks(context)
                    Log.d(TAG, "getSubtitleTracks: found ${tracks.size} tracks")
                    tracks.forEach { t ->
                        Log.d(TAG, "  track idx=${t.index} id=${t.id} label='${t.label}' lang=${t.language} selected=${t.isSelected}")
                    }
                    return tracks
                }

                override fun selectAudioTrack(index: Int) {
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_AUDIO, index)
                }

                override fun applyAudioLanguagePreferences(languages: List<String>) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setPreferredAudioLanguages(*languages.toTypedArray())
                        .build()
                }

                override fun selectSubtitleTrack(index: Int) {
                    Log.d(TAG, "selectSubtitleTrack: index=$index")
                    sidecarController.stopSidecarAddonSubtitle(clearView = true)
                    if (index < 0) {
                        Log.d(TAG, "selectSubtitleTrack: disabling text tracks")
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        return
                    }
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, index)
                    Log.d(TAG, "selectSubtitleTrack: after selection, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                    exoPlayer.logCurrentTracks("after selectSubtitleTrack")
                }

                override fun setSubtitleUri(url: String) {
                    Log.d(TAG, "setSubtitleUri: url=${url.redactedForLogs()}")
                    subtitleSelectionJob?.cancel()
                    if (sidecarController.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
                        Log.d(TAG, "setSubtitleUri: using buffer-preserving sidecar for url=${url.redactedForLogs()}")
                        val headers = externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()
                        val attached = sidecarController.startSidecarAddonSubtitle(
                            url = url,
                            headers = headers,
                            useLibass = useLibass,
                        )
                        if (attached) {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            return
                        }
                    }
                    subtitleSelectionJob = coroutineScope.launch {
                        val currentPosition = exoPlayer.currentPosition
                        val wasPlaying = exoPlayer.isPlaying
                        val currentMediaItem = exoPlayer.currentMediaItem ?: run {
                            Log.e(TAG, "setSubtitleUri: currentMediaItem is null, aborting")
                            return@launch
                        }
                        preserveAudioSelectionForReload("setSubtitleUri")
                        val resolvedMime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
                        selectedExternalSubtitleMimeType = resolvedMime
                        Log.d(TAG, "setSubtitleUri: currentPosition=$currentPosition, wasPlaying=$wasPlaying")
                        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                            .setMimeType(resolvedMime)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(
                            TAG,
                            "setSubtitleUri: subtitleConfig built, uri=${subtitleConfig.uri}, mime=${subtitleConfig.mimeType}, selectionFlags=${subtitleConfig.selectionFlags}"
                        )
                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(listOf(subtitleConfig))
                            .build()
                        Log.d(TAG, "setSubtitleUri: newMediaItem subtitleConfigs count=${newMediaItem.localConfiguration?.subtitleConfigurations?.size}")
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(TAG, "setSubtitleUri: track params set before prepare, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                        exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = wasPlaying
                        Log.d(TAG, "setSubtitleUri: prepare() called, waiting for STATE_READY")
                    }
                }

                override fun clearExternalSubtitle() {
                    Log.d(TAG, "clearExternalSubtitle called")
                    subtitleSelectionJob?.cancel()
                    sidecarController.stopSidecarAddonSubtitle(clearView = true)
                    selectedExternalSubtitleMimeType = null
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    if (currentMediaItem.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) {
                        preserveAudioSelectionForReload("clearExternalSubtitle")
                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(emptyList())
                            .build()
                        exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = wasPlaying
                    } else {
                        selectSubtitleTrack(-1)
                    }
                    Log.d(TAG, "clearExternalSubtitle: done, position=$currentPosition")
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    Log.d(TAG, "clearExternalSubtitleAndSelect: trackIndex=$trackIndex")
                    subtitleSelectionJob?.cancel()
                    sidecarController.stopSidecarAddonSubtitle(clearView = true)
                    selectedExternalSubtitleMimeType = null
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    if (currentMediaItem.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) {
                        pendingSubtitleTrackIndex.clear()
                        pendingSubtitleTrackIndex.add(trackIndex)
                        preserveAudioSelectionForReload("clearExternalSubtitleAndSelect")
                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(emptyList())
                            .build()
                        exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = wasPlaying
                    } else {
                        pendingSubtitleTrackIndex.clear()
                        selectSubtitleTrack(trackIndex)
                    }
                    Log.d(TAG, "clearExternalSubtitleAndSelect: done, pending=$trackIndex position=$currentPosition")
                }

                override fun applySubtitleStyle(style: SubtitleStyleState) {
                    currentSubtitleStyle = style
                    playerViewRef?.applySubtitleStyle(style, pipVideoHeightPx)
                }

                override fun applySubtitlePreferences(
                    preferredLanguage: String,
                    secondaryPreferredLanguage: String?,
                    useForcedSubtitles: Boolean,
                    autoSelectionApplied: Boolean,
                    hasActiveSubtitle: Boolean,
                    useCustomSubtitles: Boolean,
                ) {
                    exoPlayer.applySubtitleTrackPreferences(
                        preferredLanguage = preferredLanguage,
                        useForcedSubtitles = useForcedSubtitles,
                        autoSelectionApplied = autoSelectionApplied,
                        hasActiveSubtitle = hasActiveSubtitle,
                        useCustomSubtitles = useCustomSubtitles,
                    )
                }

                override fun setSubtitleDelayMs(delayMs: Int) {
                    subtitleDelayMs = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                    exoPlayer.getAssHandlerCompat()?.let {
                        it.subtitleDelayUs = subtitleDelayMs.toLong() * 1000L
                    }
                }
            }),
        )
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            dispatchExoPlayerSnapshot()
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = useNativeController
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                player = exoPlayer
                keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
                this.resizeMode = resizeMode.toExoResizeMode()
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                playerViewRef = this
                sidecarController.bindSubtitleView(this.subtitleView)
                subtitleView?.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        context.findActivity()?.isInPictureInPictureMode == true
                    val size = IntSize(
                        view.width - view.paddingLeft - view.paddingRight,
                        view.height - view.paddingTop - view.paddingBottom,
                    )
                    if (!inPip && size.width > 0 && size.height > 0) playerSizeOutsidePip = size
                }
                syncLibassOverlay(
                    player = exoPlayer,
                    enabled = useLibass,
                    renderMode = libassRenderMode,
                    tunnelingEnabled = playerSettings.tunnelingEnabled,
                )
                if (subtitleStyleGuard.shouldApply(this, currentSubtitleStyle, pipVideoHeightPx)) {
                    applySubtitleStyle(currentSubtitleStyle, pipVideoHeightPx)
                }
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
            playerView.useController = useNativeController
            playerView.resizeMode = resizeMode.toExoResizeMode()
            playerViewRef = playerView
            sidecarController.bindSubtitleView(playerView.subtitleView)
            syncPlayerViewKeepScreenOn()
            playerView.syncLibassOverlay(
                player = exoPlayer,
                enabled = useLibass,
                renderMode = libassRenderMode,
                tunnelingEnabled = playerSettings.tunnelingEnabled,
            )
            if (subtitleStyleGuard.shouldApply(playerView, currentSubtitleStyle, pipVideoHeightPx)) {
                playerView.applySubtitleStyle(currentSubtitleStyle, pipVideoHeightPx)
            }
        },
    )
}

@Composable
private fun LibmpvPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    videoOutput: AndroidLibmpvVideoOutput,
    hardwareDecodingEnabled: Boolean,
    yuv420pEnabled: Boolean,
    preferredSubtitleLanguage: String?,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()
    val playbackDiagnostics = remember { PlaybackDiagnostics() }
    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    val autoSyncCoordinator = rememberLibmpvAutoSyncCoordinator( // AutoSync hook
        scope = coroutineScope,
        sourceUrl = sourceUrl,
        sourceHeaders = sanitizedSourceHeaders,
        externalSubtitles = externalSubtitles,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
    )
    var playerViewRef by remember { mutableStateOf<NuvioLibmpvView?>(null) }
    val nowPlayingController = remember(context, playerViewRef) {
        playerViewRef?.let { view ->
            AndroidPlayerNowPlayingController(
                context = context,
                controls = AndroidPlayerNowPlayingController.PlaybackControls(
                    play = { view.setPaused(false) },
                    pause = { view.setPaused(true) },
                    seekTo = { positionMs -> view.seekToMs(positionMs) },
                    seekBy = { offsetMs -> view.seekByMs(offsetMs) },
                ),
            )
        }
    }

    DisposableEffect(nowPlayingController) {
        onDispose { nowPlayingController?.release() }
    }

    val audioFocus = remember(context) {
        PlayerAudioFocus(context) { playerViewRef?.setPaused(true) }
    }

    DisposableEffect(audioFocus) {
        onDispose { audioFocus.abandon() }
    }

    DisposableEffect(lifecycleOwner, nowPlayingController) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            val view = playerViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.setPaused(!latestPlayWhenReady.value)
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    val hasActiveNowPlayingSession = nowPlayingController?.isActive == true
                    if ((!isInPictureInPicture && !hasActiveNowPlayingSession) || isFinishing) {
                        view.setPaused(true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playerViewRef, nowPlayingController) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        fun dispatchSnapshot(updateKeepScreenOn: Boolean = false) {
            coroutineScope.launch(Dispatchers.Main.immediate) {
                val snapshot = view.snapshot()
                latestOnSnapshot.value(snapshot)
                nowPlayingController?.syncPlayback(snapshot)
                if (updateKeepScreenOn) {
                    view.keepScreenOn = snapshot.isPlaying || snapshot.isLoading
                }
            }
        }
        val observer = object : MPV.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) {
                if (property == "cache-buffering-state") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: Boolean) {
                if (property == "eof-reached" && value) {
                    Log.w(
                        PLAYER_DIAGNOSTIC_TAG,
                        "mpv_property=eof-reached value=true attempt=${playbackDiagnostics.attempt} " +
                            "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)}",
                    )
                }
                if (property == "pause" && !value) {
                    coroutineScope.launch(Dispatchers.Main.immediate) { audioFocus.request() }
                }
                if (property == "eof-reached" || property == "pause" || property == "paused-for-cache" || property == "seeking") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: String) = Unit
            override fun eventProperty(property: String, value: Double) {
                if (property == "duration" || property == "time-pos" || property == "speed") {
                    dispatchSnapshot()
                }
            }
            override fun eventProperty(property: String, value: MPVNode) {
                if (property == "track-list") {
                    view.refreshTracks(context)
                    dispatchSnapshot()
                }
            }
            override fun event(eventId: Int, data: MPVNode) {
                when (eventId) {
                    MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                        Log.i(
                            PLAYER_DIAGNOSTIC_TAG,
                            "mpv_event=START_FILE attempt=${playbackDiagnostics.attempt} " +
                                "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)}",
                        )
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            val snapshot = PlayerPlaybackSnapshot()
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_FILE_LOADED,
                    MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            val snapshot = view.snapshot()
                            Log.i(
                                PLAYER_DIAGNOSTIC_TAG,
                                "mpv_event=${if (eventId == MPV.mpvEvent.MPV_EVENT_FILE_LOADED) "FILE_LOADED" else "PLAYBACK_RESTART"} " +
                                    "attempt=${playbackDiagnostics.attempt} " +
                                    "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                                    "positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedPositionMs} " +
                                    "durationMs=${snapshot.durationMs}",
                            )
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            val snapshot = view.snapshot()
                            Log.w(
                                PLAYER_DIAGNOSTIC_TAG,
                                "mpv_event=END_FILE attempt=${playbackDiagnostics.attempt} " +
                                    "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                                    "positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedPositionMs} " +
                                    "durationMs=${snapshot.durationMs} eof=${snapshot.isEnded} " +
                                    "data=${diagnosticPlayerMessage(data.toJson())}",
                            )
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                            view.keepScreenOn = snapshot.isPlaying || snapshot.isLoading
                        }
                    }
                }
            }
        }
        val logObserver = object : MPV.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                Log.w(
                    PLAYER_DIAGNOSTIC_TAG,
                    "mpv_log level=$level prefix=${diagnosticPlayerMessage(prefix)} " +
                        "message=${diagnosticPlayerMessage(text)}",
                )
            }
        }
        view.mpv.addObserver(observer)
        view.mpv.addLogObserver(logObserver)
        onDispose {
            view.mpv.removeObserver(observer)
            view.mpv.removeLogObserver(logObserver)
        }
    }

    DisposableEffect(playerViewRef) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            view.setPaused(true)
        }
        PlayerPictureInPictureManager.registerTogglePlaybackCallback {
            coroutineScope.launch {
                val snapshot = view.snapshot()
                if (snapshot.isPlaying) {
                    view.setPaused(true)
                } else {
                    if (snapshot.isEnded) {
                        view.seekToMs(0L)
                    }
                    view.setPaused(false)
                }
            }
        }
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            PlayerPictureInPictureManager.registerTogglePlaybackCallback(null)
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        playbackDiagnostics.attempt += 1
        playbackDiagnostics.prepareStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            PLAYER_DIAGNOSTIC_TAG,
            "mpv_prepare_begin attempt=${playbackDiagnostics.attempt} " +
                "source=${diagnosticPlaybackSource(sourceUrl)} audioSource=${!sourceAudioUrl.isNullOrBlank()}",
        )
        val snapshot = PlayerPlaybackSnapshot()
        latestOnSnapshot.value(snapshot)
        nowPlayingController?.syncPlayback(snapshot)
        view.loadSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            requestHeaders = sanitizedSourceHeaders,
            externalSubtitles = externalSubtitles,
            playWhenReady = latestPlayWhenReady.value,
        )
    }

    LaunchedEffect(playerViewRef, playWhenReady) {
        val view = playerViewRef ?: return@LaunchedEffect
        view.setPaused(!latestPlayWhenReady.value)
        val snapshot = view.snapshot()
        view.keepScreenOn = snapshot.isPlaying || snapshot.isLoading
        latestOnSnapshot.value(snapshot)
        nowPlayingController?.syncPlayback(snapshot)
    }

    LaunchedEffect(playerViewRef, resizeMode) {
        playerViewRef?.applyResizeMode(resizeMode)
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        onControllerReady(
            autoSyncCoordinator.wrap( // AutoSync hook
                view.controller(context, nowPlayingController),
                view::replaceExternalSubtitle,
            ),
        )
    }

    LaunchedEffect(playerViewRef) {
        val view = playerViewRef ?: return@LaunchedEffect
        while (isActive) {
            val snapshot = view.snapshot()
            latestOnSnapshot.value(snapshot)
            nowPlayingController?.syncPlayback(snapshot)
            view.keepScreenOn = snapshot.isPlaying || snapshot.isLoading
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            NuvioLibmpvView(
                context = viewContext,
                videoOutput = videoOutput,
                hardwareDecodingEnabled = hardwareDecodingEnabled,
                yuv420pEnabled = yuv420pEnabled,
            ).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                keepScreenOn = false
                runCatching {
                    Utils.copyAssets(viewContext)
                    initialize(viewContext.filesDir.path, viewContext.cacheDir.path)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to initialize libmpv", error)
                    latestOnError.value(error.localizedMessage ?: "libmpv unavailable")
                }
                playerViewRef = this
            }
        },
        update = { view ->
            playerViewRef = view
            view.applyResizeMode(resizeMode)
        },
        onRelease = { view ->
            if (playerViewRef === view) playerViewRef = null
            view.releaseMpv()
        },
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private class NuvioLibmpvView(
    context: Context,
    private val videoOutput: AndroidLibmpvVideoOutput,
    private val hardwareDecodingEnabled: Boolean,
    private val yuv420pEnabled: Boolean,
    attrs: AttributeSet? = null,
) : BaseMPVView(context, attrs) {
    private val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    private val released = AtomicBoolean(false)
    private var currentSourceUrl: String? = null
    private var currentSourceAudioUrl: String? = null
    private var currentRequestHeaders: Map<String, String> = emptyMap()
    private var currentExternalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList()
    @Volatile
    private var latestSnapshot = PlayerPlaybackSnapshot()
    @Volatile
    private var latestAudioTracks: List<LibmpvTrack> = emptyList()
    @Volatile
    private var latestSubtitleTracks: List<LibmpvTrack> = emptyList()

    override fun initOptions() {
        setVo(videoOutput.mpvValue)
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", if (hardwareDecodingEnabled) "auto" else "no")
        if (yuv420pEnabled) {
            mpv.setOptionString("vf", "format=yuv420p")
        }
        mpv.setOptionString("msg-level", "all=warn")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("demuxer-max-bytes", "${libmpvCacheBytes()}").logIfMpvError("demuxer-max-bytes")
        mpv.setOptionString("demuxer-max-back-bytes", "${libmpvCacheBytes()}").logIfMpvError("demuxer-max-back-bytes")
        mpv.setOptionString("vd-lavc-film-grain", "cpu")
        mpv.setPropertyBoolean("keep-open", true)
        mpv.setPropertyBoolean("input-default-bindings", true)
        mpv.setPropertyBoolean("audio-fallback-to-null", true)
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() {
        val props = mapOf(
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    suspend fun loadSource(
        sourceUrl: String,
        sourceAudioUrl: String?,
        requestHeaders: Map<String, String>,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
        playWhenReady: Boolean,
    ) = withContext(mpvDispatcher) {
        if (!released.get()) {
            val sameSource =
                currentSourceUrl == sourceUrl &&
                    currentSourceAudioUrl == sourceAudioUrl &&
                    currentRequestHeaders == requestHeaders &&
                    currentExternalSubtitles == externalSubtitles
            currentSourceUrl = sourceUrl
            currentSourceAudioUrl = sourceAudioUrl
            currentRequestHeaders = requestHeaders
            currentExternalSubtitles = externalSubtitles
            if (!sameSource) {
                loadCurrentSourceNow(playWhenReady = playWhenReady)
            } else {
                applyRequestHeadersNow(requestHeaders)
                setPausedNow(!playWhenReady)
            }
        }
    }

    private fun loadCurrentSourceNow(playWhenReady: Boolean) {
        val sourceUrl = currentSourceUrl ?: return
        applyRequestHeadersNow(currentRequestHeaders)
        setPausedNow(!playWhenReady)
        mpv.setPropertyString("aid", "auto")
        mpv.command("loadfile", sourceUrl.toMpvSource(), "replace")
        currentSourceAudioUrl?.takeIf { it.isNotBlank() }?.let { sourceAudioUrl ->
            mpv.command("audio-add", sourceAudioUrl.toMpvSource(), "auto")
        }
        currentExternalSubtitles.forEachIndexed { index, subtitle ->
            val flag = if (index == 0) "auto" else "cached"
            mpv.command("sub-add", subtitle.url.toMpvSource(), flag, subtitle.name ?: subtitle.language, subtitle.language)
        }
        setPausedNow(!playWhenReady)
    }

    private fun String.toMpvSource(): String =
        if (!startsWith("file:", ignoreCase = true)) {
            this
        } else {
            runCatching { File(URI(this)).absolutePath }.getOrDefault(this)
        }

    fun setPaused(paused: Boolean) {
        executeMpv { setPausedNow(paused) }
    }

    fun seekToMs(positionMs: Long) {
        executeMpv {
            mpv.command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
        }
    }

    /** mpv lists the chapters of the loaded file itself. */
    suspend fun readChapters(): List<PlayerChapter> {
        if (released.get()) return emptyList()
        return runCatching {
            withContext(mpvDispatcher) {
                if (released.get()) return@withContext emptyList()
                val count = mpv.getPropertyInt("chapter-list/count") ?: 0
                (0 until count).mapNotNull { i ->
                    val seconds = mpv.getPropertyDouble("chapter-list/$i/time") ?: return@mapNotNull null
                    PlayerChapter(
                        startMs = (seconds * 1000.0).toLong(),
                        title = mpv.getPropertyString("chapter-list/$i/title")?.trim()?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun snapshot(): PlayerPlaybackSnapshot {
        if (released.get()) return latestSnapshot
        return withContext(mpvDispatcher) {
            if (released.get()) {
                latestSnapshot
            } else {
                runCatching { readSnapshotNow() }
                    .onSuccess { latestSnapshot = it }
                    .getOrDefault(latestSnapshot)
            }
        }
    }

    suspend fun mediaInfo(): PlayerMediaInfo {
        if (released.get()) return PlayerMediaInfo()
        return withContext(mpvDispatcher) {
            if (released.get()) {
                PlayerMediaInfo()
            } else {
                runCatching { mpvMediaInfo(mpv::getPropertyString) }.getOrDefault(PlayerMediaInfo())
            }
        }
    }

    private fun readSnapshotNow(): PlayerPlaybackSnapshot {
        val paused = mpv.getPropertyBoolean("pause") ?: true
        val pausedForCache = mpv.getPropertyBoolean("paused-for-cache") ?: false
        val idle = mpv.getPropertyBoolean("core-idle") ?: false
        val ended = mpv.getPropertyBoolean("eof-reached") ?: false
        val seeking = mpv.getPropertyBoolean("seeking") ?: false
        val cacheBufferingState = mpv.getPropertyInt("cache-buffering-state")
        val durationMs = mpv.getPropertyDouble("duration").toMillis()
        val positionMs = mpv.getPropertyDouble("time-pos").toMillis()
        val cachePositionMs = mpv.getPropertyDouble("demuxer-cache-time").toMillis()
        val isCacheBuffering = cacheBufferingState != null && cacheBufferingState in 0 until 100
        val isLoading = pausedForCache ||
            (!paused && !ended && (seeking || isCacheBuffering || (idle && durationMs <= 0L)))
        val videoWidth = mpv.getPropertyInt("video-out-params/dw")
            ?: mpv.getPropertyInt("video-params/dw")
            ?: 0
        val videoHeight = mpv.getPropertyInt("video-out-params/dh")
            ?: mpv.getPropertyInt("video-params/dh")
            ?: 0
        return PlayerPlaybackSnapshot(
            isLoading = isLoading,
            isPlaying = !paused && !isLoading && !idle && !ended,
            isEnded = ended,
            durationMs = durationMs,
            positionMs = positionMs,
            bufferedPositionMs = maxOf(positionMs, cachePositionMs),
            playbackSpeed = (mpv.getPropertyDouble("speed") ?: 1.0).toFloat(),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }

    fun applyResizeMode(resizeMode: PlayerResizeMode) {
        executeMpv {
            when (resizeMode) {
                PlayerResizeMode.Fit -> {
                    mpv.setPropertyDouble("panscan", 0.0)
                    mpv.setPropertyString("video-aspect-override", "no")
                }
                PlayerResizeMode.Fill -> {
                    mpv.setPropertyDouble("panscan", 1.0)
                    mpv.setPropertyString("video-aspect-override", "no")
                }
                PlayerResizeMode.Zoom -> {
                    mpv.setPropertyDouble("panscan", 0.5)
                    mpv.setPropertyString("video-aspect-override", "no")
                }
            }
        }
    }

    fun seekByMs(offsetMs: Long) {
        executeMpv {
            mpv.command("seek", (offsetMs / 1000.0).toString(), "relative")
        }
    }

    fun controller(
        context: Context,
        nowPlayingController: AndroidPlayerNowPlayingController?,
    ): PlayerEngineController =
        object : PlayerEngineController {
            override val playbackEngine = AndroidPlaybackEngine.Libmpv

            override fun play() = setPaused(false)

            override fun pause() = setPaused(true)

            override fun seekTo(positionMs: Long) = this@NuvioLibmpvView.seekToMs(positionMs)

            override fun seekBy(offsetMs: Long) = this@NuvioLibmpvView.seekByMs(offsetMs)

            override suspend fun getChapters(): List<PlayerChapter> = this@NuvioLibmpvView.readChapters()

            override fun retry() {
                executeMpv { loadCurrentSourceNow(playWhenReady = true) }
            }

            override suspend fun getMediaInfo(): PlayerMediaInfo = mediaInfo()

            override fun setPlaybackSpeed(speed: Float) {
                executeMpv {
                    mpv.setPropertyDouble("speed", speed.coerceIn(0.25f, 4f).toDouble())
                }
            }

            override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
                nowPlayingController?.updateMetadata(info)
            }

            override fun clearNowPlayingInfo() {
                nowPlayingController?.clear()
            }

            override fun setMuted(muted: Boolean) {
                executeMpv { mpv.setPropertyBoolean("mute", muted) }
            }

            override fun getAudioTracks(): List<AudioTrack> =
                latestAudioTracks.mapIndexed { index, track ->
                    AudioTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                    )
                }

            override fun getSubtitleTracks(): List<SubtitleTrack> =
                latestSubtitleTracks.mapIndexed { index, track ->
                    SubtitleTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                        isForced = track.isForced,
                    )
                }

            override fun selectAudioTrack(index: Int) {
                if (index < 0) {
                    executeMpv { mpv.setPropertyString("aid", "no") }
                } else {
                    latestAudioTracks.getOrNull(index)?.let { track ->
                        executeMpv { mpv.setPropertyInt("aid", track.id) }
                    }
                }
            }

            override fun applyAudioLanguagePreferences(languages: List<String>) {
                executeMpv {
                    mpv.setPropertyString("alang", languages.joinToString(","))
                    mpv.getPropertyString("aid")?.takeIf { it.toIntOrNull() != null }?.let { currentId ->
                        mpv.setPropertyString("aid", currentId)
                    }
                    mpv.setPropertyString("aid", "auto")
                }
            }

            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    executeMpv { mpv.setPropertyString("sid", "no") }
                } else {
                    latestSubtitleTracks.getOrNull(index)?.let { track ->
                        executeMpv { mpv.setPropertyInt("sid", track.id) }
                    }
                }
            }

            override fun setSubtitleUri(url: String) {
                executeMpv { mpv.command("sub-add", url, "select") }
            }

            override fun clearExternalSubtitle() {
                executeMpv { mpv.setPropertyString("sid", "no") }
            }

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                selectSubtitleTrack(trackIndex)
            }

            override fun applySubtitlePreferences(
                preferredLanguage: String,
                secondaryPreferredLanguage: String?,
                useForcedSubtitles: Boolean,
                autoSelectionApplied: Boolean,
                hasActiveSubtitle: Boolean,
                useCustomSubtitles: Boolean,
            ) {
                if ((hasActiveSubtitle || useCustomSubtitles) && autoSelectionApplied) {
                    return
                }
                val languages = listOfNotNull(
                    preferredLanguage.takeIf { language ->
                        language.isNotBlank() &&
                            !language.equals(SubtitleLanguageOption.NONE, ignoreCase = true) &&
                            !language.equals(SubtitleLanguageOption.FORCED, ignoreCase = true)
                    },
                    secondaryPreferredLanguage?.takeIf { language ->
                        language.isNotBlank() &&
                            !language.equals(SubtitleLanguageOption.NONE, ignoreCase = true) &&
                            !language.equals(SubtitleLanguageOption.FORCED, ignoreCase = true)
                    },
                )
                if (languages.isEmpty()) {
                    mpv.setPropertyString("sid", "no")
                    return
                }
                runCatching {
                    mpv.setPropertyString("slang", languages.joinToString(","))
                }
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) {
                executeMpv {
                    mpv.setPropertyString("sub-ass-override", "no")
                    mpv.setPropertyString("sub-color", style.textColor.toMpvColor())
                    mpv.setPropertyString("sub-back-color", style.backgroundColor.toMpvColor())
                    mpv.setPropertyString("sub-outline-color", style.outlineColor.toMpvColor())
                    mpv.setPropertyString("sub-border-color", style.outlineColor.toMpvColor())
                    mpv.setPropertyString("sub-border-style", style.toMpvSubtitleBorderStyle())
                    mpv.setPropertyString("sub-bold", if (style.bold) "yes" else "no")
                    mpv.setPropertyInt("sub-font-size", style.toMpvSubtitleFontSize())
                    mpv.setPropertyInt("sub-outline-size", style.toMpvSubtitleOutlineSize())
                    mpv.setPropertyInt("sub-border-size", style.toMpvSubtitleOutlineSize())
                    mpv.setPropertyInt("sub-pos", (100 - style.bottomOffset / 10).coerceIn(0, 100))
                    mpv.setPropertyBoolean("sub-filter-sdh", style.stripSdh)
                    mpv.setPropertyBoolean("sub-filter-sdh-harder", style.stripSdh)
                }
            }

            override fun setSubtitleDelayMs(delayMs: Int) {
                executeMpv {
                    mpv.setPropertyDouble(
                        "sub-delay",
                        delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS) / 1000.0,
                    )
                }
            }
        }

    /**
     * Loads the local subtitle file at [path] in place of the external track loaded from
     * [replacedUrl], under that track's label and language. Earlier AutoSync copies are dropped too.
     */
    fun replaceExternalSubtitle(replacedUrl: String, path: String) {
        executeMpv {
            fun externalSubtitleTracks() = mpv.getPropertyNode("track-list")?.asArray()?.toList().orEmpty()
                .filter { node -> node.nodeString("type") == "sub" && node.nodeBoolean("external") == true }

            val replacedSource = replacedUrl.toMpvSource()
            val earlierCopyPrefix = "${path.substringBeforeLast('/')}/$LIBMPV_AUTOSYNC_FILE_PREFIX"
            val replaced = externalSubtitleTracks()
                .lastOrNull { node -> node.nodeString("external-filename") == replacedSource }
            val title = replaced?.let { node ->
                node.nodeString("title") ?: node.nodeString("external-filename")?.substringAfterLast('/')
            }
            val language = replaced?.nodeString("lang")
            when {
                title != null && language != null -> mpv.command("sub-add", path, "cached", title, language)
                title != null -> mpv.command("sub-add", path, "cached", title)
                else -> mpv.command("sub-add", path, "cached")
            }
            externalSubtitleTracks()
                .filter { node ->
                    val filename = node.nodeString("external-filename") ?: return@filter false
                    filename != path &&
                        (filename == replacedSource || filename.startsWith(earlierCopyPrefix))
                }
                .mapNotNull { node -> node.nodeInt("id") }
                .forEach { id -> mpv.command("sub-remove", id.toString()) }
        }
    }

    fun refreshTracks(context: Context) {
        executeMpv {
            latestAudioTracks = extractLibmpvTracks(context, type = "audio")
            latestSubtitleTracks = extractLibmpvTracks(context, type = "sub")
        }
    }

    fun releaseMpv() {
        if (!released.compareAndSet(false, true)) return
        holder.removeCallback(this)
        mpvScope.launch {
            runCatching { mpv.destroy() }
            mpvDispatcher.close()
        }
    }

    private fun setPausedNow(paused: Boolean) {
        runCatching { mpv.setPropertyBoolean("pause", paused) }
    }

    private fun executeMpv(block: () -> Unit) {
        if (released.get()) return
        mpvScope.launch {
            if (released.get()) return@launch
            runCatching(block).onFailure { error ->
                Log.w(TAG, "libmpv operation failed", error)
            }
        }
    }

    private fun applyRequestHeadersNow(headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!userAgent.isNullOrBlank()) {
            mpv.setPropertyString("user-agent", userAgent)
        }
        val serialized = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .map { (key, value) -> "${key}: ${value.replace(",", "\\,")}" }
            .joinToString(",")
        mpv.setPropertyString("http-header-fields", serialized)
    }

    private fun extractLibmpvTracks(context: Context, type: String): List<LibmpvTrack> {
        val nodes = mpv.getPropertyNode("track-list")?.asArray()?.toList().orEmpty()
        return nodes
            .filter { node -> node.nodeString("type") == type }
            .mapIndexedNotNull { index, node ->
                val id = node.nodeInt("id") ?: return@mapIndexedNotNull null
                val rawLabel = node.nodeString("title")
                    ?: node.nodeString("external-filename")?.substringAfterLast('/')
                    ?: node.nodeString("codec")
                val language = node.nodeString("lang") ?: normalizeLanguageCode(rawLabel)
                val label = rawLabel?.takeIf { it.isNotBlank() }
                    ?: runBlocking { getString(Res.string.compose_player_track_number, index + 1) }
                LibmpvTrack(
                    id = id,
                    label = label,
                    language = language,
                    isSelected = node.nodeBoolean("selected") ?: false,
                    isForced = inferForcedSubtitleTrack(
                        label = label,
                        language = language,
                        trackId = id.toString(),
                        hasForcedSelectionFlag = node.nodeBoolean("forced") ?: false,
                    ),
                )
            }
    }
}

private data class LibmpvTrack(
    val id: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val isForced: Boolean,
)

private fun libmpvCacheBytes(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 * 1024 * 1024 else 32 * 1024 * 1024

private fun Int.logIfMpvError(option: String) {
    if (this < 0) Log.w(TAG, "libmpv option failed: $option status=$this")
}

private fun Double?.toMillis(): Long =
    this?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L

private fun MPVNode.nodeString(key: String): String? =
    runCatching { this[key]?.asString() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun MPVNode.nodeInt(key: String): Int? =
    runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.nodeBoolean(key: String): Boolean? =
    runCatching { this[key]?.asBoolean() }.getOrNull()

private fun androidx.compose.ui.graphics.Color.toMpvColor(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xff
    val red = (argb shr 16) and 0xff
    val green = (argb shr 8) and 0xff
    val blue = argb and 0xff
    return "#%02X%02X%02X%02X".format(alpha, red, green, blue)
}

private fun androidx.compose.ui.graphics.Color.alphaByte(): Int =
    (toArgb() ushr 24) and 0xff

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Int =
    (fontSizeSp * MPV_SUBTITLE_FONT_SIZE_SCALE).toInt().coerceIn(
        MPV_SUBTITLE_FONT_SIZE_MIN,
        MPV_SUBTITLE_FONT_SIZE_MAX,
    )

private fun SubtitleStyleState.toMpvSubtitleOutlineSize(): Int =
    if (!outlineEnabled) 0 else (outlineWidth * MPV_SUBTITLE_OUTLINE_SIZE_SCALE).toInt().coerceAtLeast(1)

private fun SubtitleStyleState.toMpvSubtitleBorderStyle(): String =
    if (outlineEnabled) {
        "outline-and-shadow"
    } else if (backgroundColor.alphaByte() > 0) {
        "opaque-box"
    } else {
        "outline-and-shadow"
    }

private const val MPV_SUBTITLE_FONT_SIZE_SCALE = 55.0 / 18.0
private const val MPV_SUBTITLE_FONT_SIZE_MIN = 36
private const val MPV_SUBTITLE_FONT_SIZE_MAX = 122
private const val MPV_SUBTITLE_OUTLINE_SIZE_SCALE = 1.5

private fun ExoPlayer.snapshot(): PlayerPlaybackSnapshot {
    val (videoWidth, videoHeight) = videoDimensions()
    return PlayerPlaybackSnapshot(
        isLoading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING,
        isPlaying = isPlaying,
        isEnded = playbackState == Player.STATE_ENDED,
        durationMs = duration.coerceAtLeast(0L),
        positionMs = currentPosition.coerceAtLeast(0L),
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        playbackSpeed = playbackParameters.speed,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )
}

private fun ExoPlayer.videoDimensions(): Pair<Int, Int> {
    val format = videoFormat ?: return videoSize.width to videoSize.height
    val hasCrop = format.decodedWidth != Format.NO_VALUE &&
        format.decodedHeight != Format.NO_VALUE &&
        (format.decodedWidth > format.width || format.decodedHeight > format.height)
    val baseWidth = if (hasCrop) format.width else (format.width.takeIf { it > 0 } ?: videoSize.width)
    val baseHeight = if (hasCrop) format.height else (format.height.takeIf { it > 0 } ?: videoSize.height)
    val ratio = format.pixelWidthHeightRatio
    return if (ratio != 1f) (baseWidth * ratio).roundToInt() to baseHeight else baseWidth to baseHeight
}

private fun ExoPlayer.shouldKeepPlayerScreenOn(): Boolean =
    playerError == null &&
        playWhenReady &&
        playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)

private data class TrackSelectionSnapshot(
    val trackType: Int,
    val index: Int,
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    val roleFlags: Int,
)

private fun ExoPlayer.captureSelectedTrack(trackType: Int): TrackSelectionSnapshot? {
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        if (group.isSelected) {
            val format = group.mediaTrackGroup.getFormat(0)
            return TrackSelectionSnapshot(
                trackType = trackType,
                index = idx,
                id = format.id,
                language = format.language,
                label = format.label,
                sampleMimeType = format.sampleMimeType,
                codecs = format.codecs,
                channelCount = format.channelCount,
                roleFlags = format.roleFlags,
            )
        }
        idx++
    }
    return null
}

private fun ExoPlayer.restoreTrackSelection(selection: TrackSelectionSnapshot): Boolean {
    selection.id?.takeIf { it.isNotBlank() }?.let { id ->
        val restored = selectTrackByPredicate(selection.trackType, "id=$id") { _, format ->
            format.id == id
        }
        if (restored) {
            return true
        }
    }

    selection.label?.takeIf { it.isNotBlank() }?.let { label ->
        val restored = selectTrackByPredicate(selection.trackType, "label=$label") { _, format ->
            format.label.equals(label, ignoreCase = true) &&
                (selection.language.isNullOrBlank() ||
                    format.language.equals(selection.language, ignoreCase = true))
        }
        if (restored) {
            return true
        }
    }

    val technicalMatchIndexes = mutableListOf<Int>()
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != selection.trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (
            !selection.language.isNullOrBlank() &&
            format.language.equals(selection.language, ignoreCase = true) &&
            format.sampleMimeType == selection.sampleMimeType &&
            format.codecs == selection.codecs &&
            format.channelCount == selection.channelCount &&
            format.roleFlags == selection.roleFlags
        ) {
            technicalMatchIndexes.add(idx)
        }
        idx++
    }
    if (technicalMatchIndexes.size == 1) {
        return selectTrackByIndex(selection.trackType, technicalMatchIndexes.first())
    }

    return selectTrackByIndex(selection.trackType, selection.index)
}

private fun PlaybackException.isDecoderFailure(): Boolean =
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

private fun PlayerResizeMode.toExoResizeMode(): Int =
    when (this) {
        PlayerResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderMode: String = "VIDEO_EFFECT",
    tunnelingEnabled: Boolean = false,
) {
    // Atlas pipeline always draws through AssSubtitleSurfaceView in the top-level overlay container.
    val containerId = R.id.libass_overlay_container_gl
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    // Overlay is needed only in OVERLAY render mode or tunneled playback.
    // VIDEO_EFFECT draws subtitles directly onto each video frame - no overlay needed.
    val needsOverlay = enabled && (renderMode == "OVERLAY" || tunnelingEnabled)
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            boundPlayer.getAssHandlerCompat()?.videoFrameCallback = null
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleSurfaceView(overlayContainer.context, assHandler)
    // Wire video frame callback to drive subtitle rendering.
    // The primary path (VideoFrameMetadataListener) provides vsync-pinned timing.
    // When it doesn't fire (tunneled playback), AssPositionRenderer in the
    // RenderersFactory provides position-based fallback on every render loop.
    assHandler.videoFrameCallback = { presentationTimeUs, releaseTimeNs ->
        assSubtitleView.requestRender(presentationTimeUs, releaseTimeNs)
    }
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    )
    setTag(R.id.libass_overlay_bound_player, player)
    post { syncLibassOverlayGeometry() }
}
private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleSurfaceView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleSurfaceView) {
            removeViewAt(index)
        }
    }
}

private fun PlayerView.releaseLibassOverlay() {
    findViewById<android.widget.FrameLayout>(R.id.libass_overlay_container_gl)
        ?.removeAssOverlayChildren()
    setTag(R.id.libass_overlay_bound_player, null)
}

private const val PIP_FALLBACK_SUBTITLE_SCALE = 0.4f

/** Height of a fitted video inside [viewSize], or null if either size is unknown. */
private fun displayedVideoHeightPx(viewSize: IntSize, videoSize: VideoSize): Float? {
    if (viewSize.width <= 0 || viewSize.height <= 0 || videoSize.width <= 0 || videoSize.height <= 0) return null
    val videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
    return minOf(viewSize.height.toFloat(), viewSize.width / videoAspect)
}

/**
 * Remembers the last subtitle style applied to a [PlayerView] so the style is not set again on
 * every snapshot.
 */
private class SubtitleStyleGuard {
    private var view: PlayerView? = null
    private var style: SubtitleStyleState? = null
    private var pipVideoHeightPx: Float? = null

    fun shouldApply(view: PlayerView, style: SubtitleStyleState, pipVideoHeightPx: Float?): Boolean {
        if (this.view === view && this.style == style && this.pipVideoHeightPx == pipVideoHeightPx) {
            return false
        }
        this.view = view
        this.style = style
        this.pipVideoHeightPx = pipVideoHeightPx
        return true
    }
}

/**
 * [pipVideoHeightPx] is null outside PiP. In PiP it is the video height outside PiP (0 if unknown),
 * so subtitles shrink and grow with the video exactly as it is shown full screen.
 */

private fun transformedChildBoundsInParent(child: View): android.graphics.RectF? {
    if (child.width <= 0 || child.height <= 0) return null
    return android.graphics.RectF(0f, 0f, child.width.toFloat(), child.height.toFloat()).also { bounds ->
        child.matrix.mapRect(bounds)
        bounds.offset(child.left.toFloat(), child.top.toFloat())
    }
}

private data class LibassOverlayMargins(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
)

private fun libassOverlayMargins(
    overlayWidth: Int,
    overlayHeight: Int,
    videoBounds: android.graphics.RectF
): LibassOverlayMargins? {
    if (
        overlayWidth <= 0 || overlayHeight <= 0 ||
        !videoBounds.left.isFinite() || !videoBounds.top.isFinite() ||
        !videoBounds.right.isFinite() || !videoBounds.bottom.isFinite() ||
        videoBounds.width() <= 0f || videoBounds.height() <= 0f
    ) {
        return null
    }
    return LibassOverlayMargins(
        top = videoBounds.top.roundToInt(),
        bottom = (overlayHeight - videoBounds.bottom).roundToInt(),
        left = videoBounds.left.roundToInt(),
        right = (overlayWidth - videoBounds.right).roundToInt()
    )
}

/**
 * Computes the distance from the libass overlay frame to the visible video rectangle
 * using the actual transformed bounds of exo_content_frame, then applies them to
 * AssHandler. Only runs when the overlay SurfaceView exists (OVERLAY render mode).
 */
private fun PlayerView.syncLibassOverlayGeometry() {
    val overlay = findViewById<android.widget.FrameLayout>(R.id.libass_overlay_container_gl)
    val assOverlayView = overlay?.findAssOverlayChild()
    val contentFrame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    val exoPlayer = player as? ExoPlayer
    val assHandler = exoPlayer?.getAssHandlerCompat()

    if (assOverlayView == null || assHandler == null || contentFrame == null) return

    val overlayWidth = overlay?.width ?: 0
    val overlayHeight = overlay?.height ?: 0
    val videoBounds = transformedChildBoundsInParent(contentFrame) ?: return
    val margins = libassOverlayMargins(overlayWidth, overlayHeight, videoBounds) ?: return

    assHandler.setMargins(margins.top, margins.bottom, margins.left, margins.right)
    assHandler.setUseMargins(false)
    assOverlayView.invalidateSubtitles()
}

private fun android.widget.FrameLayout.findAssOverlayChild(): AssSubtitleSurfaceView? {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is AssSubtitleSurfaceView) {
            return child
        }
    }
    return null
}

/**
 * [pipVideoHeightPx] is null outside PiP. In PiP it is the video height outside PiP (0 if unknown),
 * so subtitles shrink and grow with the video exactly as it is shown full screen.
 */
private fun PlayerView.applySubtitleStyle(style: SubtitleStyleState, pipVideoHeightPx: Float? = null) {
    subtitleView?.apply {
        val baseBottomPaddingFraction = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f
        val offsetFraction = (style.bottomOffset / 1000f).coerceIn(0f, 0.2f)
        val bottomPaddingFraction = (baseBottomPaddingFraction + offsetFraction).coerceIn(0f, 0.4f)

        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setBottomPaddingFraction(bottomPaddingFraction)
        setStyle(
            CaptionStyleCompat(
                style.textColor.toArgb(),
                style.backgroundColor.toArgb(),
                android.graphics.Color.TRANSPARENT,
                if (style.outlineEnabled) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                style.outlineColor.toArgb(),
                if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            )
        )
        val fontSizeSp = style.fontSizeSp.toFloat()
        when {
            pipVideoHeightPx == null -> setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            // The PiP window matches the video, so a fraction of its height follows window resizes.
            pipVideoHeightPx > 0f -> setFractionalTextSize(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics) /
                    pipVideoHeightPx,
            )
            // Full-screen size unknown: fall back to a fixed PiP scale.
            else -> setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * PIP_FALLBACK_SUBTITLE_SCALE)
        }
    }
}

/**
 * A downloaded file is read from disk, so it needs a much smaller buffer than a stream.
 * `content:` URIs that do not come from a download provider point at local files too.
 */
private fun MediaItem.isLocalFileSource(): Boolean {
    val uri = localConfiguration?.uri ?: return false
    return when (uri.scheme?.lowercase()) {
        "file" -> true
        "content" -> !uri.authority.orEmpty().contains("downloads", ignoreCase = true)
        else -> false
    }
}

/**
 * Cheap fingerprint of the player's audio groups and their selection. The full track list is
 * only rebuilt when this signature changes, so the runtime tick does not allocate a new list.
 */
private fun ExoPlayer.audioTrackSignature(): String {
    val signature = StringBuilder()
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        signature.append(group.length).append(':')
            .append(group.isSelected).append(':')
        val trackCount = group.mediaTrackGroup.length
        if (trackCount > 0) {
            val format = group.mediaTrackGroup.getFormat(0)
            signature.append(format.id).append(':').append(format.language)
        }
        signature.append(';')
    }
    return signature.toString()
}

/** Caches the audio track list and rebuilds it only when the track signature changes. */
private class AudioTrackCache {
    private var signature: String? = null
    private var tracks: List<AudioTrack> = emptyList()

    fun tracks(player: ExoPlayer, context: Context): List<AudioTrack> {
        val currentSignature = player.audioTrackSignature()
        if (currentSignature != signature) {
            signature = currentSignature
            tracks = player.extractAudioTracks(context)
        }
        return tracks
    }
}

private fun ExoPlayer.extractAudioTracks(context: Context): List<AudioTrack> {
    val tracks = mutableListOf<AudioTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        val label = trackNameProvider.getTrackName(format).takeIf { it.isNotBlank() }
            ?: runBlocking { getString(Res.string.compose_player_track_number, idx + 1) }
        tracks.add(
            AudioTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = label,
                language = format.language,
                isSelected = group.isSelected,
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.applySubtitleTrackPreferences(
    preferredLanguage: String,
    useForcedSubtitles: Boolean,
    autoSelectionApplied: Boolean,
    hasActiveSubtitle: Boolean,
    useCustomSubtitles: Boolean,
) {
    if ((hasActiveSubtitle || useCustomSubtitles) && autoSelectionApplied) {
        return
    }

    val builder = trackSelectionParameters.buildUpon()
    val resolvedPreferred = exoPreferredTextLanguage(preferredLanguage)

    if (resolvedPreferred == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        builder.setPreferredTextLanguage(null)
    } else {
        val userDisabledSubtitles = autoSelectionApplied && !hasActiveSubtitle
        val shouldSuppressExoAutoSelect = useForcedSubtitles && !autoSelectionApplied
        if (!userDisabledSubtitles && !shouldSuppressExoAutoSelect) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        if (!shouldSuppressExoAutoSelect) {
            builder.setPreferredTextLanguage(resolvedPreferred)
        }
    }

    val currentFlags = trackSelectionParameters.ignoredTextSelectionFlags
    val newFlags = if (!useForcedSubtitles) {
        currentFlags or C.SELECTION_FLAG_FORCED
    } else {
        currentFlags and C.SELECTION_FLAG_FORCED.inv()
    }
    builder.setIgnoredTextSelectionFlags(newFlags)
    trackSelectionParameters = builder.build()
}

private fun exoPreferredTextLanguage(preferredLanguage: String): String? {
    val normalized = normalizeLanguageCode(preferredLanguage) ?: return null
    return when (normalized) {
        SubtitleLanguageOption.NONE,
        SubtitleLanguageOption.FORCED,
        -> null
        SubtitleLanguageOption.DEVICE ->
            DeviceLanguagePreferences.preferredLanguageCodes().firstOrNull()
        else -> normalized
    }
}

private fun ExoPlayer.extractSubtitleTracks(context: Context): List<SubtitleTrack> {
    val tracks = mutableListOf<SubtitleTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        val format = group.mediaTrackGroup.getFormat(0)
        val hasForcedSelectionFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        tracks.add(
            SubtitleTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = trackNameProvider.getTrackName(format),
                language = format.language,
                isSelected = group.isSelected,
                isForced = inferForcedSubtitleTrack(
                    label = format.label,
                    language = format.language,
                    trackId = format.id,
                    hasForcedSelectionFlag = hasForcedSelectionFlag,
                ),
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.selectTrackByIndex(trackType: Int, targetIndex: Int): Boolean {
    return selectTrackByPredicate(trackType, "index=$targetIndex") { idx, _ ->
        idx == targetIndex
    }
}

private fun ExoPlayer.selectTrackByPredicate(
    trackType: Int,
    targetDescription: String,
    predicate: (index: Int, format: Format) -> Boolean,
): Boolean {
    val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "AUDIO" else "TEXT"
    Log.d(TAG, "selectTrack: type=$typeName target=$targetDescription")
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (!predicate(idx, format)) {
            idx++
            continue
        }
        Log.d(TAG, "selectTrack: found group at idx=$idx, format.id=${format.id}, lang=${format.language}, label=${format.label}")
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(0))
            )
            .build()
        Log.d(TAG, "selectTrack: override applied")
        return true
    }
    Log.w(TAG, "selectTrack: no group found for type=$typeName target=$targetDescription (total groups scanned=$idx)")
    return false
}

private fun ExoPlayer.logCurrentTracks(context: String) {
    Log.d(TAG, "--- logCurrentTracks ($context) ---")
    Log.d(TAG, "  textDisabled=${trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
    for (group in currentTracks.groups) {
        val typeName = when (group.type) {
            C.TRACK_TYPE_AUDIO -> "AUDIO"
            C.TRACK_TYPE_TEXT -> "TEXT"
            C.TRACK_TYPE_VIDEO -> "VIDEO"
            else -> "OTHER(${group.type})"
        }
        if (group.type != C.TRACK_TYPE_TEXT && group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        Log.d(TAG, "  group type=$typeName id=${format.id} lang=${format.language} label=${format.label} selected=${group.isSelected} supported=${group.isSupported}")
    }
    Log.d(TAG, "--- end logCurrentTracks ---")
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.videoBoundsFraction(aspectRatio: Float): RectF? {
    val subtitleView = this.subtitleView ?: return null
    val viewWidth = subtitleView.width.toFloat()
    val viewHeight = subtitleView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    if (aspectRatio > 0f) {
        val parentRatio = viewWidth / viewHeight
        return if (parentRatio > aspectRatio) {
            val fitW = viewHeight * aspectRatio
            val leftPx = (viewWidth - fitW) / 2f
            RectF(leftPx / viewWidth, 0f, (leftPx + fitW) / viewWidth, 1f)
        } else {
            val fitH = viewWidth / aspectRatio
            val topPx = (viewHeight - fitH) / 2f
            RectF(0f, topPx / viewHeight, 1f, (topPx + fitH) / viewHeight)
        }
    }

    val contentFrame = getTag(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        ?: findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.also { setTag(androidx.media3.ui.R.id.exo_content_frame, it) }
        ?: return null
    val frameWidth = contentFrame.width.toFloat()
    val frameHeight = contentFrame.height.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return null
    if (frameWidth > viewWidth || frameHeight > viewHeight) return null
    val left = contentFrame.x / viewWidth
    val top = contentFrame.y / viewHeight
    return RectF(
        left,
        top,
        left + frameWidth / viewWidth,
        top + frameHeight / viewHeight,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val shouldStripSdhProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            DolbyFreeMediaCodecSelector(mediaCodecSelector),
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider,
            shouldStripSdhProvider = shouldStripSdhProvider,
            videoBoundsFractionProvider = videoBoundsFractionProvider,
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
            )
        }
    }
}

/**
 * Hides the crashing platform Dolby decoders (e.g. `c2.dolby.eac3.decoder.eac3`) from the
 * MediaCodec audio renderer so the bundled ffmpeg extension renderer handles AC-3/E-AC-3
 * (including E-AC-3 JOC) from the very first prepare attempt instead of failing once and retrying.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class DolbyFreeMediaCodecSelector(
    private val delegate: MediaCodecSelector,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val decoderInfos = delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        if (mimeType != MimeTypes.AUDIO_AC3 &&
            mimeType != MimeTypes.AUDIO_E_AC3 &&
            mimeType != MimeTypes.AUDIO_E_AC3_JOC
        ) {
            return decoderInfos
        }
        return decoderInfos.filterNot { it.name.contains("dolby", ignoreCase = true) }
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val shouldStripSdhProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?,
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        val processed = cueGroup.cues.mapNotNull(::processCue)
        delegate.onCues(CueGroup(processed, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(cues.mapNotNull(::processCue))
    }

    private fun processCue(cue: Cue): Cue? {
        var processed = fixRtlCueText(cue)
        if (shouldStripSdhProvider()) {
            val text = processed.text?.toString() ?: return processed
            val filtered = SubtitleSdhFilter.filter(text) ?: return null
            if (filtered != text) {
                processed = processed.buildUpon().setText(filtered).build()
            }
        }
        if (shouldNormalizeCuePositionProvider()) {
            processed = normalizeCuePosition(processed)
        }
        if (processed.bitmap != null) {
            val bounds = videoBoundsFractionProvider()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
                val isIdentity = bounds.left == 0f && bounds.top == 0f
                    && bounds.width() == 1f && bounds.height() == 1f
                if (!isIdentity) {
                    processed = remapBitmapCueToVideoBounds(processed, bounds)
                }
            }
        }
        return processed
    }

    private fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
        val builder = cue.buildUpon()
        if (cue.position != Cue.DIMEN_UNSET) {
            builder.setPosition(bounds.left + cue.position * bounds.width())
        }
        if (cue.size != Cue.DIMEN_UNSET) {
            builder.setSize(cue.size * bounds.width())
        }
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
            builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
        }
        if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
        }
        return builder.build()
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }

    private fun fixRtlCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!containsRtlChars(text)) return cue
        val original = text.toString()
        val fixed = original.split('\n').joinToString("\n") { line ->
            moveLeadingRtlPunctuationToEnd(line)
        }
        if (fixed == original) return cue
        return cue.buildUpon().setText(SpannableString(fixed)).build()
    }

    private fun moveLeadingRtlPunctuationToEnd(line: String): String {
        if (line.isEmpty()) return line
        var end = 0
        while (end < line.length && line[end] in RTL_PUNCTUATION) end++
        if (end == 0) return line
        return line.substring(end) + line.substring(0, end)
    }

    private fun containsRtlChars(text: CharSequence): Boolean {
        for (char in text) {
            val directionality = Character.getDirectionality(char)
            if (
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

private fun diagnosticElapsedSince(startedAtMs: Long): Long =
    if (startedAtMs <= 0L) -1L else (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)

private fun diagnosticPlaybackSource(value: String): String = runCatching {
    val uri = Uri.parse(value)
    val host = uri.host.orEmpty()
    val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
    "scheme=${uri.scheme ?: "none"},host=${host.ifBlank { "none" }},port=${uri.port},loopback=$isLoopback"
}.getOrDefault("unparseable")

private fun isLoopbackPlaybackSource(value: String): Boolean = runCatching {
    when (Uri.parse(value).host.orEmpty().lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }
}.getOrDefault(false)

private fun diagnosticPlayerMessage(value: String?): String =
    value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

private fun diagnosticThrowableChain(value: Throwable): String =
    generateSequence(value) { it.cause }
        .take(6)
        .joinToString(" -> ") { error ->
            "${error.javaClass.simpleName}:${diagnosticPlayerMessage(error.message)}"
        }
        .let(::diagnosticPlayerMessage)

internal class SubtitleRequestHeaderDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SubtitleRequestHeaderDataSource(
            upstream = upstreamFactory.createDataSource(),
            externalSubtitles = externalSubtitles,
        )
}

internal class SubtitleRequestHeaderDataSource(
    private val upstream: DataSource,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val subtitle = externalSubtitles.find { it.url == url }
        val headers = subtitle?.headers
        
        return if (headers.isNullOrEmpty()) {
            upstream.open(dataSpec)
        } else {
            val mergedHeaders = dataSpec.httpRequestHeaders.toMutableMap()
            headers.forEach { (key, value) ->
                mergedHeaders[key] = value
            }
            upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(mergedHeaders).build())
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}

private fun Format.hdrFormats(): List<String> = buildList {
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) add("DV")
    when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> add("HDR10")
        C.COLOR_TRANSFER_HLG -> add("HLG")
    }
}
