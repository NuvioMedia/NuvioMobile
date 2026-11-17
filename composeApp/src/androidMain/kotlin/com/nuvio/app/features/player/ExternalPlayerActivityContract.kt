package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Result returned by an external video player after playback ends.
 * Not all players return this data — MX Player, VLC, Just Player, and mpv-android are known to support it.
 */
data class ExternalPlayerResult(
    val positionMs: Long,
    val durationMs: Long?,
    val endedByUser: Boolean = true,
)

/**
 * ActivityResultContract that launches an external video player via a pre-built Intent
 * and parses the playback position returned by the player (if supported).
 *
 * Supported players:
 * - MX Player: returns "position" (Int, ms), "duration" (Int, ms), "end_by" (String)
 * - VLC: returns "extra_position" (Long, ms), "extra_duration" (Long, ms)
 * - Just Player: returns "position" (Int, ms), "duration" (Int, ms)
 * - mpv-android: returns "position" (Int, ms), "duration" (Int, ms)
 * - Vimu Player: returns "position" (Int, ms), "duration" (Int, ms)
 */
class ExternalPlayerActivityContract : ActivityResultContract<Intent, ExternalPlayerResult?>() {

    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): ExternalPlayerResult? {
        // Some players return RESULT_OK, others return RESULT_CANCELED even on normal exit.
        // We try to parse position regardless of resultCode.
        val data = intent ?: return null
        val endedByUser = parseEndReason(data)
        val duration = parseDuration(data)
        // MX Player omits both "position" and "duration" once a file has played to completion, so a
        // completed playback must not be discarded just because it carries no position. Completion is
        // additionally gated on RESULT_OK so an errored playback cannot be recorded as watched.
        val parsedPosition = parsePosition(data)
        val position = when {
            parsedPosition != null -> parsedPosition
            !endedByUser && resultCode == Activity.RESULT_OK -> duration ?: 0L
            else -> return null
        }

        return ExternalPlayerResult(
            positionMs = position,
            durationMs = duration,
            endedByUser = endedByUser,
        )
    }

    private fun parsePosition(data: Intent): Long? {
        // VLC uses Long extras
        val vlcPosition = data.getLongExtra("extra_position", -1L)
        if (vlcPosition > 0) return vlcPosition

        // MX Player / Just Player / mpv / Vimu use Int extras
        val mxPosition = data.getIntExtra("position", -1)
        if (mxPosition > 0) return mxPosition.toLong()

        return null
    }

    private fun parseDuration(data: Intent): Long? {
        val vlcDuration = data.getLongExtra("extra_duration", -1L)
        if (vlcDuration > 0) return vlcDuration

        val mxDuration = data.getIntExtra("duration", -1)
        if (mxDuration > 0) return mxDuration.toLong()

        return null
    }

    private fun parseEndReason(data: Intent): Boolean {
        // MX Player returns "end_by" with values "user" or "playback_completion"
        val endBy = data.getStringExtra("end_by")
        return endBy != "playback_completion"
    }
}
