package com.nuvio.app.features.autosync

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    fun cancelForManualSubtitleDelay()
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}
