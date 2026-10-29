package com.nuvio.app.features.streams

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

object StreamBadgePresentation {
    private val filterCacheLock = SynchronizedObject()
    private var cachedRules: StreamBadgeRules? = null
    private var cachedFilters: List<CompiledStreamBadgeFilter> = emptyList()

    fun apply(groups: List<AddonStreamGroup>, rules: StreamBadgeRules): List<AddonStreamGroup> {
        val filters = compiledFilters(rules)
        if (filters.isEmpty()) return groups
        return groups.map { group ->
            group.copy(
                streams = group.streams.map { stream ->
                    val matchedBadges = StreamBadgeMatcher.matchedBadges(stream, filters)
                    if (matchedBadges.isEmpty()) {
                        stream
                    } else {
                        stream.copy(badges = mergeBadges(stream.badges, matchedBadges))
                    }
                },
            )
        }
    }

    // Each addon group is presented on its own with the same rules, so their regexes are
    // compiled once and reused until the rules change.
    private fun compiledFilters(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> =
        synchronized(filterCacheLock) {
            if (cachedRules != rules) {
                cachedFilters = StreamBadgeMatcher.compile(rules)
                cachedRules = rules
            }
            cachedFilters
        }

    private fun mergeBadges(existing: List<StreamBadge>, matched: List<StreamBadge>): List<StreamBadge> {
        if (existing.isEmpty()) return matched
        val seenKeys = existing.mapTo(mutableSetOf()) { it.dedupeKey() }
        return existing + matched.filter { badge -> seenKeys.add(badge.dedupeKey()) }
    }

    private fun StreamBadge.dedupeKey(): String =
        (imageURL.takeIf { it.isNotBlank() } ?: name).lowercase()
}
