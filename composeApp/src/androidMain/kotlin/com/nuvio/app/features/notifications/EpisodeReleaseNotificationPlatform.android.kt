package com.nuvio.app.features.notifications

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.settings.AppIconPlatform
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal actual object EpisodeReleaseNotificationPlatform {
    private const val permissionRequestCode = 4607
    private const val platformPreferencesName = "nuvio_episode_release_notifications_platform"
    private const val scheduledIdsKey = "scheduled_episode_release_ids"
    private const val workTag = "episode_release_notifications"
    internal const val channelId = "episode_release_notifications"
    internal const val workerRequestIdKey = "request_id"
    internal const val workerTitleKey = "title"
    internal const val workerBodyKey = "body"
    internal const val workerDeepLinkKey = "deep_link"
    internal const val workerBackdropUrlKey = "backdrop_url"

    private var appContext: Context? = null
    private var currentActivity: ComponentActivity? = null
    private var pendingPermissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null
    private val httpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun bindActivity(activity: ComponentActivity) {
        currentActivity = activity
    }

    fun unbindActivity(activity: ComponentActivity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    fun handlePermissionRequestResult(
        requestCode: Int,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != permissionRequestCode) return false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        pendingPermissionContinuation?.resume(granted)
        pendingPermissionContinuation = null
        return true
    }

    actual suspend fun notificationsAuthorized(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    actual suspend fun requestAuthorization(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ensureNotificationChannel()
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        val permissionState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            ensureNotificationChannel()
            return true
        }

        val activity = currentActivity ?: return false
        return suspendCancellableCoroutine { continuation ->
            pendingPermissionContinuation = continuation
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                permissionRequestCode,
            )
        }
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        val context = appContext ?: return
        ensureNotificationChannel()

        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            cancelTrackedWork(workManager)

            val nowEpochMs = System.currentTimeMillis()
            val scheduledIds = mutableListOf<String>()

            requests.forEach { request ->
                val triggerAtEpochMs = triggerAtEpochMs(request.releaseDateIso) ?: return@forEach
                val initialDelayMs = triggerAtEpochMs - nowEpochMs
                if (initialDelayMs <= 0L) return@forEach

                val inputData = Data.Builder()
                    .putString(workerRequestIdKey, request.requestId)
                    .putString(workerTitleKey, request.notificationTitle)
                    .putString(workerBodyKey, request.notificationBody)
                    .putString(workerDeepLinkKey, request.deepLinkUrl)
                    .putString(workerBackdropUrlKey, request.backdropUrl)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<EpisodeReleaseNotificationWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .addTag(workTag)
                    .build()

                awaitOperation(
                    workManager.enqueueUniqueWork(
                        uniqueWorkName(request.requestId),
                        ExistingWorkPolicy.REPLACE,
                        workRequest,
                    ),
                )

                scheduledIds += request.requestId
            }

            preferences(context)
                .edit()
                .putStringSet(scopedScheduledIdsKey(), scheduledIds.toSet())
                .apply()
        }
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        val context = appContext ?: return
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            cancelTrackedWork(workManager)
            preferences(context)
                .edit()
                .remove(scopedScheduledIdsKey())
                .apply()
        }
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        val context = appContext ?: return
        ensureNotificationChannel()

        val notification = buildNotification(context, request)

        NotificationManagerCompat.from(context)
            .notify(kotlin.math.abs(request.requestId.hashCode()), notification)
    }

    internal suspend fun buildNotification(
        context: Context,
        request: EpisodeReleaseNotificationRequest,
    ): android.app.Notification {
        val pendingIntent = buildPendingIntent(context, request)
        val backdropBitmap = loadBackdropBitmap(request.backdropUrl)
        val appIconBitmap = ContextCompat.getDrawable(
            context,
            AppIconPlatform.currentLauncherIconResource(context),
        )?.toBitmap()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(request.notificationTitle)
            .setContentText(request.notificationBody)
            .setStyle(
                backdropBitmap?.let { bitmap ->
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(appIconBitmap)
                        .setSummaryText(request.notificationBody)
                } ?: NotificationCompat.BigTextStyle().bigText(request.notificationBody),
            )
            .setLargeIcon(appIconBitmap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    internal suspend fun loadBackdropBitmap(backdropUrl: String?): Bitmap? {
        val imageUrl = backdropUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching {
            val bytes: ByteArray = httpClient.get(imageUrl).body()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun buildPendingIntent(
        context: Context,
        request: EpisodeReleaseNotificationRequest,
    ): PendingIntent {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(request.deepLinkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            kotlin.math.abs(request.requestId.hashCode()),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelTrackedWork(workManager: WorkManager) {
        val context = appContext ?: return
        preferences(context)
            .getStringSet(scopedScheduledIdsKey(), emptySet())
            .orEmpty()
            .forEach { requestId ->
                awaitOperation(workManager.cancelUniqueWork(uniqueWorkName(requestId)))
            }
    }

    private fun awaitOperation(operation: Operation) {
        operation.result.get()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(platformPreferencesName, Context.MODE_PRIVATE)

    private fun scopedScheduledIdsKey(): String = ProfileScopedKey.of(scheduledIdsKey)

    private fun triggerAtEpochMs(releaseDateIso: String): Long? = runCatching {
        LocalDate.parse(releaseDateIso)
            .atTime(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun ensureNotificationChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val existingChannel = notificationManager.getNotificationChannel(channelId)
        if (existingChannel != null) return

        val channel = NotificationChannel(
            channelId,
            runBlocking { getString(Res.string.notifications_channel_episode_releases_name) },
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = runBlocking { getString(Res.string.notifications_channel_episode_releases_description) }
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun uniqueWorkName(requestId: String): String = "$workTag:$requestId"
}
