package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps the exact provider routes returned by CloudStream search endpoints.
 *
 * A normal Nuvio poster only has external metadata, while a CloudStream search card
 * already contains the provider-specific URL required by MainAPI.load. Remembering
 * that URL makes both entry paths resolve through the same CloudStream contract.
 */
internal object CloudStreamSearchRouteIndex {
    private data class Entry(
        val providerId: String,
        val query: String,
        val item: CloudStreamSearchItem,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())

    fun clear() {
        entries.value = emptyList()
    }

    fun clearProvider(providerId: String) {
        entries.update { current -> current.filterNot { it.providerId == providerId } }
    }

    fun remember(
        providerId: String,
        query: String,
        items: List<CloudStreamSearchItem>,
    ) {
        if (items.isEmpty()) return
        val additions = items.map { item ->
            Entry(providerId = providerId, query = query.canonicalRouteTitle(), item = item)
        }
        entries.update { current ->
            (additions + current)
                .distinctBy { entry -> entry.providerId to entry.item.data }
                .take(MAX_ENTRIES)
        }
    }

    fun find(
        providerId: String,
        titles: List<String>,
        type: String,
        year: Int?,
    ): List<CloudStreamSearchItem> {
        val requestedType = normalizeRouteType(type)
        val normalizedTitles = titles
            .map { title -> title.canonicalRouteTitle() }
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedTitles.isEmpty()) return emptyList()

        val candidates = entries.value.asSequence()
            .filter { entry -> entry.providerId == providerId }
            .filter { entry -> normalizeRouteType(entry.item.type.nuvioType) == requestedType }
            .filter { entry ->
                val candidateYear = entry.item.year
                    ?: entry.item.name.releaseYearFromRouteTitle()
                // A known requested year must never match a yearless cached route.
                year == null || candidateYear == year
            }
            .filter { entry ->
                entry.item.name.canonicalRouteTitle() in normalizedTitles
            }
            .map(Entry::item)
            .distinctBy(CloudStreamSearchItem::data)
            .toList()

        // Without a requested year, cached routes for multiple releases are ambiguous.
        // Force a fresh provider search instead of silently trying the first cached URL.
        if (year == null && candidates.size > 1) {
            val candidateYears = candidates.map { item ->
                item.year ?: item.name.releaseYearFromRouteTitle()
            }
            val knownYears = candidateYears.filterNotNull().distinct()
            if (knownYears.size > 1 || candidateYears.any { it == null }) return emptyList()
        }

        return candidates
            .sortedByDescending { item ->
                val candidateYear = item.year ?: item.name.releaseYearFromRouteTitle()
                year != null && candidateYear == year
            }
            .take(MAX_MATCHES)
    }

    private fun String.canonicalRouteTitle(): String {
        val normalized = lowercase()
            .map { char -> if (char.isLetterOrDigit()) char else ' ' }
            .joinToString("")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .toMutableList()
        if (normalized.lastOrNull()?.matches(Regex("\\d{4}")) == true) {
            normalized.removeAt(normalized.lastIndex)
        }
        if (normalized.firstOrNull() in setOf("the", "a", "an")) {
            normalized.removeAt(0)
        }
        return normalized.joinToString(" ")
    }

    private fun String.releaseYearFromRouteTitle(): Int? {
        val lastToken = trim().substringAfterLast(' ', "")
        return lastToken.toIntOrNull()?.takeIf { it in 1900..2099 }
    }

    private fun normalizeRouteType(type: String): String = when (type.trim().lowercase()) {
        "tv", "show" -> "series"
        "livetv", "channel" -> "live"
        else -> type.trim().lowercase().ifBlank { "other" }
    }

    private const val MAX_ENTRIES = 1_000
    private const val MAX_MATCHES = 5
}
