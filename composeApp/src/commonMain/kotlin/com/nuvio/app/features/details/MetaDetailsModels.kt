package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamItem

data class MetaDetails(
    val id: String,
    val type: String,
    val name: String,
    val imdbId: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    /** TV: ISO last air date from TMDB (or addon) for year-range display. */
    val lastAirDate: String? = null,
    val status: String? = null,
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val externalRatings: List<MetaExternalRating> = emptyList(),
    val genres: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val cast: List<MetaPerson> = emptyList(),
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val country: String? = null,
    val awards: String? = null,
    val language: String? = null,
    val website: String? = null,
    val hasScheduledVideos: Boolean = false,
    val defaultVideoId: String? = null,
    val moreLikeThis: List<MetaPreview> = emptyList(),
    val moreLikeThisSource: MoreLikeThisSource? = null,
    val collectionName: String? = null,
    val collectionItems: List<MetaPreview> = emptyList(),
    val trailers: List<MetaTrailer> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val seasonPosters: Map<Int, String> = emptyMap(),
    val videos: List<MetaVideo> = emptyList(),
)

enum class MoreLikeThisSource {
    TMDB,
    TRAKT,
    SIMKL,
}

data class MetaExternalRating(
    val source: String,
    val value: Double,
    val isCertified: Boolean = false,
)

data class MetaTrailer(
    val id: String,
    val key: String,
    val name: String,
    val site: String,
    val size: Int? = null,
    val type: String = "Trailer",
    val official: Boolean = false,
    val publishedAt: String? = null,
    val seasonNumber: Int? = null,
    val displayName: String? = null,
    val iso6391: String? = null,
)

data class MetaPerson(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null,
)

data class MetaCompany(
    val name: String,
    val logo: String? = null,
    val tmdbId: Int? = null,
)

data class MetaLink(
    val name: String,
    val category: String,
    val url: String,
)

data class MetaVideo(
    val id: String,
    val title: String,
    val released: String? = null,
    val available: Boolean = true,
    val thumbnail: String? = null,
    val seasonPoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    val streams: List<StreamItem> = emptyList(),
)

data class MetaDetailsUiState(
    val isLoading: Boolean = false,
    val meta: MetaDetails? = null,
    val errorMessage: String? = null,
)
