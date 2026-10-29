package com.nuvio.app.features.player

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.IntentCompat
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.concurrent.ConflatedTaskDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal const val NOW_PLAYING_TAG = "NuvioNowPlaying"
internal const val NOW_PLAYING_CHANNEL_ID = "nuvio_playback"
internal const val NOW_PLAYING_NOTIFICATION_ID = 0x4E55
private const val ACTION_START_FOREGROUND = "com.nuvio.app.nowplaying.START_FOREGROUND"
private const val EXTRA_START_NOTIFICATION = "com.nuvio.app.nowplaying.START_NOTIFICATION"

class PlayerNowPlayingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_FOREGROUND) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val startNotification = IntentCompat.getParcelableExtra(intent, EXTRA_START_NOTIFICATION, Notification::class.java)
            ?: PlayerNowPlayingServiceState.notification
        if (startNotification == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val started = runCatching {
            startForeground(NOW_PLAYING_NOTIFICATION_ID, startNotification)
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to promote playback service", error)
        }.isSuccess
        if (!started) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val currentNotification = PlayerNowPlayingServiceState.notification
        if (currentNotification == null) {
            stopSelf(startId)
        } else if (currentNotification !== startNotification) {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOW_PLAYING_NOTIFICATION_ID, currentNotification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        PlayerNowPlayingServiceController.onServiceDestroyed(applicationContext)
        super.onDestroy()
    }

    companion object {
        internal fun publish(context: Context, notification: Notification) {
            if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
            PlayerNowPlayingServiceController.publish(context.applicationContext, notification)
        }

        internal fun hide(context: Context) {
            if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
            PlayerNowPlayingServiceController.hide(context.applicationContext)
        }
    }
}

private sealed interface PlayerNowPlayingServiceCommand {
    data class Publish(
        val context: Context,
        val notification: Notification,
    ) : PlayerNowPlayingServiceCommand

    data class Hide(val context: Context) : PlayerNowPlayingServiceCommand
}

private object PlayerNowPlayingServiceController {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioNowPlayingService").apply { isDaemon = true }
    }
    private val startRequested = AtomicBoolean(false)
    private val commands = ConflatedTaskDispatcher<PlayerNowPlayingServiceCommand>(
        schedule = { task -> executor.execute(task) },
        consume = ::execute,
    )

    fun publish(context: Context, notification: Notification) {
        PlayerNowPlayingServiceState.notification = notification
        commands.dispatch(PlayerNowPlayingServiceCommand.Publish(context, notification))
    }

    fun hide(context: Context) {
        PlayerNowPlayingServiceState.notification = null
        commands.dispatch(PlayerNowPlayingServiceCommand.Hide(context))
    }

    fun onServiceDestroyed(context: Context) {
        startRequested.set(false)
        val notification = PlayerNowPlayingServiceState.notification ?: return
        commands.dispatch(PlayerNowPlayingServiceCommand.Publish(context, notification))
    }

    private fun execute(command: PlayerNowPlayingServiceCommand) {
        when (command) {
            is PlayerNowPlayingServiceCommand.Publish -> publishNow(command)
            is PlayerNowPlayingServiceCommand.Hide -> hideNow(command)
        }
    }

    private fun publishNow(command: PlayerNowPlayingServiceCommand.Publish) {
        if (PlayerNowPlayingServiceState.notification !== command.notification) return
        if (startRequested.compareAndSet(false, true)) {
            val intent = Intent(command.context, PlayerNowPlayingService::class.java)
                .setAction(ACTION_START_FOREGROUND)
                .putExtra(EXTRA_START_NOTIFICATION, command.notification)
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    command.context.startForegroundService(intent)
                } else {
                    command.context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(NOW_PLAYING_TAG, "Unable to start playback service", error)
            }.isSuccess
            if (!started) {
                startRequested.set(false)
                return
            }
        }
        if (PlayerNowPlayingServiceState.notification !== command.notification) return
        runCatching {
            command.context.getSystemService(NotificationManager::class.java)
                ?.notify(NOW_PLAYING_NOTIFICATION_ID, command.notification)
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to publish playback notification", error)
        }
    }

    private fun hideNow(command: PlayerNowPlayingServiceCommand.Hide) {
        if (PlayerNowPlayingServiceState.notification != null) return
        startRequested.set(false)
        runCatching {
            command.context.stopService(Intent(command.context, PlayerNowPlayingService::class.java))
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to stop playback service", error)
        }
        runCatching {
            command.context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOW_PLAYING_NOTIFICATION_ID)
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to hide playback notification", error)
        }
    }
}

private object PlayerNowPlayingServiceState {
    @Volatile
    var notification: Notification? = null
}
