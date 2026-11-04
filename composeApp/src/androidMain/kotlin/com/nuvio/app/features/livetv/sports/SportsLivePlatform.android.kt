package com.nuvio.app.features.livetv.sports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.settings.AppIconPlatform

internal actual object SportsLivePlatform {
    actual val supported: Boolean = true

    actual fun show(title: String, text: String, openUrl: String?): Boolean {
        val context = RecordingPlatform.context() ?: return false
        SportsLiveService.content = SportsLiveService.Content(title, text, openUrl)
        if (SportsLiveService.running) {
            runCatching { NotificationManagerCompat.from(context).notify(SportsLiveService.NOTIFICATION_ID, SportsLiveService.build(context)) }
            return true
        }
        return runCatching {
            val intent = Intent(context, SportsLiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            true
        }.getOrDefault(false) // Android 12+ won't start it from the background (outside an alarm)
    }

    actual fun stop() {
        val context = RecordingPlatform.context() ?: return
        SportsLiveService.content = null
        runCatching { context.stopService(Intent(context, SportsLiveService::class.java)) }
    }

    actual fun openNotificationSettings() {
        val context = RecordingPlatform.context() ?: return
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + context.packageName))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * The live score of your team's game, kept at the top of the screen while they play. Being a
 * foreground service, it also keeps the app (and its score checks) running in the background,
 * so goal alerts arrive with the app closed. Stopped at full time, or with its Stop button.
 */
class SportsLiveService : Service() {

    internal class Content(val title: String, val text: String, val openUrl: String?)

    companion object {
        internal const val NOTIFICATION_ID = 7342
        private const val CHANNEL_ID = "livetv_live_scores"
        private const val ACTION_STOP = "com.nuvio.livetv.STOP_LIVE_SCORES"

        @Volatile internal var content: Content? = null
        @Volatile internal var running = false

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Live scores", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "The score while a team you follow is playing"
                        setShowBadge(false)
                    }
                )
            }
        }

        internal fun build(context: Context): Notification {
            ensureChannel(context)
            val c = content ?: Content("Live scores", "Waiting for the game", null)
            val open = Intent().apply {
                component = AppIconPlatform.currentLauncherComponent(context)
                action = if (c.openUrl != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN
                if (c.openUrl != null) data = Uri.parse(c.openUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stopIntent = PendingIntent.getService(
                context, NOTIFICATION_ID + 1,
                Intent(context, SportsLiveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
                .setContentTitle(c.title)
                .setContentText(c.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(c.text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(openIntent)
                .addAction(0, "Stop", stopIntent)
                .build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        RecordingPlatform.initialize(applicationContext)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SportsLiveWatch.stopForNow()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = build(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed right now: the scores still come while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        // Keeps the phone awake enough to check the score (at most one game's length at a time).
        if (wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nuvio:LiveScores")
                    .apply { setReferenceCounted(false); acquire(5 * 60 * 60 * 1000L) }
            }.getOrNull()
        }
        SportsLiveWatch.start()
        return START_NOT_STICKY
    }

    /** Android 15+ ends this kind of service after six hours a day: stop when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
