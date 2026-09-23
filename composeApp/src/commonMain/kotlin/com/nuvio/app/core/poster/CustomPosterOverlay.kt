package com.nuvio.app.core.poster

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlin.jvm.JvmName

/**
 * Applies a custom poster URL pattern to a [MetaPreview].
 * The original poster is preserved in [MetaPreview.rawPosterUrl] for fallback on load error.
 */
fun MetaPreview.withCustomPosterUrl(pattern: String): MetaPreview {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(id)
    val contentType = if (type.equals("movie", ignoreCase = true)) "movie" else "series"
    val supportsShape = "{shape}" in pattern

    if (!supportsShape && posterShape != PosterShape.Poster) return this

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = contentType,
        shape = when {
            supportsShape -> when (posterShape) {
                PosterShape.Poster -> "poster"
                PosterShape.Landscape -> "landscape"
                PosterShape.Square -> "square"
            }
            else -> "poster"
        }
    )

    val resolvedLandscape = if (supportsShape) {
        CustomPosterUrlResolver.resolve(pattern, ids, contentType, shape = "landscape")
    } else null

    if (resolvedPoster == null && resolvedLandscape == null) return this

    return copy(
        poster = resolvedPoster ?: poster,
        rawPosterUrl = rawPosterUrl ?: poster,
        landscapePoster = resolvedLandscape ?: landscapePoster
    )
}

/**
 * Re-applies (or clears) a custom poster URL overlay.
 * First restores the original poster from [rawPosterUrl], then applies the new pattern.
 * Use this when the pattern changes and cached items already carry a previous overlay.
 */
fun MetaPreview.reapplyCustomPosterUrl(pattern: String): MetaPreview {
    val restored = if (rawPosterUrl != null) {
        copy(poster = rawPosterUrl, landscapePoster = null)
    } else {
        this
    }
    if (pattern.isBlank()) return restored
    return restored.withCustomPosterUrl(pattern)
}

fun List<MetaPreview>.reapplyCustomPosterUrls(pattern: String): List<MetaPreview> =
    map { it.reapplyCustomPosterUrl(pattern) }

fun List<MetaPreview>.withCustomPosterUrls(pattern: String): List<MetaPreview> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}

/**
 * Applies a custom poster URL pattern to a [LibraryItem].
 */
fun com.nuvio.app.features.library.LibraryItem.withCustomPosterUrl(
    pattern: String
): com.nuvio.app.features.library.LibraryItem {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(id, explicitImdbId = imdbId)
    val contentType = if (type.equals("tv", ignoreCase = true) || type.equals("series", ignoreCase = true)) "series" else type
    val supportsShape = "{shape}" in pattern

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = contentType,
        shape = "poster"
    )
    val resolvedLandscape = if (supportsShape) {
        CustomPosterUrlResolver.resolve(pattern, ids, contentType, shape = "landscape")
    } else null

    if (resolvedPoster == null && resolvedLandscape == null) return this

    return copy(
        poster = resolvedPoster ?: poster,
        rawPosterUrl = rawPosterUrl ?: poster,
        landscapePoster = resolvedLandscape ?: landscapePoster,
    )
}

@JvmName("withCustomPosterUrlsLibrary")
fun List<com.nuvio.app.features.library.LibraryItem>.withCustomPosterUrls(
    pattern: String
): List<com.nuvio.app.features.library.LibraryItem> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}

/**
 * Applies a custom poster URL pattern to a [ContinueWatchingItem].
 * The `poster` field is replaced with the portrait resolve. When the pattern contains
 * `{shape}`, a landscape URL is also resolved and set as `background` so that
 * wide/card CW layouts pick it up automatically.
 */
fun com.nuvio.app.features.watchprogress.ContinueWatchingItem.withCustomPosterUrl(
    pattern: String
): com.nuvio.app.features.watchprogress.ContinueWatchingItem {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(parentMetaId)
    val contentType = if (parentMetaType.equals("movie", ignoreCase = true)) "movie" else "series"
    val supportsShape = "{shape}" in pattern

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = contentType,
        shape = "poster"
    )
    val resolvedLandscape = if (supportsShape) {
        CustomPosterUrlResolver.resolve(pattern, ids, contentType, shape = "landscape")
    } else null

    if (resolvedPoster == null && resolvedLandscape == null) return this

    val originalPoster = poster
    val originalBackground = background
    return copy(
        poster = resolvedPoster ?: poster,
        background = resolvedLandscape ?: background,
        imageUrl = if (imageUrl == originalPoster && resolvedPoster != null) resolvedPoster else imageUrl,
        rawPosterUrl = rawPosterUrl ?: originalPoster,
        rawBackgroundUrl = if (resolvedLandscape != null) (rawBackgroundUrl ?: originalBackground) else rawBackgroundUrl,
    )
}

@JvmName("withCustomPosterUrlsCw")
fun List<com.nuvio.app.features.watchprogress.ContinueWatchingItem>.withCustomPosterUrls(
    pattern: String
): List<com.nuvio.app.features.watchprogress.ContinueWatchingItem> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}

/**
 * Applies custom poster URL overlay to [moreLikeThis] and [collectionItems]
 * inside a [MetaDetails] object.
 */
fun com.nuvio.app.features.details.MetaDetails.withCustomPosterUrls(
    pattern: String
): com.nuvio.app.features.details.MetaDetails {
    if (pattern.isBlank()) return this
    val overlaidMoreLikeThis = moreLikeThis.withCustomPosterUrls(pattern)
    val overlaidCollection = collectionItems.withCustomPosterUrls(pattern)
    if (overlaidMoreLikeThis === moreLikeThis && overlaidCollection === collectionItems) return this
    return copy(
        moreLikeThis = overlaidMoreLikeThis,
        collectionItems = overlaidCollection,
    )
}

/**
 * Applies custom poster URL overlay to all rails inside a [TmdbEntityBrowseData].
 */
fun com.nuvio.app.features.tmdb.TmdbEntityBrowseData.withCustomPosterUrls(
    pattern: String
): com.nuvio.app.features.tmdb.TmdbEntityBrowseData {
    if (pattern.isBlank()) return this
    return copy(
        rails = rails.map { rail ->
            rail.copy(items = rail.items.withCustomPosterUrls(pattern))
        }
    )
}

/**
 * Applies custom poster URL overlay to [movieCredits] and [tvCredits]
 * inside a [PersonDetail].
 */
fun com.nuvio.app.features.details.PersonDetail.withCustomPosterUrls(
    pattern: String
): com.nuvio.app.features.details.PersonDetail {
    if (pattern.isBlank()) return this
    return copy(
        movieCredits = movieCredits.withCustomPosterUrls(pattern),
        tvCredits = tvCredits.withCustomPosterUrls(pattern),
    )
}
