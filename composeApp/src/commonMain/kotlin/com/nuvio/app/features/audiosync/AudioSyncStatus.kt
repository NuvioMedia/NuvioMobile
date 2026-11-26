package com.nuvio.app.features.audiosync

/** Progress worth showing to the user. */
internal sealed interface AudioSyncStatus {
    data object Failed : AudioSyncStatus

    /** A subtitle was picked and the audio is being analysed. */
    data object Listening : AudioSyncStatus

    /** No second decoder for this codec: syncing uses the playing audio only, without look-ahead. */
    data class LiveOnly(val mimeType: String) : AudioSyncStatus

    /** The early estimate could not be confirmed; subtitles are back on the file's own timing. */
    data object Withdrawn : AudioSyncStatus

    /** An early, unconfirmed estimate was applied; it keeps adjusting until confirmed. */
    data class Estimated(val offsetMs: Long) : AudioSyncStatus

    /** Subtitles now follow the audio; [offsetMs] is the applied delay at the playhead. */
    data class Synced(val offsetMs: Long, val rateCorrected: Boolean) : AudioSyncStatus

    /** The subtitle timing changed partway through (a different cut). */
    data class Adjusted(val offsetMs: Long) : AudioSyncStatus
}
