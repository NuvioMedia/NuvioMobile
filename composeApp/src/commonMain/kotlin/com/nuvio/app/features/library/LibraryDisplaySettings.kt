package com.nuvio.app.features.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibraryLayoutMode {
    HORIZONTAL,
    VERTICAL,
}

enum class LibrarySortOption {
    DEFAULT,
    ADDED_DESC,
    ADDED_ASC,
    RELEASED_DESC,
    RELEASED_ASC,
    TITLE_ASC,
    TITLE_DESC,
}

enum class LibraryWatchedFilter {
    ALL,
    WATCHED,
    UNWATCHED,
}

data class LibraryDisplaySettingsUiState(
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.HORIZONTAL,
    val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val selectedSectionKey: String? = null,
    val selectedType: String? = null,
)

object LibraryDisplaySettingsRepository {
    private val _uiState = MutableStateFlow(LibraryDisplaySettingsUiState())
    val uiState: StateFlow<LibraryDisplaySettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = LibraryDisplaySettingsUiState()
    }

    fun setLayoutMode(layoutMode: LibraryLayoutMode) {
        ensureLoaded()
        if (_uiState.value.layoutMode == layoutMode) return
        _uiState.value = _uiState.value.copy(layoutMode = layoutMode)
        persist()
    }

    fun setSortOption(sortOption: LibrarySortOption) {
        ensureLoaded()
        if (_uiState.value.sortOption == sortOption) return
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
        persist()
    }

    fun setSelectedSection(sectionKey: String) {
        ensureLoaded()
        val current = _uiState.value
        if (current.selectedSectionKey == sectionKey && current.selectedType == null) return
        _uiState.value = current.copy(selectedSectionKey = sectionKey, selectedType = null)
        persist()
    }

    fun setSelectedType(type: String?) {
        ensureLoaded()
        if (_uiState.value.selectedType == type) return
        _uiState.value = _uiState.value.copy(selectedType = type)
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = decodeLibraryDisplaySettings(LibraryDisplaySettingsStorage.loadPayload())
    }

    private fun persist() {
        LibraryDisplaySettingsStorage.savePayload(encodeLibraryDisplaySettings(_uiState.value))
    }
}

internal data class LibraryVerticalEntry(
    val item: LibraryItem,
    val section: LibrarySection,
)

internal data class LibraryFilterOption(
    val key: String,
    val count: Int,
)

internal data class LibraryVerticalProjection(
    val availableSections: List<LibrarySection>,
    val selectedSectionKey: String?,
    val availableTypes: List<String>,
    val selectedType: String?,
    val entries: List<LibraryVerticalEntry>,
    val typeCounts: Map<String, Int> = emptyMap(),
    val allTypesCount: Int = 0,
    val availableGenres: List<LibraryFilterOption> = emptyList(),
    val selectedGenre: String? = null,
    val availableYears: List<LibraryFilterOption> = emptyList(),
    val selectedYear: String? = null,
)

private val ReleaseSortOptions = setOf(LibrarySortOption.RELEASED_DESC, LibrarySortOption.RELEASED_ASC)

internal fun availableLibrarySortOptions(sourceMode: LibrarySourceMode): List<LibrarySortOption> =
    when {
        sourceMode == LibrarySourceMode.MDBLIST -> LibrarySortOption.entries
        sourceMode.isRemoteTrackingSource -> LibrarySortOption.entries.filterNot { it in ReleaseSortOptions }
        else -> LibrarySortOption.entries.filterNot { it == LibrarySortOption.DEFAULT || it in ReleaseSortOptions }
    }

// The sort option is shared by all sources, so one a source does not offer falls back to its default.
internal fun effectiveLibrarySortOption(
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): LibrarySortOption =
    when {
        selected in availableLibrarySortOptions(sourceMode) -> selected
        sourceMode.isRemoteTrackingSource -> LibrarySortOption.DEFAULT
        else -> LibrarySortOption.ADDED_DESC
    }

internal fun sortLibraryItems(
    items: List<LibraryItem>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    listKey: String? = null,
    providerOrder: Map<String, Int>? = null,
): List<LibraryItem> =
    when (effectiveLibrarySortOption(selected, sourceMode)) {
        LibrarySortOption.DEFAULT -> items.sortedWith(
            compareBy<LibraryItem> { it.listRanks[listKey] ?: it.traktRank ?: Int.MAX_VALUE }
                .thenByDescending { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_DESC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator) ?: compareByDescending<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_ASC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator) ?: compareBy<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RELEASED_DESC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator) ?: compareByDescending<LibraryItem> { libraryReleaseYear(it) }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RELEASED_ASC -> items.sortedWith(
            providerOrder?.let(::libraryProviderOrderComparator)
                ?: compareBy<LibraryItem, Int?>(nullsLast()) { libraryReleaseYear(it) }
                    .thenBy { libraryTitleTieBreakKey(it) }
                    .thenBy { it.id },
        )
        LibrarySortOption.TITLE_ASC -> items.sortedWith(
            compareBy<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
    }

internal fun sortLibrarySections(
    sections: List<LibrarySection>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    providerOrders: Map<String, Map<String, Int>> = emptyMap(),
): List<LibrarySection> =
    sections.map { section ->
        section.copy(items = sortLibraryItems(section.items, selected, sourceMode, section.type, providerOrders[section.type]))
    }

internal fun buildLibraryVerticalProjection(
    sections: List<LibrarySection>,
    sourceMode: LibrarySourceMode,
    selectedSectionKey: String?,
    selectedType: String?,
    sortOption: LibrarySortOption,
    providerOrders: Map<String, Map<String, Int>> = emptyMap(),
    selectedGenre: String? = null,
    selectedYear: String? = null,
    watchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    isWatched: (LibraryItem) -> Boolean = { false },
): LibraryVerticalProjection {
    val availableSections = if (sourceMode.isRemoteTrackingSource) sections else emptyList()
    val selectedSection = if (sourceMode.isRemoteTrackingSource) {
        sections.firstOrNull { it.type == selectedSectionKey } ?: sections.firstOrNull()
    } else {
        null
    }
    val baseEntries = if (selectedSection != null) {
        selectedSection.items.map { item -> LibraryVerticalEntry(item, selectedSection) }
    } else {
        sections.flatMap { section ->
            section.items.map { item -> LibraryVerticalEntry(item, section) }
        }
    }
    val deduplicatedEntries = LinkedHashMap<String, LibraryVerticalEntry>()
    baseEntries.forEach { entry ->
        val key = libraryDisplayItemKey(entry.item)
        if (key !in deduplicatedEntries) {
            deduplicatedEntries[key] = entry
        }
    }
    val availableTypes = deduplicatedEntries.values
        .map { entry -> (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedWith(compareBy<String> { type -> LibraryTypeOrder.indexOf(type).takeIf { it >= 0 } ?: LibraryTypeOrder.size }.thenBy { it })
    val effectiveType = selectedType
        ?.normalizedLibraryType()
        ?.takeIf { it in availableTypes }
    val typeFilteredEntries = deduplicatedEntries.values.filter { entry ->
        effectiveType == null || (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() == effectiveType
    }
    val effectiveGenre = selectedGenre?.takeIf { genre ->
        typeFilteredEntries.any { entry -> entry.item.hasLibraryGenre(genre) }
    }
    val effectiveYear = selectedYear?.takeIf { year ->
        typeFilteredEntries.any { entry -> libraryReleaseYear(entry.item)?.toString() == year }
    }
    val genreOptions = typeFilteredEntries
        .filter { entry -> effectiveYear == null || libraryReleaseYear(entry.item)?.toString() == effectiveYear }
        .flatMap { entry -> entry.item.genres.map(String::trim).filter(String::isNotBlank) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedBy { it.key.lowercase() }
        .map { (genre, count) -> LibraryFilterOption(key = genre, count = count) }
    val yearOptions = typeFilteredEntries
        .filter { entry -> effectiveGenre == null || entry.item.hasLibraryGenre(effectiveGenre) }
        .mapNotNull { entry -> libraryReleaseYear(entry.item)?.toString() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.key }
        .map { (year, count) -> LibraryFilterOption(key = year, count = count) }
    val entriesForTypeCounts = deduplicatedEntries.values.filter { entry ->
        (effectiveGenre == null || entry.item.hasLibraryGenre(effectiveGenre)) &&
            (effectiveYear == null || libraryReleaseYear(entry.item)?.toString() == effectiveYear)
    }
    val typeCounts = entriesForTypeCounts
        .groupingBy { entry -> (entry.item.mediaCategory ?: entry.item.type).normalizedLibraryType() }
        .eachCount()
    val filteredEntries = typeFilteredEntries.filter { entry ->
        (effectiveGenre == null || entry.item.hasLibraryGenre(effectiveGenre)) &&
            (effectiveYear == null || libraryReleaseYear(entry.item)?.toString() == effectiveYear) &&
            when (watchedFilter) {
                LibraryWatchedFilter.ALL -> true
                LibraryWatchedFilter.WATCHED -> isWatched(entry.item)
                LibraryWatchedFilter.UNWATCHED -> !isWatched(entry.item)
            }
    }
    val entryByKey = filteredEntries.associateBy { entry -> libraryDisplayItemKey(entry.item) }
    val sortedEntries = sortLibraryItems(
        items = filteredEntries.map { entry -> entry.item },
        selected = sortOption,
        sourceMode = sourceMode,
        listKey = selectedSection?.type,
        providerOrder = providerOrders[selectedSection?.type],
    ).mapNotNull { item -> entryByKey[libraryDisplayItemKey(item)] }

    return LibraryVerticalProjection(
        availableSections = availableSections,
        selectedSectionKey = selectedSection?.type,
        availableTypes = availableTypes,
        selectedType = effectiveType,
        entries = sortedEntries,
        typeCounts = typeCounts,
        allTypesCount = entriesForTypeCounts.size,
        availableGenres = genreOptions,
        selectedGenre = effectiveGenre,
        availableYears = yearOptions,
        selectedYear = effectiveYear,
    )
}

private val LibraryTypeOrder = listOf("movie", "series", "tv", "show", "anime")

private fun LibraryItem.hasLibraryGenre(genre: String): Boolean =
    genres.any { it.trim().equals(genre, ignoreCase = true) }

internal fun encodeLibraryDisplaySettings(state: LibraryDisplaySettingsUiState): String =
    LibraryDisplaySettingsJson.encodeToString(
        StoredLibraryDisplaySettings(
            layoutMode = state.layoutMode.name,
            sortOption = state.sortOption.name,
            selectedSectionKey = state.selectedSectionKey,
            selectedType = state.selectedType,
        ),
    )

internal fun decodeLibraryDisplaySettings(payload: String?): LibraryDisplaySettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            runCatching {
                LibraryDisplaySettingsJson.decodeFromString<StoredLibraryDisplaySettings>(value)
            }.getOrNull()
        }
    return LibraryDisplaySettingsUiState(
        layoutMode = stored?.layoutMode
            ?.let { value -> LibraryLayoutMode.entries.firstOrNull { it.name == value } }
            ?: LibraryLayoutMode.HORIZONTAL,
        sortOption = stored?.sortOption
            ?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
            ?: LibrarySortOption.DEFAULT,
        selectedSectionKey = stored?.selectedSectionKey,
        selectedType = stored?.selectedType,
    )
}

private val LibraryDisplaySettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val LeadingLibraryTitleArticle = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun libraryTitleSortKey(item: LibraryItem): String =
    libraryTitleTieBreakKey(item)
        .trim()
        .replace(LeadingLibraryTitleArticle, "")

private val LibraryReleaseYear = Regex("""\d{4}""")

private fun libraryReleaseYear(item: LibraryItem): Int? =
    item.releaseInfo?.let { LibraryReleaseYear.find(it)?.value?.toIntOrNull() }

private fun libraryTitleTieBreakKey(item: LibraryItem): String =
    item.name
        .ifBlank { item.id }
        .lowercase()

internal fun libraryDisplayItemKey(item: LibraryItem): String =
    "${item.type.normalizedLibraryType()}:${item.id.trim()}"

private fun String.normalizedLibraryType(): String = trim().lowercase()

internal val LibrarySourceMode.isRemoteTrackingSource: Boolean
    get() = this != LibrarySourceMode.LOCAL

@Serializable
private data class StoredLibraryDisplaySettings(
    @SerialName("layout_mode") val layoutMode: String = LibraryLayoutMode.HORIZONTAL.name,
    @SerialName("sort_option") val sortOption: String = LibrarySortOption.DEFAULT.name,
    @SerialName("selected_section_key") val selectedSectionKey: String? = null,
    @SerialName("selected_type") val selectedType: String? = null,
)

private fun libraryProviderOrderComparator(ranks: Map<String, Int>): Comparator<LibraryItem> =
    compareBy<LibraryItem> { ranks[libraryDisplayItemKey(it)] ?: Int.MAX_VALUE }.thenBy { it.id }
