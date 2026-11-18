package com.nuvio.app.car

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log for Nuvio Auto, so a failure in the car can be read (and shared) from Nuvio Auto Setup
 * without adb. Keeps the last few hundred lines in the app's files folder. Also records crashes.
 */
internal object CarLog {
    private const val TAG = "NuvioAuto"
    private const val MAX_LINES = 300
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "nuvio_auto_log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashRecorder) Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    @Synchronized
    fun d(message: String, error: Throwable? = null) {
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
        val f = file ?: return
        runCatching {
            val line = buildString {
                append(time.format(Date())).append("  ").append(message)
                if (error != null) append("\n    ").append(error.javaClass.simpleName).append(": ").append(error.message)
            }
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line.lines()
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    fun read(ctx: Context): String {
        init(ctx)
        val f = file ?: return ""
        return runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
    }

    fun clear(ctx: Context) {
        init(ctx)
        runCatching { file?.delete() }
    }

    fun deviceLine(): String =
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    private class CrashRecorder(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                d("CRASH on ${t.name}: ${e.stackTraceToString().lines().take(12).joinToString("\n    ")}")
            }
            next?.uncaughtException(t, e)
        }
    }
}
