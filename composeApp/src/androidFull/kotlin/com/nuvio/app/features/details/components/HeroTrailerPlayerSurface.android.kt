package com.nuvio.app.features.details.components

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import android.widget.FrameLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nuvio.app.features.player.PlatformPlaybackDataSourceFactory
import com.nuvio.app.features.trailer.LetterboxDetector
import com.nuvio.app.features.trailer.LetterboxSampler
import com.nuvio.app.features.trailer.LetterboxTracker
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    var playerContainer by remember { mutableStateOf<HeroTrailerTextureContainer?>(null) }

    val dataSourceFactory = remember(context) {
        PlatformPlaybackDataSourceFactory.create(
            context = context,
            defaultRequestHeaders = emptyMap(),
            defaultResponseHeaders = emptyMap(),
            useYoutubeChunkedPlayback = true,
        )
    }
    val exoPlayer = remember(sourceUrl, sourceAudioUrl, dataSourceFactory) {
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                if (!sourceAudioUrl.isNullOrBlank()) {
                    setMediaSource(
                        MergingMediaSource(
                            mediaSourceFactory.createMediaSource(MediaItem.fromUri(sourceUrl)),
                            mediaSourceFactory.createMediaSource(MediaItem.fromUri(sourceAudioUrl)),
                        ),
                    )
                } else {
                    setMediaItem(MediaItem.fromUri(sourceUrl))
                }
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (muted) 0f else 1f
                prepare()
            }
    }
    var hasRenderedFirstFrame by remember(exoPlayer) { mutableStateOf(false) }
    var letterboxZoom by remember(exoPlayer) { mutableFloatStateOf(1f) }
    val letterboxZoomState = animateFloatAsState(
        targetValue = letterboxZoom,
        animationSpec = tween(durationMillis = 400),
        label = "heroTrailerLetterboxZoom",
    )

    DisposableEffect(exoPlayer, lifecycleOwner) {
        fun detachVideoSurface() {
            playerContainer?.detachPlayer(exoPlayer)
            playerContainer?.alpha = 0f
        }

        val listener = object : Player.Listener {
            private var readyReported = false
            private var endedReported = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (!readyReported) {
                            readyReported = true
                            latestOnReady.value()
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (!endedReported) {
                            endedReported = true
                            latestOnEnded.value()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerContainer?.setVideoSize(videoSize)
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                detachVideoSurface()
                latestOnError.value()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (latestPlayWhenReady.value && exoPlayer.playbackState != Player.STATE_ENDED) {
                        playerContainer?.attachPlayer(exoPlayer)
                        playerContainer?.alpha = 1f
                        exoPlayer.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                    detachVideoSurface()
                }
                else -> Unit
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            detachVideoSurface()
            exoPlayer.stop()
            exoPlayer.release()
            playerContainer = null
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            return@LaunchedEffect
        }
        exoPlayer.playWhenReady = playWhenReady
        if (playWhenReady) {
            playerContainer?.attachPlayer(exoPlayer)
            playerContainer?.alpha = 1f
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(exoPlayer, muted) {
        exoPlayer.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(exoPlayer, hasRenderedFirstFrame) {
        letterboxZoom = 1f
        if (!hasRenderedFirstFrame) return@LaunchedEffect
        val tracker = LetterboxTracker()
        val sampler = LetterboxSampler()
        try {
            while (true) {
                delay(LetterboxDetector.SAMPLE_INTERVAL_MS)
                if (LetterboxDetector.isSampleWindowOver(exoPlayer.currentPosition, exoPlayer.duration)) break
                if (!exoPlayer.isPlaying) continue
                val textureView = playerContainer?.textureView ?: continue
                val bar = sampler.sample(textureView) ?: continue
                letterboxZoom = tracker.onSample(bar) ?: continue
                break
            }
        } finally {
            sampler.release()
        }
    }

    AndroidView(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer {
                scaleX = letterboxZoomState.value
                scaleY = letterboxZoomState.value
            },
        factory = { viewContext ->
            HeroTrailerTextureContainer(viewContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                attachPlayer(exoPlayer)
                playerContainer = this
            }
        },
        update = { container ->
            playerContainer = container
            if (playWhenReady) {
                container.attachPlayer(exoPlayer)
            }
        },
    )
}

private class HeroTrailerTextureContainer(
    context: Context,
) : FrameLayout(context) {
    val textureView = TextureView(context)
    private val textureTransform = Matrix()
    private var videoAspectRatio = 16f / 9f
    private var attachedPlayer: ExoPlayer? = null

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = false
        addView(
            textureView,
            LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        textureView.isFocusable = false
        textureView.isClickable = false
    }

    fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.clearVideoTextureView(textureView)
        attachedPlayer = player
        player.setVideoTextureView(textureView)
    }

    fun detachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) {
            player.clearVideoTextureView(textureView)
            attachedPlayer = null
        }
    }

    fun setVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        updateTextureTransform()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTextureTransform()
    }

    private fun updateTextureTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoAspectRatio <= 0f) return

        val viewAspectRatio = viewWidth / viewHeight
        textureTransform.reset()
        if (viewAspectRatio > videoAspectRatio) {
            val scaleY = viewAspectRatio / videoAspectRatio
            textureTransform.setScale(1f, scaleY, viewWidth / 2f, viewHeight / 2f)
        } else {
            val scaleX = videoAspectRatio / viewAspectRatio
            textureTransform.setScale(scaleX, 1f, viewWidth / 2f, viewHeight / 2f)
        }
        textureView.setTransform(textureTransform)
    }
}
