package com.nuvio.app.features.livetv.record

/** Recordings aren't built for iOS yet: nothing is written, nothing is scheduled. */
internal actual object RecordingPlatform {
    actual val available: Boolean get() = false
    actual fun recordingsRoot(): String = ""
    actual fun listRecordingDirs(): List<String> = emptyList()
    actual fun readText(path: String): String? = null
    actual fun writeText(path: String, text: String) {}
    actual fun exists(path: String): Boolean = false
    actual fun deleteDir(path: String) {}
    actual fun dirSize(path: String): Long = 0L
    actual fun fileSize(path: String): Long = 0L
    actual fun usableSpace(): Long = 0L
    actual fun playUrl(path: String): String = path
    actual suspend fun download(url: String, headers: Map<String, String>, path: String, append: Boolean, shouldStop: () -> Boolean, onProgress: (Long) -> Unit) {
        error("Recording isn't available on this platform yet")
    }
    actual suspend fun fetchText(url: String, headers: Map<String, String>): String = error("Recording isn't available on this platform yet")
    actual suspend fun fetchTextAt(url: String, headers: Map<String, String>): Pair<String, String> = error("Recording isn't available on this platform yet")
    actual fun keepAlive(active: Boolean) {}
    actual fun scheduleAlarm(id: String, atMs: Long) {}
    actual fun cancelAlarm(id: String) {}
    actual fun canScheduleExact(): Boolean = false
    actual fun requestExactAlarms() {}
    actual fun notificationsAllowed(): Boolean = false
    actual fun notify(id: String, title: String, text: String) {}
    actual fun notifyReminder(id: String, title: String, text: String, openUrl: String?, important: Boolean) {}
    actual fun cancelNotification(id: String) {}
    actual fun online(): Boolean = true
}
