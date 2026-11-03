package com.nuvio.app.features.livetv.ondemand

/** In-memory stand-in until Live TV is built for iOS. */
internal actual object OnDemandStore {
    private val items = HashMap<String, VodItem>()
    private val categories = HashMap<String, List<VodCategory>>()
    private val matches = HashMap<String, VodMatch>()
    private val prefixes = HashMap<String, Set<String>>()

    private fun key(pl: String, kind: VodKind) = "$pl|${kind.key}"

    actual fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>): Int {
        this.items.keys.removeAll { k -> k.startsWith(key(playlistId, kind) + "|") }
        var n = 0
        items.forEach { this.items[key(playlistId, kind) + "|" + it.id] = it; n++ }
        this.categories[key(playlistId, kind)] = categories.map { (id, name) ->
            VodCategory(playlistId, kind, id, name, this.items.values.count { it.playlistId == playlistId && it.kind == kind && it.categoryId == id })
        }
        return n
    }

    actual fun removeExcept(keepPlaylistIds: Set<String>): Set<String> {
        val dropped = items.values.map { it.playlistId }.toSet() - keepPlaylistIds
        items.values.removeAll { it.playlistId in dropped }
        categories.keys.removeAll { k -> k.substringBefore('|') in dropped }
        return dropped
    }

    actual fun hasAny(): Boolean = items.isNotEmpty()
    actual fun count(kind: VodKind): Int = items.values.count { it.kind == kind }

    actual fun categories(kind: VodKind): List<VodCategory> =
        categories.values.flatten().filter { it.kind == kind && it.count > 0 }

    actual fun items(kind: VodKind, category: VodCategory?, excludeCategories: Set<String>, limit: Int, offset: Int, sort: VodSort, merge: Boolean): List<VodItem> =
        items.values.filter { it.kind == kind && (category == null || (it.playlistId == category.playlistId && it.categoryId == category.id)) }
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
            .drop(offset).take(limit)

    actual fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val q = VodTitles.normalize(text)
        if (q.isEmpty()) return emptyList()
        return items.values.filter { it.kind == kind && VodTitles.normalize(it.name).contains(q) }
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
            .take(limit)
    }

    actual fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        items.values.filter { it.kind == kind && it.tmdbId == tmdbId }

    actual fun byTitle(kind: VodKind, title: String): List<VodItem> {
        val norm = VodTitles.normalize(title)
        if (norm.isBlank()) return emptyList()
        return items.values.filter { it.kind == kind && VodTitles.normalize(it.name) == norm }
    }

    actual fun versions(item: VodItem): List<VodItem> = listOf(item)

    actual fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> =
        categories[key(playlistId, kind)].orEmpty().associate { it.id to it.name }

    actual fun item(playlistId: String, kind: VodKind, id: String): VodItem? = items[key(playlistId, kind) + "|" + id]

    actual fun learnPrefixes(playlistId: String): Set<String> {
        prefixes[playlistId] = VodTitles.learnPrefixes(items.values.asSequence().filter { it.playlistId == playlistId }.map { it.name })
        return loadPrefixes()
    }

    actual fun hasLearnedPrefixes(playlistId: String): Boolean = prefixes.containsKey(playlistId)
    actual fun loadPrefixes(): Set<String> = prefixes.values.flatten().toSet()
    actual fun cachedMatch(uid: String): VodMatch? = matches[uid]
    actual fun cacheMatch(uid: String, match: VodMatch) { matches[uid] = match }
    actual fun compact() {}
}
