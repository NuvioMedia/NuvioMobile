package com.nuvio.app.features.livetv.record

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nuvio.app.features.settings.AppIconPlatform

/**
 * Keeps recordings going while the app is in the background: a foreground service (Android shows
 * a small "Recording" notification for it), with the phone kept awake and Wi-Fi kept on. Started
 * when a recording starts, stopped when none are left.
 */
class LiveTvRecordingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 7341

        fun start(context: Context) {
            val intent = Intent(context, LiveTvRecordingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveTvRecordingService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        RecordingPlatform.initialize(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(this@LiveTvRecordingService)
            action = Intent.ACTION_MAIN
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val notification: Notification = NotificationCompat.Builder(this, RecordingPlatform.CHANNEL_ID)
            .setContentTitle("Recording Live TV")
            .setContentText(LiveTvRecorder.recordings.value.filter { it.isRecording }.joinToString(", ") { it.title }.ifBlank { "Recording" })
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, launchIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed right now (Android 12+ from the background): recording still runs while the app is open.
        }
        if (wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nuvio:LiveTvRecording")
                    .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nuvio:LiveTvRecording")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
