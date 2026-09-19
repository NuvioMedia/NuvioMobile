package com.nuvio.app.features.tracking

import kotlinx.serialization.Serializable

/**
 * Where a rewatch run stands: the last episode of a series the user rewatched.
 *
 * Simkl keeps a rewatch in its own session and never moves the canonical watch position, so the
 * Continue Watching row would keep pointing at the episode the user finished months ago. A run
 * position turns the series into a next-up card that follows the rewatch instead: a run at S01E02
 * offers S01E03 next.
 *
 * The position is read from the tracking account (see `deriveSimklRewatchRuns`) rather than stored on
 * the device, so the row behaves the same on every device the user signs in on. The rule for what
 * counts as a run lives with that reader: two episodes in a row, never a single one.
 *
 * [matchKeys] holds every ID form of the series (imdb, tmdb, tvdb, simkl, the catalogue id), because
 * the Continue Watching pipeline builds its series key from whichever id the playback carried.
 */
@Serializable
data class RewatchRunPosition(
    val contentId: String,
    val matchKeys: List<String> = emptyList(),
    /** The last episode of the run the user rewatched. */
    val seasonNumber: Int,
    val episodeNumber: Int,
    /** When that episode was rewatched, so a fresh run outranks an old watch position. */
    val markedAtEpochMs: Long,
) {
    fun matches(contentId: String?): Boolean {
        val candidate = contentId?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        if (candidate.equals(this.contentId, ignoreCase = true)) return true
        return matchKeys.any { key -> key.equals(candidate, ignoreCase = true) }
    }
}
