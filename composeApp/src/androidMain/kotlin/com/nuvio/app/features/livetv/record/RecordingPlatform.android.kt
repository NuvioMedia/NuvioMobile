package com.nuvio.app.features.livetv.record

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal actual object RecordingPlatform {
    internal const val CHANNEL_ID = "livetv_recordings"
    internal const val REMINDER_CHANNEL_ID = "livetv_reminders"
    private var appContext: Context? = null

    /** The app's context once [initialize] ran (other Live TV parts on Android use it too). */
    internal fun context(): Context? = appContext

    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        ensureChannel()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // A live stream never ends; only a stall counts as a failure.
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun root(): File? = appContext?.let { File(File(it.filesDir, "livetv"), "recordings").also { d -> d.mkdirs() } }

    actual val available: Boolean get() = appContext != null

    actual fun recordingsRoot(): String = root()?.absolutePath.orEmpty()

    actual fun listRecordingDirs(): List<String> = root()?.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath }.orEmpty()

    actual fun readText(path: String): String? = File(path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    actual fun writeText(path: String, text: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun deleteDir(path: String) { runCatching { File(path).deleteRecursively() } }

    actual fun dirSize(path: String): Long = runCatching { File(path).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    actual fun fileSize(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    actual fun usableSpace(): Long = runCatching { (root() ?: appContext?.filesDir)?.usableSpace ?: Long.MAX_VALUE }.getOrDefault(Long.MAX_VALUE)

    actual fun playUrl(path: String): String = File(path).toURI().toString()

    actual suspend fun download(url: String, headers: Map<String, String>, path: String, append: Boolean, shouldStop: () -> Boolean, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) } }.get().build()
            val target = File(path)
            target.parentFile?.mkdirs()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RecordingHttpError(response.code)
                    val body = response.body ?: throw IllegalStateException("Empty response")
                    body.byteStream().use { input ->
                        FileOutputStream(target, append).buffered(256 * 1024).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = if (append) target.length() else 0L
                            while (!shouldStop()) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n == 0) continue
                                out.write(buf, 0, n)
                                total += n
                                onProgress(total)
                            }
                        }
                    }
                }
            } catch (e: RecordingHttpError) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(describe(e), e)
            }
        }

    actual suspend fun fetchText(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) } }.get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RecordingHttpError(response.code)
                response.body?.string() ?: throw IllegalStateException("Empty response")
            }
        } catch (e: RecordingHttpError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(describe(e), e)
        }
    }

    actual suspend fun fetchTextAt(url: String, headers: Map<String, String>): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) } }.get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RecordingHttpError(response.code)
                val body = response.body ?: throw IllegalStateException("Empty response")
                body.string() to response.request.url.toString()
            }
        } catch (e: RecordingHttpError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(describe(e), e)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "Couldn't find the provider's server"
        is SocketTimeoutException -> "The provider stopped sending (timeout)"
        is java.net.ConnectException -> "Couldn't connect to the provider's server"
        else -> e.message ?: e.javaClass.simpleName
    }

    actual fun keepAlive(active: Boolean) {
        val context = appContext ?: return
        if (active) LiveTvRecordingService.start(context) else LiveTvRecordingService.stop(context)
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).setAction(RecordingAlarmReceiver.ACTION_FIRE).putExtra(RecordingAlarmReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    actual fun scheduleAlarm(id: String, atMs: Long) {
        val context = appContext ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            if (canScheduleExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context, id))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context, id))
        }
    }

    actual fun cancelAlarm(id: String) {
        val context = appContext ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(context, id)) }
    }

    actual fun canScheduleExact(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    actual fun requestExactAlarms() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    actual fun notificationsAllowed(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live TV recordings", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Recordings starting, finishing or failing"
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Live TV reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows and games you asked to be reminded about, and scores for teams you follow"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun notificationId(id: String): Int = 7400 + (id.hashCode() and 0xffff)

    actual fun notifyReminder(id: String, title: String, text: String, openUrl: String?, important: Boolean) {
        val context = appContext ?: return
        if (!notificationsAllowed()) return
        ensureChannel()
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = if (openUrl != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN
            if (openUrl != null) data = Uri.parse(openUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(context, id.hashCode(), launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (important) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setDefaults(if (important) NotificationCompat.DEFAULT_ALL else 0)
            .setContentIntent(contentIntent)
            .apply { if (openUrl != null) addAction(0, "Open", contentIntent) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(id), notification) }
    }

    actual fun cancelNotification(id: String) {
        val context = appContext ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(id)) }
    }

    actual fun notify(id: String, title: String, text: String) {
        val context = appContext ?: return
        if (!notificationsAllowed()) return
        ensureChannel()
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(context, id.hashCode(), launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(id), notification) }
    }

    actual fun online(): Boolean = runCatching {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching true
        val n = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
}
