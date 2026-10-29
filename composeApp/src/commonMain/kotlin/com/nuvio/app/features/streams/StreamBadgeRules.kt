package com.nuvio.app.features.streams

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val STREAM_BADGE_IMPORT_LIMIT = 3

@Serializable
data class StreamBadgeRules(
    val imports: List<StreamBadgeImport> = emptyList(),
) {
    val hasImport: Boolean
        get() = imports.isNotEmpty()

    val activeImport: StreamBadgeImport?
        get() = imports.firstOrNull { it.isActive } ?: imports.firstOrNull()

    val enabledFilterCount: Int
        get() = activeImport?.enabledFilterCount ?: 0

    fun normalized(): StreamBadgeRules {
        val normalizedImports = mutableListOf<StreamBadgeImport>()
        imports.forEach { import ->
            val normalizedUrl = import.sourceUrl.trim()
            if (normalizedUrl.isBlank() || import.filters.isEmpty()) return@forEach
            val normalizedImport = import.copy(sourceUrl = normalizedUrl)
            val existingIndex = normalizedImports.indexOfFirst { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }
            if (existingIndex >= 0) {
                normalizedImports[existingIndex] = normalizedImport
            } else if (normalizedImports.size < STREAM_BADGE_IMPORT_LIMIT) {
                normalizedImports += normalizedImport
            }
        }
        if (normalizedImports.isEmpty()) return copy(imports = emptyList())
        val activeIndex = normalizedImports.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        return copy(
            imports = normalizedImports.mapIndexed { index, import ->
                import.copy(isActive = index == activeIndex)
            },
        )
    }

    fun upsert(import: StreamBadgeImport, activate: Boolean = true): StreamBadgeRules {
        val normalizedUrl = import.sourceUrl.trim()
        if (normalizedUrl.isBlank()) return normalized()
        val normalizedImport = import.copy(sourceUrl = normalizedUrl, isActive = activate)
        val replaced = imports.map { existing ->
            if (existing.sourceUrl.equals(normalizedUrl, ignoreCase = true)) normalizedImport else existing
        }
        val nextImports = if (imports.any { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            replaced
        } else {
            imports + normalizedImport
        }
        val activeImports = if (activate) {
            nextImports.map { existing ->
                existing.copy(isActive = existing.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            }
        } else {
            nextImports
        }
        return copy(imports = activeImports).normalized()
    }

    fun setActiveSource(sourceUrl: String): StreamBadgeRules {
        val normalizedUrl = sourceUrl.trim()
        if (normalizedUrl.isBlank() || imports.none { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            return normalized()
        }
        return copy(
            imports = imports.map { import ->
                import.copy(isActive = import.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            },
        ).normalized()
    }

    fun forPlayer(sourceUrl: String): StreamBadgeRules =
        if (sourceUrl.isBlank()) normalized() else setActiveSource(sourceUrl)

    fun removeSource(sourceUrl: String): StreamBadgeRules =
        copy(imports = imports.filterNot { it.sourceUrl.equals(sourceUrl.trim(), ignoreCase = true) }).normalized()

    override fun toString(): String =
        "StreamBadgeRules(imports=${imports.size}, activeFilters=$enabledFilterCount, groups=${imports.sumOf { it.groups.size }})"
}

@Serializable
data class StreamBadgeImport(
    val sourceUrl: String = "",
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList(),
    val isActive: Boolean = true,
) {
    val enabledFilterCount: Int
        get() = filters.count { it.isEnabled }

    override fun toString(): String =
        "StreamBadgeImport(sourceUrl=$sourceUrl, filters=${filters.size}, groups=${groups.size}, isActive=$isActive)"
}

@Serializable
data class StreamBadgeFilter(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val isEnabled: Boolean = true,
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = "",
)

@Serializable
data class StreamBadgeGroup(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val isExpanded: Boolean = true,
)

sealed interface StreamBadgeImportResult {
    data class Success(val rules: StreamBadgeRules) : StreamBadgeImportResult
    data class Error(val message: String) : StreamBadgeImportResult
}

internal object StreamBadgeRulesParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(sourceUrl: String, payload: String): StreamBadgeImport {
        val decoded = try {
            json.decodeFromString<StreamBadgePayload>(payload)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        }

        val filters = decoded.filters.mapNotNull { filter ->
            val name = filter.name.orEmpty().trim()
            val pattern = filter.pattern.orEmpty().trim()
            if (name.isBlank() || pattern.isBlank()) {
                return@mapNotNull null
            }

            StreamBadgeFilter(
                id = filter.id.orEmpty(),
                groupId = filter.groupId.orEmpty(),
                name = name,
                pattern = pattern,
                imageURL = filter.imageURL.orEmpty(),
                isEnabled = filter.isEnabled ?: true,
                tagColor = filter.tagColor.orEmpty(),
                tagStyle = filter.tagStyle.orEmpty(),
                textColor = filter.textColor.orEmpty(),
                borderColor = filter.borderColor.orEmpty(),
            )
        }

        if (filters.isEmpty()) {
            throw IllegalArgumentException("Badge import did not contain any usable filters.")
        }

        val groups = decoded.groups.map { group ->
            StreamBadgeGroup(
                id = group.id.orEmpty(),
                name = group.name.orEmpty(),
                color = group.color.orEmpty(),
                isExpanded = group.isExpanded ?: true,
            )
        }

        return StreamBadgeImport(
            sourceUrl = sourceUrl.trim(),
            filters = filters,
            groups = groups,
        )
    }
}

object StreamBadgeMatcher {
    fun compile(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        if (!rules.hasImport) return emptyList()
        return rules.normalized().imports.filter { it.isActive }.flatMap { import ->
            import.filters.mapNotNull { filter ->
                if (!filter.isEnabled || filter.name.isBlank() || filter.pattern.isBlank()) {
                    return@mapNotNull null
                }
                val regex = runCatching { Regex(filter.pattern) }.getOrNull() ?: return@mapNotNull null
                CompiledStreamBadgeFilter(
                    name = filter.name,
                    badge = StreamBadge(
                        name = filter.name,
                        imageURL = filter.imageURL,
                        tagColor = filter.tagColor,
                        tagStyle = filter.tagStyle,
                        textColor = filter.textColor,
                        borderColor = filter.borderColor,
                    ),
                    regex = regex,
                )
            }
        }
    }

    fun matchedNames(stream: StreamItem, rules: StreamBadgeRules): List<String> =
        matchedNames(stream, compile(rules))

    fun matchedNames(stream: StreamItem, filters: List<CompiledStreamBadgeFilter>): List<String> {
        return matchedBadges(stream, filters).map { it.name }
    }

    fun matchedBadges(stream: StreamItem, filters: List<CompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        return matchedBadges(badgeMatchCandidates(stream), filters)
    }

    fun matchedBadges(candidates: List<String>, filters: List<CompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty() || candidates.isEmpty()) return emptyList()

        val matched = linkedMapOf<String, StreamBadge>()
        filters.forEach { filter ->
            if (candidates.any { candidate -> filter.regex.containsMatchIn(candidate) }) {
                val key = filter.badge.dedupeKey()
                if (key !in matched) matched[key] = filter.badge
            }
        }
        return matched.values.toList()
    }

    fun badgeMatchCandidates(stream: StreamItem): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val candidates = listOfNotNull(
            raw?.filename,
            resolve?.filename,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            raw?.torrentName,
            resolve?.torrentName,
            stream.name,
            stream.title,
            stream.description,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.edition,
            parsed?.audio?.joinToString(" "),
            parsed?.channels?.joinToString(" "),
            parsed?.hdr?.joinToString(" "),
            parsed?.group,
            stream.sourceName,
            stream.addonName,
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (candidates.size <= 1) {
            candidates
        } else {
            candidates + candidates.joinToString(" ")
        }
    }
}

data class CompiledStreamBadgeFilter(
    val name: String,
    val badge: StreamBadge,
    val regex: Regex,
)

private fun StreamBadge.dedupeKey(): String =
    (imageURL.takeIf { it.isNotBlank() } ?: name).lowercase()

@Serializable
private data class StreamBadgePayload(
    val filters: List<StreamBadgeFilterPayload> = emptyList(),
    val groups: List<StreamBadgeGroupPayload> = emptyList(),
)

@Serializable
private data class StreamBadgeFilterPayload(
    val id: String? = null,
    val groupId: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val imageURL: String? = null,
    val isEnabled: Boolean? = null,
    val tagColor: String? = null,
    val tagStyle: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    val type: String? = null,
)

@Serializable
private data class StreamBadgeGroupPayload(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null,
    val isExpanded: Boolean? = null,
)
