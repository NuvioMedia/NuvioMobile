package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tracking.TrackingAttributedItem
import kotlinx.serialization.Serializable

enum class LibraryShelf(val key: String, val displayTitle: String) {
    PLAN_TO_WATCH("plan_to_watch", "Plan to watch"),
    CURRENTLY_WATCHING("currently_watching", "Currently Watching"),
    REVISIT("revisit", "Revisit"),
    COMPLETED("completed", "Completed"),
    DROPPED("dropped", "Dropped"),
    ON_HOLD("on_hold", "On Hold"),
    ARCHIVE("archive", "Archive");

    companion object {
        fun fromKey(key: String?): LibraryShelf? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

fun isNSFWGenreOrTag(tag: String): Boolean {
    val t = tag.trim().lowercase()
    return t == "erotica" || t == "erotic" || t == "hentai" || t == "adult" ||
           t == "ecchi" || t == "nsfw" || t == "18+" || t == "porn" ||
           t == "pornography" || t == "xxx" || t == "sex" || t == "nude" ||
           t == "nudity" || t == "smut" || t.contains("hentai") || t.contains("erotica")
}

fun LibraryItem.isNSFWItem(): Boolean = isNSFW || genres.any { isNSFWGenreOrTag(it) }

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    /** Original media category from the tracking provider (e.g. "anime").
     *  Used for UI filtering while [type] stays as "movie"/"series" for meta addon compatibility. */
    val mediaCategory: String? = null,
    override val trackingProviderId: String? = null,
    override val trackingProviderItemId: String? = null,
    override val trackingSourceUrl: String? = null,
    val savedAtEpochMs: Long,
    val shelf: String? = null,
    val shelfSeason: Int? = null,
    val shelfEpisode: Int? = null,
    val lastWatchedAtEpochMs: Long? = null,
    val isPrivate: Boolean = false,
    val isNSFW: Boolean = false,
    val isArchive: Boolean = false,
) : TrackingAttributedItem {
    override val trackingContentId: String
        get() = id
}

data class LibrarySection(
    val type: String,
    val displayTitle: String,
    val items: List<LibraryItem>,
)

internal fun librarySectionItemKey(sectionType: String, item: LibraryItem): String =
    "$sectionType|${item.type}|${item.id}"

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
    SIMKL,
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

fun MetaDetails.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        rawReleaseDate = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = PosterShape.Poster,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
        isNSFW = genres.any { isNSFWGenreOrTag(it) },
    )

fun MetaPreview.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        rawReleaseDate = rawReleaseDate,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
        isNSFW = genres.any { isNSFWGenreOrTag(it) },
    )

fun LibraryItem.toMetaPreview(): MetaPreview {
    val displayDate = if (type.trim().lowercase() == "movie") {
        rawReleaseDate.takeIf { !it.isNullOrBlank() } ?: releaseInfo
    } else {
        releaseInfo
    }
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = displayDate,
        rawReleaseDate = rawReleaseDate,
        imdbRating = imdbRating,
        genres = genres,
    )
}
