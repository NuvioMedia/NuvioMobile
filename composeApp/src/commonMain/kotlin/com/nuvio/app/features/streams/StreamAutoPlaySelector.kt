package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries

        val (addonEntries, pluginEntries) = remainingEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        preferredStreamTitle: String? = null,
        preferredProviderName: String? = null,
        preferredProviderAddonId: String? = null,
        preferredStreamSubtitle: String? = null,
        preferSameNameInSelection: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            preferredStreamTitle = preferredStreamTitle,
            preferredProviderName = preferredProviderName,
            preferredProviderAddonId = preferredProviderAddonId,
            preferredStreamSubtitle = preferredStreamSubtitle,
            preferSameNameInSelection = preferSameNameInSelection,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        preferredStreamTitle: String? = null,
        preferredProviderName: String? = null,
        preferredProviderAddonId: String? = null,
        preferredStreamSubtitle: String? = null,
        preferSameNameInSelection: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetStreamTitle = preferredStreamTitle?.trim().orEmpty()
        val sameNameCandidates = if (preferSameNameInSelection && targetStreamTitle.isNotEmpty()) {
            candidateStreams.filter { stream ->
                val providerMatches = when {
                    !preferredProviderAddonId.isNullOrBlank() && stream.addonId.equals(preferredProviderAddonId.trim(), ignoreCase = true) -> true
                    !preferredProviderName.isNullOrBlank() && stream.addonName.equals(preferredProviderName.trim(), ignoreCase = true) -> true
                    preferredProviderAddonId.isNullOrBlank() && preferredProviderName.isNullOrBlank() -> true
                    else -> false
                }
                if (!providerMatches) return@filter false

                stream.streamLabel.trim().equals(targetStreamTitle, ignoreCase = true) ||
                    stream.name?.trim()?.equals(targetStreamTitle, ignoreCase = true) == true
            }
        } else {
            emptyList()
        }
        val targetSubtitle = preferredStreamSubtitle?.trim().orEmpty()
        val orderedSameNameCandidates = if (sameNameCandidates.size > 1 && targetSubtitle.isNotEmpty()) {
            sameNameCandidates.sortedByDescending { stream ->
                stream.streamSubtitle?.trim()?.equals(targetSubtitle, ignoreCase = true) == true
            }
        } else {
            sameNameCandidates
        }
        val preferredSameNameReadyStream = orderedSameNameCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        val preferredBingeGroupReadyStream = bingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }

        val preferredReadyStream = preferredSameNameReadyStream ?: preferredBingeGroupReadyStream
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    (
                        orderedSameNameCandidates.any {
                            it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                        } ||
                        bingeGroupCandidates.any {
                            it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                        }
                    ),
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = preferredReadyStream
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            matchingStreams
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .forEach(::add)
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
