package com.nuvio.app.features.autosync

/**
 * Early stop for one candidate subtitle. A correct subtitle aligns clearly with any full-dialogue
 * reference, and references are tried best-first, so when [stopAfterSources] independent
 * reference sources all found no fit, the subtitle itself is the problem and its remaining
 * reference pairs are not worth evaluating.
 *
 * Near-identical tracks (one source in several variants) count once, so copies of a single
 * unsuitable source cannot end the search on their own.
 */
internal class AutoSyncNoFitTracker(
    private val stopAfterSources: Int = DEFAULT_STOP_AFTER_SOURCES,
) {
    private val noFitSources = ArrayList<AutoSyncTimelineRetimer.PreparedActivity>(stopAfterSources)

    /** Records a no-fit result against [reference]; true once the candidate should be abandoned. */
    fun recordNoFit(reference: AutoSyncTimelineRetimer.PreparedActivity): Boolean {
        if (noFitSources.none { AutoSyncReferenceConsistency.isSameSource(it, reference) }) {
            noFitSources += reference
        }
        return noFitSources.size >= stopAfterSources
    }

    val sourceCount: Int get() = noFitSources.size

    companion object {
        private const val DEFAULT_STOP_AFTER_SOURCES = 3

        // Rejected pairs this ambiguous have no competing alignment worth refining. A real but
        // weak match (e.g. a margin just under the 0.02 acceptance gate) does not count.
        private const val NO_FIT_MAX_ACTIVITY_MARGIN = 0.01

        fun isNoFit(result: AutoSyncTimelineRetimeResult?): Boolean =
            result == null ||
                (!result.confident && result.activityMargin < NO_FIT_MAX_ACTIVITY_MARGIN)
    }
}
