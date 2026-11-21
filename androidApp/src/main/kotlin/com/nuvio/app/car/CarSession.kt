package com.nuvio.app.car

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.features.player.CarPlayerOverrides
import com.nuvio.app.features.player.NuvioPlaybackRemote
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.streams.StreamQualityCap
import java.lang.ref.WeakReference

/**
 * What's going on with the car right now, for the phone side: the live [CarDisplayHost] (so the phone
 * remote can press Back on the car), the mobile-data stream cap, and the "Nuvio is on your car" notification
 * that opens the remote. Main thread only.
 */
internal object CarSession {
    private var hostRef: WeakReference<CarDisplayHost>? = null
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    /** The car host while Nuvio is on the car screen. */
    val host: CarDisplayHost?
        get() = hostRef?.get()

    val active: Boolean
        get() = host != null

    fun start(ctx: Context, host: CarDisplayHost) {
        val app = ctx.applicationContext
        appContext = app
        hostRef = WeakReference(host)
        watchNetwork(app)
        applyOverrides(app)
        showNotification(app)
    }

    /** Pushes every in-car override from Nuvio Auto Setup into Nuvio (or clears them when not in the car). */
    private fun applyOverrides(app: Context) {
        if (!active) {
            CarPlayerOverrides.clear()
            ExternalAppCommands.setCarProfile(null, locked = false)
            return
        }
        val basic = CarPrefs.basicMode(app)
        CarPlayerOverrides.set(
            CarPlayerOverrides.Config(
                resizeMode = if (CarPrefs.FILL_VIDEO.get(app)) PlayerResizeMode.Zoom else null,
                bigBuffer = CarPrefs.BIG_BUFFER.get(app),
                autoSkipIntros = CarPrefs.AUTO_SKIP_INTROS.get(app),
                autoNextEpisode = CarPrefs.AUTO_NEXT_EPISODE.get(app),
                subtitleScale = if (basic) 1f else CarPrefs.SUBTITLE_SCALES[CarPrefs.subtitleScaleIndex(app)],
                subtitleBackground = CarPrefs.SUBTITLE_BACKGROUND.get(app),
                steering = if (basic) CarPlayerOverrides.SteeringMode.DEFAULT else CarPrefs.steering(app),
            ),
        )
        ExternalAppCommands.setCarProfile(CarPrefs.carProfile(app), CarPrefs.CAR_LOCK.get(app))
        CarLog.d("Car overrides: ${CarPlayerOverrides.config.value}, profile ${CarPrefs.carProfile(app)}")
    }

    fun stop(host: CarDisplayHost) {
        if (hostRef?.get() !== host) return
        hostRef = null
        val app = appContext ?: return
        networkCallback?.let { cb ->
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        StreamQualityCap.set(null)
        if (CarPrefs.PAUSE_ON_DISCONNECT.get(app)) {
            NuvioPlaybackRemote.pause()
            CarLog.d("Car disconnected: paused")
        }
        cacheProfiles(app)
        applyOverrides(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Re-applies settings changed in Nuvio Auto Setup while connected. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        if (!active) return
        applyCap(app, currentlyCellular(app))
        applyOverrides(app)
        if (CarPrefs.remoteNotification(app)) showNotification(app)
        else app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Remembers profile names for the Setup screen's profile picker. */
    fun cacheProfiles(ctx: Context) {
        runCatching {
            val profiles = ProfileRepository.state.value.profiles.map { it.profileIndex to it.name }
            CarPrefs.cacheProfiles(ctx, profiles)
        }
    }

    // ---- Data saver -------------------------------------------------------------------------------------------

    private fun watchNetwork(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                main.post { applyCap(app, cellular) }
            }

            override fun onLost(network: Network) {
                main.post { applyCap(app, currentlyCellular(app)) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { CarLog.d("Couldn't watch the network for the data saver", it) }
        applyCap(app, currentlyCellular(app))
    }

    private fun currentlyCellular(app: Context): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun applyCap(app: Context, cellular: Boolean) {
        val wanted = when (CarPrefs.dataSaver(app)) {
            CarPrefs.DataSaver.ALWAYS -> true
            CarPrefs.DataSaver.MOBILE_DATA -> cellular
            CarPrefs.DataSaver.OFF -> false
        }
        val cap = if (active && wanted) CarPrefs.DATA_SAVER_MAX_HEIGHT else null
        if (StreamQualityCap.maxHeight.value != cap) {
            StreamQualityCap.set(cap)
            CarLog.d(if (cap != null) "Data saver on: ${cap}p streams first" else "Data saver off")
        }
    }

    // ---- Notification -----------------------------------------------------------------------------------------

    private fun showNotification(app: Context) {
        if (!CarPrefs.remoteNotification(app)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nuvio Auto", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Nuvio is on the car screen, with a shortcut to the phone remote"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            app, 1,
            Intent(app, CarRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nuvio is on your car screen")
            .setContentText("Tap for the remote: search with the phone keyboard, play, pause, skip")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private const val CHANNEL_ID = "nuvio_auto"
    private const val NOTIFICATION_ID = 47_201
}
