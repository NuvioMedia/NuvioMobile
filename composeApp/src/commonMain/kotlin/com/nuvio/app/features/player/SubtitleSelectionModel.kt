package com.nuvio.app.features.player

internal const val SubtitleOffLanguageKey = "__off__"
internal const val SubtitleUnknownLanguageKey = "__unknown__"

internal data class SubtitleLanguageItem(
    val key: String,
    val count: Int,
)

internal enum class SubtitleOptionsRailEmptyContent {
    NONE,
    LOADING,
    FETCH,
}

internal sealed interface SubtitleSelectionOption {
    val id: String

    data class BuiltIn(
        val track: SubtitleTrack,
    ) : SubtitleSelectionOption {
        override val id: String = "internal:${track.index}:${track.id}"
    }

    data class Addon(
        val subtitle: AddonSubtitle,
    ) : SubtitleSelectionOption {
        override val id: String = "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.selectionKey}"
    }
}

internal val AddonSubtitle.selectionKey: String
    get() = url.takeIf { it.isNotBlank() } ?: listOfNotNull(addonName, id).joinToString(":")

internal fun AddonSubtitle.matchesSelection(selectedId: String?): Boolean {
    if (selectedId.isNullOrBlank()) return false
    return url == selectedId || id == selectedId || selectionKey == selectedId
}

internal fun List<AddonSubtitle>.findSelectedAddon(selectedId: String?): AddonSubtitle? {
    if (selectedId.isNullOrBlank()) return null
    firstOrNull { it.url == selectedId }?.let { return it }
    firstOrNull { it.selectionKey == selectedId }?.let { return it }
    return firstOrNull { it.id == selectedId }
}

internal fun subtitleTracksStructureKey(tracks: List<SubtitleTrack>): String =
    tracks.joinToString("\u0001") { track ->
        "${track.index}\u0000${track.id}\u0000${track.language}\u0000${track.label}\u0000${track.isForced}"
    }

internal fun addonSubtitlesStructureKey(subtitles: List<AddonSubtitle>): String =
    subtitles.joinToString("\u0001") { subtitle ->
        "${subtitle.id}\u0000${subtitle.selectionKey}\u0000${subtitle.language}\u0000${subtitle.addonName}\u0000${subtitle.display}"
    }

internal fun buildSubtitleLanguageItems(
    subtitleTracks: List<SubtitleTrack>,
    addonSubtitles: List<AddonSubtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    selectedLanguageKey: String,
): List<SubtitleLanguageItem> {
    val counts = linkedMapOf<String, Int>()
    subtitleTracks.forEach { track ->
        val key = track.subtitleLanguageKey()
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = subtitleLanguageKey(subtitle.language)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = listOfNotNull(
        preferredLanguage.toPreferredSubtitleKey(),
        secondaryPreferredLanguage.toPreferredSubtitleKey(),
    ).distinct()
    val preferredKeys = preferredOrder.toSet()
    val visibleEntries = counts.entries.filter { entry ->
        !showOnlyPreferredLanguages || entry.key in preferredKeys || entry.key == selectedLanguageKey
    }
    val sortedEntries = visibleEntries.sortedWith(
        compareBy<Map.Entry<String, Int>>(
            { entry -> preferredOrder.indexOf(entry.key).takeIf { it >= 0 } ?: Int.MAX_VALUE },
            { entry -> if (entry.key == SubtitleUnknownLanguageKey) "\uFFFF" else entry.key },
        ),
    )

    return listOf(SubtitleLanguageItem(SubtitleOffLanguageKey, 0)) +
        sortedEntries.map { SubtitleLanguageItem(it.key, it.value) }
}

internal fun buildSubtitleSelectionOptions(
    languageKey: String,
    subtitleTracks: List<SubtitleTrack>,
    addonSubtitles: List<AddonSubtitle>,
): List<SubtitleSelectionOption> {
    if (languageKey == SubtitleOffLanguageKey) return emptyList()

    val builtInOptions = subtitleTracks
        .filter { it.subtitleLanguageKey() == languageKey }
        .map { SubtitleSelectionOption.BuiltIn(it) }
    val seenAddonIds = mutableSetOf<String>()
    val addonOptions = addonSubtitles
        .filter { subtitleLanguageKey(it.language) == languageKey }
        .map(SubtitleSelectionOption::Addon)
        .filter { seenAddonIds.add(it.id) }

    return builtInOptions + addonOptions
}

internal fun subtitleOptionsRailEmptyContent(
    selectedLanguageKey: String,
    hasAvailableLanguages: Boolean,
    isLoadingAddonSubtitles: Boolean,
): SubtitleOptionsRailEmptyContent {
    if (selectedLanguageKey == SubtitleOffLanguageKey && hasAvailableLanguages) {
        return SubtitleOptionsRailEmptyContent.NONE
    }
    return if (isLoadingAddonSubtitles) {
        SubtitleOptionsRailEmptyContent.LOADING
    } else {
        SubtitleOptionsRailEmptyContent.FETCH
    }
}

internal fun selectedSubtitleLanguageKey(
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    selectedAddonSubtitle: AddonSubtitle?,
): String {
    selectedAddonSubtitle?.let { return subtitleLanguageKey(it.language) }
    return subtitleTracks
        .firstOrNull { it.index == selectedSubtitleIndex }
        ?.subtitleLanguageKey()
        ?: subtitleTracks.firstOrNull { it.isSelected }?.subtitleLanguageKey()
        ?: SubtitleOffLanguageKey
}

internal fun selectedSubtitleOptionId(
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    selectedAddonSubtitle: AddonSubtitle?,
): String? {
    selectedAddonSubtitle?.let { subtitle -> return SubtitleSelectionOption.Addon(subtitle).id }
    return subtitleTracks
        .firstOrNull { it.index == selectedSubtitleIndex }
        ?.let { SubtitleSelectionOption.BuiltIn(it) }
        ?.id
        ?: subtitleTracks
            .firstOrNull { it.isSelected }
            ?.let { SubtitleSelectionOption.BuiltIn(it) }
            ?.id
}

internal fun subtitleLanguageKey(language: String?): String {
    val normalized = normalizeLanguageCode(language) ?: return SubtitleUnknownLanguageKey
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized.substringBefore('-').ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun SubtitleTrack.subtitleLanguageKey(): String {
    val normalized = subtitleLanguageKey(language)
    val haystack = listOf(label, language, id).filterNotNull().joinToString(" ").lowercase()
    return when (normalized) {
        "pt" -> when {
            BrazilianPortugueseHints.any(haystack::contains) &&
                EuropeanPortugueseHints.none(haystack::contains) -> "pt-br"
            else -> normalized
        }
        "es" -> when {
            LatinAmericanSpanishHints.any(haystack::contains) &&
                CastilianSpanishHints.none(haystack::contains) -> "es-419"
            else -> normalized
        }
        else -> normalized
    }
}

private fun String?.toPreferredSubtitleKey(): String? {
    val normalized = normalizeLanguageCode(this) ?: return null
    if (normalized == SubtitleLanguageOption.NONE || normalized == SubtitleLanguageOption.FORCED) return null
    return subtitleLanguageKey(normalized).takeUnless { it == SubtitleUnknownLanguageKey }
}

private val BrazilianPortugueseHints = listOf(
    "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil", "brasileiro", "(br)",
)

private val EuropeanPortugueseHints = listOf(
    "pt-pt", "pt_pt", "portugal", "european", "europeu", "iberian", "(eu)",
)

private val LatinAmericanSpanishHints = listOf(
    "es-419", "es_419", "es-la", "es-lat", "latino", "latinoamerica", "latam", "latin america",
)

private val CastilianSpanishHints = listOf(
    "es-es", "es_es", "castilian", "castellano", "spain", "españa", "espana", "iberian",
)
