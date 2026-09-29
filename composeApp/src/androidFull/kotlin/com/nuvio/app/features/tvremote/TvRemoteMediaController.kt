package com.nuvio.app.features.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.IntentCompat
import com.nuvio.app.MainActivity
import com.nuvio.app.R
import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.buildNowPlayingBaseMetadata
import com.nuvio.app.features.player.downloadNowPlayingArtwork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

internal const val TV_NOTIFICATION_ID = 0x4E56
internal const val TV_CHANNEL = "nuvio_tv_remote"

internal fun RemoteSnapshot.subtitle(): String = listOfNotNull(
    if (season != null && episode != null) "S$season · E$episode" else null,
    episodeTitle?.takeIf { it.isNotBlank() },
).joinToString(" · ")

internal class TvRemoteMediaController(
    private val context: Context,
    private val repository: TvRemoteRepository,
    private val scope: CoroutineScope,
    private val loadArtwork: suspend (String) -> Bitmap? = { url ->
        withContext(Dispatchers.IO) { downloadNowPlayingArtwork(url) }
    },
) {
    val localPlayback = MutableStateFlow(false)
    private var session: MediaSession? = null
    private var volumeSessionId: String? = null
    private var artwork: Bitmap? = null
    private var artworkUrl: String? = null
    private var artworkJob: Job? = null
    private var allowUntil = 0L

    init {
        ensureTvNotificationChannel(context)
        scope.launch { combine(repository.state, localPlayback) { state, local -> state to local }.collect { (state, local) ->
            refresh(state, local)
        } }
    }
    fun allowPromotion() {
        allowUntil = SystemClock.elapsedRealtime() + 5_000
        refresh(repository.state.value, localPlayback.value)
    }
    private fun refresh(state: TvRemoteState, local: Boolean) {
        if (repository.selected == null || state.disconnected) {
            clear()
            TvRemoteService.hide(context)
        } else if (local || state.snapshot == null) {
            clear()
            TvRemoteService.publish(context, tvConnectionNotification(context, state), canPromote())
        } else publish(state)
    }
    private fun canPromote() = AndroidTvRemote.visible || SystemClock.elapsedRealtime() < allowUntil
    private fun clear() {
        artworkJob?.cancel(); artworkJob = null; artworkUrl = null; artwork = null
        session?.apply { isActive = false; setPlaybackState(null); setMetadata(null); release() }
        session = null
        volumeSessionId = null
        if (Build.VERSION.SDK_INT >= 30) TvRemoteRoutes.update(context, null)
    }
    private fun publish(current: TvRemoteState) {
        val snapshot = current.snapshot ?: return
        val active = session ?: MediaSession(context, "NuvioTvRemote").also {
            session = it
            it.setSessionActivity(contentIntent(context))
        }
        val expectedSession = requireNotNull(snapshot.sessionId)
        if (volumeSessionId != expectedSession) {
            volumeSessionId = expectedSession
            active.setPlaybackToRemote(if (Build.VERSION.SDK_INT >= 30)
                object : VolumeProvider(VOLUME_CONTROL_FIXED, 0, 0, tvRouteSessionId(expectedSession)) {}
            else object : VolumeProvider(VOLUME_CONTROL_FIXED, 0, 0) {})
        }
        if (Build.VERSION.SDK_INT >= 30) TvRemoteRoutes.update(context, snapshot)
        active.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command("play")
            override fun onPause() = command("pause")
            override fun onStop() = command("pause")
            override fun onSeekTo(pos: Long) = command("seek", pos)
            override fun onFastForward() { allowPromotion(); repository.seekBy(10_000, expectedSession) }
            override fun onRewind() { allowPromotion(); repository.seekBy(-10_000, expectedSession) }
            private fun command(action: String, position: Long? = null) {
                allowPromotion(); repository.send(action, position, expectedSession)
            }
        }, Handler(Looper.getMainLooper()))
        var actions = 0L
        if (current.canControl) {
            actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            if (snapshot.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
        }
        active.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(
            when { !current.connected -> PlaybackState.STATE_CONNECTING; snapshot.state == "playing" -> PlaybackState.STATE_PLAYING; snapshot.state == "buffering" -> PlaybackState.STATE_BUFFERING; else -> PlaybackState.STATE_PAUSED },
            current.position(SystemClock.elapsedRealtime()), if (current.connected && snapshot.state == "playing") snapshot.speed else 0f,
            SystemClock.elapsedRealtime(),
        ).build())
        val nextArtwork = snapshot.artwork?.takeIf { it.startsWith("https://") }
        if (nextArtwork != artworkUrl) {
            artworkUrl = nextArtwork; artwork = null; artworkJob?.cancel()
            if (nextArtwork != null) artworkJob = scope.launch {
                val bitmap = try { loadArtwork(nextArtwork) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (artworkUrl == nextArtwork && session != null) { artwork = bitmap; publish(repository.state.value) }
            }
        }
        val subtitle = listOf(snapshot.subtitle().takeIf { it.isNotBlank() }, "Playing on ${snapshot.deviceName}").filterNotNull().joinToString(" · ")
        val metadata = buildNowPlayingBaseMetadata(PlayerNowPlayingInfo(snapshot.title, subtitle, nextArtwork), snapshot.durationMs)
        val artMetadata = artwork?.let { android.media.MediaMetadata.Builder(metadata)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, it)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON, it).build() } ?: metadata
        if (runCatching { active.setMetadata(artMetadata) }.isFailure) active.setMetadata(metadata)
        // Publish complete state before exposing the session to SystemUI/controllers.
        active.isActive = true
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, TV_CHANNEL) else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        builder.setSmallIcon(R.drawable.ic_notification_small).setContentTitle(snapshot.title)
            .setSubText(context.getString(R.string.app_name))
            .setContentText(if (current.connected) subtitle else "Reconnecting to ${snapshot.deviceName}…")
            .setLargeIcon(artwork).setContentIntent(contentIntent(context)).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false).setOngoing(current.connected && snapshot.state == "playing")
        val indices = mutableListOf<Int>()
        fun addAction(icon: Int, label: String, action: String) {
            indices.add(indices.size)
            builder.addAction(Notification.Action.Builder(icon, label, actionIntent(context, requireNotNull(expectedSession), action)).build())
        }
        if (current.canControl) {
            if (snapshot.canSeek) addAction(android.R.drawable.ic_media_rew, "Rewind 10 seconds", "rewind")
            if (snapshot.state == "playing" || snapshot.state == "buffering") addAction(android.R.drawable.ic_media_pause, "Pause", "pause")
            else addAction(android.R.drawable.ic_media_play, "Play", "play")
            if (snapshot.canSeek) addAction(android.R.drawable.ic_media_ff, "Forward 10 seconds", "forward")
        }
        builder.setStyle(Notification.MediaStyle().setMediaSession(active.sessionToken).setShowActionsInCompactView(*indices.toIntArray()))
        TvRemoteService.publish(context, builder.build(), canPromote())
    }
}

internal fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, TV_NOTIFICATION_ID,
    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("nuvio.tv.remote.OPEN", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

private fun actionIntent(context: Context, session: String, action: String): PendingIntent = PendingIntent.getBroadcast(context, TV_NOTIFICATION_ID,
    Intent(context, TvRemoteActionReceiver::class.java).setAction(action)
        .setData(android.net.Uri.parse("nuvio-tv-action://$action/$session")).putExtra("session", session),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

class TvRemoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AndroidTvRemote.initialized || TvRemoteService.notification == null) return
        if (intent.action == "disconnect") {
            AndroidTvRemote.repository.disconnect()
            return
        }
        AndroidTvRemote.userInteraction()
        val repository = AndroidTvRemote.repository
        val session = intent.getStringExtra("session") ?: return
        when (intent.action) {
            "play", "pause" -> repository.send(requireNotNull(intent.action), expectedSession = session)
            "rewind" -> repository.seekBy(-10_000, session)
            "forward" -> repository.seekBy(10_000, session)
        }
    }
}

internal fun ensureTvNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(TV_CHANNEL, "Nuvio TV connection", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
    )
}

/** The connection stays foreground while idle, so a later TV start can publish media controls. */
internal fun tvConnectionNotification(context: Context, state: TvRemoteState): Notification {
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, TV_CHANNEL) else {
        @Suppress("DEPRECATION") Notification.Builder(context)
    }
    val disconnect = PendingIntent.getBroadcast(context, TV_NOTIFICATION_ID,
        Intent(context, TvRemoteActionReceiver::class.java).setAction("disconnect"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return builder.setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(if (state.connected) "Waiting for playback on ${state.deviceName}"
            else "Connecting to ${state.deviceName ?: "your TV"}…")
        .setContentIntent(contentIntent(context)).setOnlyAlertOnce(true).setShowWhen(false)
        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
        .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnect).build())
        .build()
}

/** Started while visible/after an explicit interaction; Android may restore this sticky service. */
class TvRemoteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureTvNotificationChannel(this)
        val initial = intent?.let { IntentCompat.getParcelableExtra(it, "notification", Notification::class.java) }
            ?: tvConnectionNotification(this, TvRemoteState())
        // Even a delayed start must satisfy the platform promotion deadline before stopping.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(TV_NOTIFICATION_ID, initial, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(TV_NOTIFICATION_ID, initial)
        }.isSuccess
        if (!promoted) {
            requested = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            // A system restart has no Activity and no in-memory notification. Restore the
            // encrypted pairing and start observation here, after meeting the FGS deadline.
            requested = true
            AndroidTvRemote.initialize(applicationContext)
            AndroidTvRemote.restoreObservation()
        }
        if (!requested || notification == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).notify(TV_NOTIFICATION_ID, notification)
        return START_STICKY
    }
    override fun onDestroy() {
        requested = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        internal var notification: Notification? = null
            private set
        private var requested = false
        internal fun publish(context: Context, value: Notification, promote: Boolean) {
            notification = value
            runCatching { context.getSystemService(NotificationManager::class.java).notify(TV_NOTIFICATION_ID, value) }
            if (promote && !requested) {
                requested = true
                val intent = Intent(context, TvRemoteService::class.java).putExtra("notification", value)
                if (runCatching {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                }.isFailure) requested = false // FGS restrictions: the ordinary media card remains.
            }
        }
        private fun demote(context: Context) {
            if (requested) { requested = false; context.stopService(Intent(context, TvRemoteService::class.java)) }
        }
        internal fun hide(context: Context) {
            notification = null
            demote(context)
            context.getSystemService(NotificationManager::class.java).cancel(TV_NOTIFICATION_ID)
        }
    }
}
