package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_stream_blocked
import nuvio.composeapp.generated.resources.player_error_stream_expired
import nuvio.composeapp.generated.resources.player_error_stream_rate_limited
import nuvio.composeapp.generated.resources.player_error_stream_removed
import nuvio.composeapp.generated.resources.player_error_stream_unavailable
import org.jetbrains.compose.resources.StringResource

private val MPV_HTTP_ERROR_REGEX = Regex("HTTP error (\\d{3})(?:\\s+(.+))?", RegexOption.IGNORE_CASE)
private val MPV_URL_REGEX = Regex("https?://\\S+")
private const val MPV_RECENT_ERROR_LINES = 3

/**
 * The mpv log lines behind a failed load, for the error screen. The HTTP status explains most
 * failed opens, but FFmpeg logs it as a warning, so it is kept apart from the error lines and
 * shown first. ytdl_hook lines are dropped: Android has no youtube-dl, so its fallback can only
 * fail, and its lines would push the real cause out.
 */
internal class MpvErrorLog {
    private var httpError: String? = null
    private var lastHttpStatus: Int? = null
    private val recentErrors = ArrayDeque<String>()

    @Synchronized
    fun clear() {
        httpError = null
        lastHttpStatus = null
        recentErrors.clear()
    }

    @Synchronized
    fun onLog(prefix: String, isError: Boolean, text: String) {
        val line = text.trim()
        if (line.isEmpty() || prefix.contains("ytdl_hook", ignoreCase = true)) return
        val http = MPV_HTTP_ERROR_REGEX.find(line)
        if (http != null) {
            val reason = http.groupValues[2].replace(MPV_URL_REGEX, "").trim()
            httpError = "HTTP ${http.groupValues[1]} $reason".trim()
            lastHttpStatus = http.groupValues[1].toInt()
            return
        }
        if (!isError || "://" in line) return
        recentErrors.addLast("[$prefix] ${line.take(160)}")
        while (recentErrors.size > MPV_RECENT_ERROR_LINES) recentErrors.removeFirst()
    }

    /** The HTTP status FFmpeg reported for the failed open, if any. */
    @Synchronized
    fun httpStatus(): Int? = lastHttpStatus

    /** What to show for a failed load, or null when mpv logged nothing useful. */
    @Synchronized
    fun message(fileError: String?): String? =
        (listOfNotNull(httpError) + recentErrors + listOfNotNull(fileError?.trim()?.takeIf { it.isNotEmpty() }))
            .distinct()
            .joinToString("\n")
            .ifEmpty { null }
}

/** A plain-language reason for an HTTP status, shown above the technical error. Same mapping as NuvioTV. */
internal fun httpStatusExplanation(code: Int): StringResource? = when (code) {
    401, 410 -> Res.string.player_error_stream_expired
    404 -> Res.string.player_error_stream_removed
    429 -> Res.string.player_error_stream_rate_limited
    in 500..599 -> Res.string.player_error_stream_unavailable
    in 400..499 -> Res.string.player_error_stream_blocked
    else -> null
}
