package com.nuvio.app.features.livetv.record

/** The provider answered a recording's request with an HTTP error. */
internal class RecordingHttpError(val code: Int) : Exception("HTTP $code")

/**
 * What recordings need from the device: files in the app's own storage, a stream copied to a
 * file, the app kept running while it records, alarms for scheduled recordings, notifications.
 */
internal expect object RecordingPlatform {
    /** The folder recordings live in (each in a folder of its own). Empty when the platform has none. */
    fun recordingsRoot(): String

    /** Every recording folder under the root (absolute paths). */
    fun listRecordingDirs(): List<String>

    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun exists(path: String): Boolean
    fun deleteDir(path: String)
    fun dirSize(path: String): Long
    fun fileSize(path: String): Long

    /** Room left where recordings go. */
    fun usableSpace(): Long

    /** A link the app's player opens for a file on the device. */
    fun playUrl(path: String): String

    /** The platform can write files and open streams (false where recordings aren't built yet). */
    val available: Boolean

    /**
     * Copies the stream at [url] into [path] (appended to when [append]) until it ends or
     * [shouldStop] says so, calling [onProgress] with the bytes written so far now and then.
     * Throws [RecordingHttpError] for an HTTP error, or another exception with a plain-English
     * message when the connection fails.
     */
    suspend fun download(url: String, headers: Map<String, String>, path: String, append: Boolean, shouldStop: () -> Boolean, onProgress: (Long) -> Unit)

    /** Reads a small text resource (an HLS playlist) over HTTP. */
    suspend fun fetchText(url: String, headers: Map<String, String>): String

    /** Keeps the app running (a foreground service with a notice) while anything records. */
    fun keepAlive(active: Boolean)

    /** Wakes the app at [atMs] to start the schedule with this id (exact when allowed). */
    fun scheduleAlarm(id: String, atMs: Long)
    fun cancelAlarm(id: String)

    /** Whether exact alarms are allowed (Android 12+ asks the person first). */
    fun canScheduleExact(): Boolean

    /** Opens the system page where exact alarms are allowed. */
    fun requestExactAlarms()

    /** Whether the app may show notifications. */
    fun notificationsAllowed(): Boolean

    /** A notification about a recording (one per [id]; a new one with the same id replaces it). */
    fun notify(id: String, title: String, text: String)

    /**
     * A reminder notification (a show or game about to start): tapping it opens [openUrl] in the
     * app (a Live TV link, see LiveTvDeepLinks), or the app itself when null. [important] makes
     * it a heads-up one with sound. One per [id].
     */
    fun notifyReminder(id: String, title: String, text: String, openUrl: String?, important: Boolean)

    /** Takes a notification posted with [notifyReminder] or [notify] down again. */
    fun cancelNotification(id: String)

    /** Whether the device is online right now. */
    fun online(): Boolean
}
