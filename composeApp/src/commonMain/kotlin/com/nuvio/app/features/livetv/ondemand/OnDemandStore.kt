package com.nuvio.app.features.livetv.ondemand

/**
 * On-device index of every provider's movies and series, the TV app's OnDemandDatabase. Providers
 * can list 100,000+ titles, far too many to keep in memory, so they live in a small SQLite
 * database and are read a page at a time.
 *
 * Tables (as the TV app's "livetv_on_demand.db", plus [VodItem.url] for M3U entries):
 *  - items(pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre, h, url)
 *  - categories(pl, kind, id, name, position)
 *  - posters(uid, poster, checked, title, tmdb): posters found for titles, remembered between starts
 *  - prefixes(pl, prefix): each provider's learned title tags
 */
internal expect object OnDemandStore {
    /**
     * Brings one provider's catalog of [kind] up to date in a single transaction: only titles that
     * are new or changed are written, titles the provider no longer lists are removed. Returns
     * how many titles the provider listed.
     */
    fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>): Int

    /** Drops every provider not in [keepPlaylistIds]; returns the ones it dropped. */
    fun removeExcept(keepPlaylistIds: Set<String>): Set<String>

    fun hasAny(): Boolean

    fun count(kind: VodKind): Int

    /** A provider's categories that have titles in them, in the provider's order. */
    fun categories(kind: VodKind): List<VodCategory>

    /** A page of titles: in one category, or across all of [kind] (hidden categories left out). */
    fun items(kind: VodKind, category: VodCategory?, excludeCategories: Set<String>, limit: Int, offset: Int, sort: VodSort, merge: Boolean): List<VodItem>

    /** Titles matching [text]: those starting with it first, then those with every word anywhere in the name. */
    fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int, sort: VodSort, merge: Boolean): List<VodItem>

    /** Every copy of a title: same TMDB id, or same name and year (other quality, other provider). */
    fun versions(item: VodItem): List<VodItem>

    fun categoryNames(playlistId: String, kind: VodKind): Map<String, String>

    fun item(playlistId: String, kind: VodKind, id: String): VodItem?

    /** Works out this provider's title tags from its catalog and stores them; returns every provider's tags. */
    fun learnPrefixes(playlistId: String): Set<String>

    fun hasLearnedPrefixes(playlistId: String): Boolean

    /** Every provider's learned tags, lower case. */
    fun loadPrefixes(): Set<String>

    fun cachedMatch(uid: String): VodMatch?

    fun cacheMatch(uid: String, match: VodMatch)

    /** Gives space left empty by removed titles back to the system when there's a lot of it. */
    fun compact()
}
