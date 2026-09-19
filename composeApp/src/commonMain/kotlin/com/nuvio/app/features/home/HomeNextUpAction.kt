package com.nuvio.app.features.home

import com.nuvio.app.features.details.SeriesPrimaryAction

/**
 * Whether the primary action of a series offers nothing the next-up row should show.
 *
 * Two actions get a button on the detail screen but are not "next up":
 * - a resume of an unfinished episode, which the row already shows as progress, and
 * - a "watch again" restart of a series watched to its end, which would otherwise put an S01E01
 *   card in the row for every series the user ever finished.
 *
 * A rewatch run is a real offer and passes through.
 */
internal fun homeNextUpOffersNothing(action: SeriesPrimaryAction): Boolean =
    action.isWatchAgain || action.resumePositionMs != null
