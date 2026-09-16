package com.nuvio.app.features.simkl

import kotlinx.serialization.Serializable

/**
 * Determines which external ID is used as the canonical content ID for anime entries
 * resolved through Simkl.
 *
 * By default (IMDB), anime entries that share the same IMDB ID get grouped together
 * (e.g. multiple MAL seasons of one franchise). Choosing MAL or KITSU gives each season
 * its own canonical ID so they appear as separate items in the library and watched state.
 */
@Serializable
enum class SimklAnimeIdPreference {
    /** Use IMDB ID when available (groups multiple seasons under one ID). */
    IMDB,

    /** Prefer MyAnimeList ID — each MAL entry gets its own canonical ID. */
    MAL,

    /** Prefer Kitsu ID — each Kitsu entry gets its own canonical ID. */
    KITSU,

    /** Use TVDB ID — stable across seasons, groups like IMDB but avoids per-season IMDB splits. */
    TVDB;

    companion object {
        fun fromStorage(value: String?): SimklAnimeIdPreference =
            entries.firstOrNull { it.name == value } ?: DEFAULT_SIMKL_ANIME_ID_PREFERENCE
    }
}

val DEFAULT_SIMKL_ANIME_ID_PREFERENCE: SimklAnimeIdPreference = SimklAnimeIdPreference.IMDB
