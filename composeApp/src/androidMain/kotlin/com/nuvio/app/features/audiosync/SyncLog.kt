package com.nuvio.app.features.audiosync

import android.util.Log

/** Debug-only logcat, with stream/subtitle URLs redacted before logging. */
internal object SyncLog {
    private const val TAG = "NuvioAudioSync"
    private val url = Regex("""https?://[^\s]+""")

    @Volatile
    var debugEnabled: Boolean = false

    private fun write(level: Int, message: String) {
        if (debugEnabled) Log.println(level, TAG, url.replace(message, "<url>"))
    }

    fun i(message: String) = write(Log.INFO, message)
    fun w(message: String) = write(Log.WARN, message)
    fun d(message: String) = write(Log.DEBUG, message)
}
