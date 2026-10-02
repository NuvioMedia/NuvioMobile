@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.extractor.Extractor

/**
 * An AutoSync extractor wrapper that forwards everything
 * to [wrappedExtractor] and can be rebuilt around a different inner extractor. Lets Nuvio swap in
 * the libass Matroska extractor underneath the wrappers, where it would otherwise not see it.
 */
internal interface RewrappableExtractor : Extractor {
    val wrappedExtractor: Extractor

    fun rewrap(inner: Extractor): Extractor
}

/** Returns this extractor's wrappers rebuilt around [replacement] in place of the innermost extractor. */
internal fun Extractor.replaceInnermostExtractor(replacement: Extractor): Extractor =
    if (this is RewrappableExtractor) {
        rewrap(wrappedExtractor.replaceInnermostExtractor(replacement))
    } else {
        replacement
    }
