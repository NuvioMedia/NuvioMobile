package com.nuvio.app.features.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.R
import com.nuvio.app.core.build.AppFeaturePolicy
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

private const val SEEK_INTERVAL_MS = 10_000L
private const val MAX_ARTWORK_DOWNLOAD_BYTES = 12 * 1024 * 1024
private const val MAX_ARTWORK_EDGE_PX = 1_024

private const val ACTION_PLAY = "com.nuvio.app.nowplaying.PLAY"
private const val ACTION_PAUSE = "com.nuvio.app.nowplaying.PAUSE"
private const val ACTION_REWIND = "com.nuvio.app.nowplaying.REWIND"
private const val ACTION_FAST_FORWARD = "com.nuvio.app.nowplaying.FAST_FORWARD"

private data class AndroidNowPlayingMetadata(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

internal class AndroidPlayerNowPlayingController(
    context: Context,
    private val controls: PlaybackControls,
) {
    internal data class PlaybackControls(
        val play: () -> Unit,
        val pause: () -> Unit,
        val seekTo: (Long) -> Unit,
        val seekBy: (Long) -> Unit,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioNowPlayingArtwork").apply { isDaemon = true }
    }
    private val artworkGeneration = AtomicInteger(0)
    private val mediaSession = MediaSession(appContext, NOW_PLAYING_TAG).apply {
        setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = controls.play()

                override fun onPause() = controls.pause()

                override fun onStop() = controls.pause()

                override fun onSeekTo(pos: Long) = controls.seekTo(pos.coerceAtLeast(0L))

                override fun onFastForward() = controls.seekBy(SEEK_INTERVAL_MS)

                override fun onRewind() = controls.seekBy(-SEEK_INTERVAL_MS)
            },
            mainHandler,
        )
        buildContentIntent(appContext)?.let(::setSessionActivity)
    }

    private var metadata: AndroidNowPlayingMetadata? = null
    private var snapshot = PlayerPlaybackSnapshot()
    private var artworkArt: Bitmap? = null
    private var artworkAlbumArt: Bitmap? = null
    private var artworkDisplayIcon: Bitmap? = null
    private var artworkNotificationIcon: Bitmap? = null
    private var released = false
    private var lastPublishedPositionMs = Long.MIN_VALUE
    private var lastPublishedDurationMs = Long.MIN_VALUE
    private var lastPublishedPlaying: Boolean? = null
    private var lastPublishedLoading: Boolean? = null
    private var lastPublishedEnded: Boolean? = null
    private var lastPublishedSpeed = Float.NaN

    val isActive: Boolean
        get() = !released && metadata != null

    init {
        if (AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) {
            createNotificationChannel(appContext)
        }
        AndroidNowPlayingActionDispatcher.register(this)
    }

    fun updateMetadata(info: PlayerNowPlayingInfo) {
        runOnMain {
            if (released) return@runOnMain

            val normalized = AndroidNowPlayingMetadata(
                title = info.title.trim(),
                subtitle = info.subtitle?.trim()?.takeIf(String::isNotEmpty),
                artworkUrl = info.artworkUrl?.trim()?.takeIf(String::isNotEmpty),
            )
            if (normalized.title.isEmpty()) {
                clearInternal()
                return@runOnMain
            }

            val artworkChanged = metadata?.artworkUrl != normalized.artworkUrl
            metadata = normalized
            com.nuvio.app.features.tvremote.AndroidTvRemote.localPlayback(this, true)
            mediaSession.isActive = true

            if (artworkChanged) {
                artworkArt = null
                artworkAlbumArt = null
                artworkDisplayIcon = null
                artworkNotificationIcon = null
                loadArtwork(normalized.artworkUrl)
            }

            publishMetadata()
            publishPlaybackState(force = true)
            publishNotification()
        }
    }

    fun syncPlayback(nextSnapshot: PlayerPlaybackSnapshot) {
        runOnMain {
            if (released || metadata == null) return@runOnMain

            val durationChanged = snapshot.durationMs != nextSnapshot.durationMs
            val playingChanged = snapshot.isPlaying != nextSnapshot.isPlaying
            val loadingChanged = snapshot.isLoading != nextSnapshot.isLoading
            val endedChanged = snapshot.isEnded != nextSnapshot.isEnded
            snapshot = nextSnapshot

            if (durationChanged) publishMetadata()
            publishPlaybackState(force = false)
            if (playingChanged || loadingChanged || endedChanged) publishNotification()
        }
    }

    fun clear() {
        runOnMain {
            if (released) return@runOnMain
            clearInternal()
        }
    }

    fun release() {
        runOnMain {
            if (released) return@runOnMain
            released = true
            clearInternal()
            AndroidNowPlayingActionDispatcher.unregister(this)
            mediaSession.release()
            artworkExecutor.shutdownNow()
        }
    }

    internal fun handleAction(action: String?) {
        if (released) return
        when (action) {
            ACTION_PLAY -> controls.play()
            ACTION_PAUSE -> controls.pause()
            ACTION_REWIND -> controls.seekBy(-SEEK_INTERVAL_MS)
            ACTION_FAST_FORWARD -> controls.seekBy(SEEK_INTERVAL_MS)
        }
    }

    private fun clearInternal() {
        com.nuvio.app.features.tvremote.AndroidTvRemote.localPlayback(this, false)
        artworkGeneration.incrementAndGet()
        metadata = null
        snapshot = PlayerPlaybackSnapshot()
        artworkArt = null
        artworkAlbumArt = null
        artworkDisplayIcon = null
        artworkNotificationIcon = null
        resetPublishedPlaybackState()
        mediaSession.setMetadata(null)
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build(),
        )
        mediaSession.isActive = false
        PlayerNowPlayingService.hide(appContext)
    }

    private fun publishMetadata() {
        val currentMetadata = metadata ?: return
        val base = buildNowPlayingBaseMetadata(
            PlayerNowPlayingInfo(currentMetadata.title, currentMetadata.subtitle, currentMetadata.artworkUrl),
            snapshot.durationMs,
        )
        val withArtwork = artworkArt?.takeIf { !it.isRecycled }?.let { art ->
            MediaMetadata.Builder(base)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .apply {
                    artworkAlbumArt?.takeIf { !it.isRecycled }
                        ?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                    artworkDisplayIcon?.takeIf { !it.isRecycled }
                        ?.let { putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it) }
                }
                .build()
        }

        if (withArtwork == null || runCatching { mediaSession.setMetadata(withArtwork) }.isFailure) {
            runCatching { mediaSession.setMetadata(base) }
        }
    }

    private fun publishPlaybackState(force: Boolean) {
        val current = snapshot
        val positionChanged = lastPublishedPositionMs == Long.MIN_VALUE ||
            abs(current.positionMs - lastPublishedPositionMs) >= 1_000L
        val durationChanged = lastPublishedDurationMs != current.durationMs
        val playingChanged = lastPublishedPlaying != current.isPlaying
        val loadingChanged = lastPublishedLoading != current.isLoading
        val endedChanged = lastPublishedEnded != current.isEnded
        val speedChanged = lastPublishedSpeed.isNaN() || lastPublishedSpeed != current.playbackSpeed

        if (!force && !positionChanged && !durationChanged && !playingChanged && !loadingChanged && !endedChanged && !speedChanged) {
            return
        }

        val state = when {
            current.isEnded -> PlaybackState.STATE_STOPPED
            current.isLoading -> PlaybackState.STATE_BUFFERING
            current.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val playbackSpeed = if (current.isPlaying) current.playbackSpeed.coerceAtLeast(0.1f) else 0f
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    current.positionMs.coerceAtLeast(0L),
                    playbackSpeed,
                    SystemClock.elapsedRealtime(),
                )
                .setBufferedPosition(current.bufferedPositionMs.coerceAtLeast(0L))
                .build(),
        )

        lastPublishedPositionMs = current.positionMs
        lastPublishedDurationMs = current.durationMs
        lastPublishedPlaying = current.isPlaying
        lastPublishedLoading = current.isLoading
        lastPublishedEnded = current.isEnded
        lastPublishedSpeed = current.playbackSpeed
    }

    private fun publishNotification() {
        if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
        val currentMetadata = metadata ?: return
        val notification = buildNotification(
            context = appContext,
            sessionToken = mediaSession.sessionToken,
            metadata = currentMetadata,
            snapshot = snapshot,
            artwork = artworkNotificationIcon?.takeIf { !it.isRecycled },
        )
        PlayerNowPlayingService.publish(appContext, notification)
    }

    private fun loadArtwork(urlString: String?) {
        val generation = artworkGeneration.incrementAndGet()
        if (urlString.isNullOrBlank()) return

        artworkExecutor.execute {
            val bitmap = runCatching { downloadNowPlayingArtwork(urlString) }
                .onFailure { error -> Log.w(NOW_PLAYING_TAG, "Failed to load artwork", error) }
                .getOrNull()

            mainHandler.post {
                if (released || generation != artworkGeneration.get() || metadata?.artworkUrl != urlString) {
                    return@post
                }
                artworkArt = bitmap
                artworkAlbumArt = bitmap?.let(::copyArtwork)
                artworkDisplayIcon = bitmap?.let(::copyArtwork)
                artworkNotificationIcon = bitmap?.let(::copyArtwork)
                publishMetadata()
                publishNotification()
            }
        }
    }

    private fun resetPublishedPlaybackState() {
        lastPublishedPositionMs = Long.MIN_VALUE
        lastPublishedDurationMs = Long.MIN_VALUE
        lastPublishedPlaying = null
        lastPublishedLoading = null
        lastPublishedEnded = null
        lastPublishedSpeed = Float.NaN
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

class PlayerNowPlayingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidNowPlayingActionDispatcher.dispatch(intent.action)
    }
}

private object AndroidNowPlayingActionDispatcher {
    @Volatile
    private var controllerRef: WeakReference<AndroidPlayerNowPlayingController>? = null

    fun register(controller: AndroidPlayerNowPlayingController) {
        controllerRef = WeakReference(controller)
    }

    fun unregister(controller: AndroidPlayerNowPlayingController) {
        if (controllerRef?.get() === controller) controllerRef = null
    }

    fun dispatch(action: String?) {
        controllerRef?.get()?.handleAction(action)
    }
}

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        NOW_PLAYING_CHANNEL_ID,
        "Playback",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "Media playback controls"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
}

private fun buildNotification(
    context: Context,
    sessionToken: MediaSession.Token,
    metadata: AndroidNowPlayingMetadata,
    snapshot: PlayerPlaybackSnapshot,
    artwork: Bitmap?,
): Notification {
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, NOW_PLAYING_CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }

    val playPauseAction = if (snapshot.isPlaying) {
        Notification.Action.Builder(
            android.R.drawable.ic_media_pause,
            "Pause",
            buildActionIntent(context, ACTION_PAUSE, 2),
        ).build()
    } else {
        Notification.Action.Builder(
            android.R.drawable.ic_media_play,
            "Play",
            buildActionIntent(context, ACTION_PLAY, 2),
        ).build()
    }

    return builder
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(metadata.title)
        .setContentText(metadata.subtitle)
        .setLargeIcon(artwork)
        .setContentIntent(buildContentIntent(context))
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(snapshot.isPlaying)
        .setShowWhen(false)
        .addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_rew,
                "Rewind 10 seconds",
                buildActionIntent(context, ACTION_REWIND, 1),
            ).build(),
        )
        .addAction(playPauseAction)
        .addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_ff,
                "Forward 10 seconds",
                buildActionIntent(context, ACTION_FAST_FORWARD, 3),
            ).build(),
        )
        .setStyle(
            Notification.MediaStyle()
                .setMediaSession(sessionToken)
                .setShowActionsInCompactView(0, 1, 2),
        )
        .build()
}

private fun buildActionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, PlayerNowPlayingActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun buildContentIntent(context: Context): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ?: return null
    return PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal fun downloadNowPlayingArtwork(urlString: String): Bitmap? {
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("Accept", "image/*")
    }

    return try {
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ARTWORK_DOWNLOAD_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        decodeSampledBitmap(bytes, MAX_ARTWORK_EDGE_PX)
    } finally {
        connection.disconnect()
    }
}

private fun copyArtwork(bitmap: Bitmap): Bitmap? =
    if (bitmap.isRecycled) null else runCatching { bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()

private fun decodeSampledBitmap(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxEdgePx || bounds.outHeight / sampleSize > maxEdgePx) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
