package com.nuvio.app.features.details

enum class UniverseSortOrder { AiredOldest, AiredNewest, MostPopular }
enum class UniversePosterStyle { Landscape, Posters }

internal fun sortUniverse(items: List<UniverseConnection>, order: UniverseSortOrder): List<UniverseConnection> {
    fun date(item: UniverseConnection): String? = item.item.rawReleaseDate
        ?: item.item.releaseInfo?.take(4)?.takeIf { it.toIntOrNull() != null }?.plus("-01-01")
    val oldest = compareBy<UniverseConnection> { date(it) == null }
        .thenBy { date(it) }.thenBy { it.item.name }.thenBy { it.item.id }
    val comparator = when (order) {
        UniverseSortOrder.AiredOldest -> oldest
        UniverseSortOrder.AiredNewest -> compareBy<UniverseConnection> { date(it) == null }
            .thenByDescending { date(it) }.thenBy { it.item.name }.thenBy { it.item.id }
        UniverseSortOrder.MostPopular -> compareByDescending<UniverseConnection> { it.item.voteCount ?: -1 }
            .then(oldest)
    }
    return items.sortedWith(comparator)
}
