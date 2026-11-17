package com.nuvio.app.features.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.poster.withCustomPosterUrl
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_all_types
import nuvio.composeapp.generated.resources.library_filter_genre
import nuvio.composeapp.generated.resources.library_filter_list
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_filter_type
import nuvio.composeapp.generated.resources.library_filter_watched
import nuvio.composeapp.generated.resources.library_filter_year
import nuvio.composeapp.generated.resources.library_type_all
import nuvio.composeapp.generated.resources.library_watched_filter_all
import nuvio.composeapp.generated.resources.library_watched_filter_unwatched
import nuvio.composeapp.generated.resources.library_watched_filter_watched
import nuvio.composeapp.generated.resources.library_sort_added_asc
import nuvio.composeapp.generated.resources.library_sort_added_desc
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_sort_provider_order
import nuvio.composeapp.generated.resources.library_sort_released_asc
import nuvio.composeapp.generated.resources.library_sort_released_desc
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibrarySavedControls(
    layoutMode: LibraryLayoutMode,
    sourceMode: LibrarySourceMode,
    sortOption: LibrarySortOption,
    verticalProjection: LibraryVerticalProjection,
    watchedFilter: LibraryWatchedFilter,
    onSectionSelected: (String) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onYearSelected: (String?) -> Unit,
    onWatchedFilterSelected: (LibraryWatchedFilter) -> Unit,
    onSortSelected: (LibrarySortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortOptions = availableLibrarySortOptions(sourceMode)
    val allTypesLabel = stringResource(Res.string.library_filter_all_types)
    val allLabel = stringResource(Res.string.library_type_all)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (layoutMode == LibraryLayoutMode.VERTICAL && sourceMode.isRemoteTrackingSource) {
                val selectedSection = verticalProjection.availableSections
                    .firstOrNull { section -> section.type == verticalProjection.selectedSectionKey }
                NuvioDropdownChip(
                    title = stringResource(Res.string.library_filter_list),
                    label = selectedSection?.let { section -> localizedLibrarySectionTitle(section.type, section.displayTitle) }.orEmpty(),
                    selectedKey = verticalProjection.selectedSectionKey,
                    options = verticalProjection.availableSections.map { section ->
                        NuvioDropdownOption(key = section.type, label = localizedLibrarySectionTitle(section.type, section.displayTitle))
                    },
                    enabled = verticalProjection.availableSections.size > 1,
                    onSelected = { option -> onSectionSelected(option.key) },
                )
            }

            if (layoutMode == LibraryLayoutMode.VERTICAL) {
                val typeOptions = buildList {
                    add(NuvioDropdownOption(key = "", label = "$allTypesLabel (${verticalProjection.allTypesCount})"))
                    addAll(
                        verticalProjection.availableTypes.map { type ->
                            NuvioDropdownOption(
                                key = type,
                                label = "${localizedMediaTypeLabel(type)} (${verticalProjection.typeCounts[type] ?: 0})",
                            )
                        },
                    )
                }
                NuvioDropdownChip(
                    title = stringResource(Res.string.library_filter_type),
                    label = verticalProjection.selectedType
                        ?.let(::localizedMediaTypeLabel)
                        ?: allTypesLabel,
                    selectedKey = verticalProjection.selectedType.orEmpty(),
                    options = typeOptions,
                    enabled = typeOptions.size > 1,
                    onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
                )
            }

            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_sort),
                label = librarySortOptionLabel(sortOption),
                selectedKey = sortOption.name,
                options = sortOptions.map { option ->
                    NuvioDropdownOption(key = option.name, label = librarySortOptionLabel(option))
                },
                enabled = sortOptions.size > 1,
                onSelected = { option ->
                    LibrarySortOption.entries
                        .firstOrNull { it.name == option.key }
                        ?.let(onSortSelected)
                },
            )
        }

        if (layoutMode == LibraryLayoutMode.VERTICAL) {
            LibraryFilterRow {
                if (verticalProjection.availableGenres.isNotEmpty()) {
                    val genreTitle = stringResource(Res.string.library_filter_genre)
                    Box(propagateMinConstraints = true) {
                        NuvioDropdownChip(
                            title = genreTitle,
                            label = "$genreTitle: ${verticalProjection.selectedGenre ?: allLabel}",
                            selectedKey = verticalProjection.selectedGenre.orEmpty(),
                            options = libraryFacetOptions(allLabel, verticalProjection.availableGenres),
                            onSelected = { option -> onGenreSelected(option.key.ifBlank { null }) },
                            fillWidth = true,
                        )
                    }
                }
                if (verticalProjection.availableYears.isNotEmpty()) {
                    val yearTitle = stringResource(Res.string.library_filter_year)
                    Box(propagateMinConstraints = true) {
                        NuvioDropdownChip(
                            title = yearTitle,
                            label = "$yearTitle: ${verticalProjection.selectedYear ?: allLabel}",
                            selectedKey = verticalProjection.selectedYear.orEmpty(),
                            options = libraryFacetOptions(allLabel, verticalProjection.availableYears),
                            onSelected = { option -> onYearSelected(option.key.ifBlank { null }) },
                            fillWidth = true,
                        )
                    }
                }
                val watchedTitle = stringResource(Res.string.library_filter_watched)
                Box(propagateMinConstraints = true) {
                    NuvioDropdownChip(
                        title = watchedTitle,
                        label = "$watchedTitle: ${libraryWatchedFilterLabel(watchedFilter)}",
                        selectedKey = watchedFilter.name,
                        options = LibraryWatchedFilter.entries.map { filter ->
                            NuvioDropdownOption(key = filter.name, label = libraryWatchedFilterLabel(filter))
                        },
                        onSelected = { option ->
                            LibraryWatchedFilter.entries
                                .firstOrNull { it.name == option.key }
                                ?.let(onWatchedFilterSelected)
                        },
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.libraryVerticalContent(
    projection: LibraryVerticalProjection,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    posterPattern: String,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
) {
    items(
        items = projection.entries.chunked(columns),
        key = { rowEntries ->
            val firstEntry = rowEntries.first()
            "library-vertical:${firstEntry.item.type}:${firstEntry.item.id}"
        },
    ) { rowEntries ->
        val resolvedItems = remember(rowEntries, posterPattern) {
            rowEntries.map { entry ->
                entry.item.toMetaPreview().let {
                    if (posterPattern.isNotBlank()) it.withCustomPosterUrl(posterPattern) else it
                }
            }
        }
        PosterGridRow(
            items = resolvedItems,
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick?.let { callback ->
                { preview -> rowEntries.findEntry(preview)?.item?.let(callback) }
            },
            onPosterLongClick = onPosterLongClick?.let { callback ->
                { preview ->
                    rowEntries.findEntry(preview)?.let { entry -> callback(entry.item, entry.section) }
                }
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalSkeletonItems(columns: Int) {
    items(
        count = 2,
        key = { index -> "library-vertical-skeleton:$index" },
    ) {
        PosterGridSkeletonRow(
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
        )
    }
}

internal fun LazyItemScope.libraryContentTransitionModifier(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(durationMillis = 160),
        placementSpec = tween(durationMillis = 180),
        fadeOutSpec = tween(durationMillis = 90),
    )

@Composable
private fun librarySortOptionLabel(option: LibrarySortOption): String =
    when (option) {
        LibrarySortOption.DEFAULT -> stringResource(Res.string.library_sort_provider_order)
        LibrarySortOption.ADDED_DESC -> stringResource(Res.string.library_sort_added_desc)
        LibrarySortOption.ADDED_ASC -> stringResource(Res.string.library_sort_added_asc)
        LibrarySortOption.RELEASED_DESC -> stringResource(Res.string.library_sort_released_desc)
        LibrarySortOption.RELEASED_ASC -> stringResource(Res.string.library_sort_released_asc)
        LibrarySortOption.TITLE_ASC -> stringResource(Res.string.library_sort_title_asc)
        LibrarySortOption.TITLE_DESC -> stringResource(Res.string.library_sort_title_desc)
    }

private fun libraryFacetOptions(
    allLabel: String,
    options: List<LibraryFilterOption>,
): List<NuvioDropdownOption> =
    listOf(NuvioDropdownOption(key = "", label = allLabel)) +
        options.map { option -> NuvioDropdownOption(key = option.key, label = "${option.key} (${option.count})") }

@Composable
private fun LibraryFilterRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidth = constraints.maxWidth
        Layout(
            content = content,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) { measurables, _ ->
            val count = measurables.size
            val spacing = 8.dp.roundToPx()
            val totalSpacing = spacing * (count - 1).coerceAtLeast(0)
            val naturalWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
            val naturalTotal = naturalWidths.sum() + totalSpacing
            val equalShare = if (count > 0) (viewportWidth - totalSpacing) / count else 0
            val widths = when {
                count == 0 -> emptyList()
                naturalWidths.all { it <= equalShare } -> List(count) { index ->
                    if (index == count - 1) viewportWidth - totalSpacing - equalShare * (count - 1) else equalShare
                }
                naturalTotal <= viewportWidth -> {
                    val extra = (viewportWidth - naturalTotal) / count
                    naturalWidths.mapIndexed { index, width ->
                        width + extra + if (index == count - 1) (viewportWidth - naturalTotal) - extra * count else 0
                    }
                }
                else -> naturalWidths
            }
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints.fixedWidth(widths[index]))
            }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            val width = placeables.sumOf { it.width } + totalSpacing
            layout(width, height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + spacing
                }
            }
        }
    }
}

@Composable
private fun libraryWatchedFilterLabel(filter: LibraryWatchedFilter): String =
    when (filter) {
        LibraryWatchedFilter.ALL -> stringResource(Res.string.library_watched_filter_all)
        LibraryWatchedFilter.WATCHED -> stringResource(Res.string.library_watched_filter_watched)
        LibraryWatchedFilter.UNWATCHED -> stringResource(Res.string.library_watched_filter_unwatched)
    }

private fun List<LibraryVerticalEntry>.findEntry(preview: MetaPreview): LibraryVerticalEntry? =
    firstOrNull { entry -> entry.item.id == preview.id && entry.item.type == preview.type }
