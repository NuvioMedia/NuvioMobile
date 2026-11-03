package com.nuvio.app.features.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.poster.withCustomPosterUrl
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_all_types
import nuvio.composeapp.generated.resources.library_filter_list
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_filter_type
import nuvio.composeapp.generated.resources.library_sort_added_asc
import nuvio.composeapp.generated.resources.library_sort_added_desc
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_sort_provider_order
import nuvio.composeapp.generated.resources.library_sort_released_asc
import nuvio.composeapp.generated.resources.library_sort_released_desc
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryTopControls(
    selectedMode: LibraryViewMode,
    showServers: Boolean,
    onModeSelected: (LibraryViewMode) -> Unit,
    sourceMode: LibrarySourceMode,
    sortOption: LibrarySortOption,
    projection: LibraryVerticalProjection,
    onTypeSelected: (String?) -> Unit,
    onSortSelected: (LibrarySortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allTypesLabel = stringResource(Res.string.library_filter_all_types)
    val typeOptions = buildList {
        add(NuvioDropdownOption(key = "", label = allTypesLabel))
        addAll(projection.availableTypes.map { type ->
            NuvioDropdownOption(key = type, label = localizedMediaTypeLabel(type))
        })
    }
    val sortOptions = availableLibrarySortOptions(sourceMode).map { option ->
        NuvioDropdownOption(key = option.name, label = librarySortOptionLabel(option))
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(
            label = stringResource(Res.string.library_source_saved),
            selected = selectedMode == LibraryViewMode.Saved,
            onClick = { onModeSelected(LibraryViewMode.Saved) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Chip(
            label = stringResource(Res.string.library_source_cloud),
            selected = selectedMode == LibraryViewMode.Cloud,
            onClick = { onModeSelected(LibraryViewMode.Cloud) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_type),
            label = projection.selectedType?.let(::localizedMediaTypeLabel) ?: allTypesLabel,
            selectedKey = projection.selectedType.orEmpty(),
            options = typeOptions,
            enabled = typeOptions.size > 1,
            onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_sort),
            label = librarySortOptionLabel(sortOption),
            selectedKey = sortOption.name,
            options = sortOptions,
            enabled = sortOptions.size > 1,
            onSelected = { option ->
                LibrarySortOption.entries.firstOrNull { it.name == option.key }?.let(onSortSelected)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    if (showServers) {
        Chip(
            label = stringResource(Res.string.library_source_servers),
            selected = selectedMode == LibraryViewMode.Servers,
            onClick = { onModeSelected(LibraryViewMode.Servers) },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun LibraryStatusTabs(
    sections: List<LibrarySection>,
    selectedSectionKey: String?,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            Chip(
                label = localizedLibrarySectionTitle(section.type, section.displayTitle),
                selected = section.type == selectedSectionKey,
                onClick = { onSectionSelected(section.type) },
                enabled = sections.size > 1,
            )
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

private fun List<LibraryVerticalEntry>.findEntry(preview: MetaPreview): LibraryVerticalEntry? =
    firstOrNull { entry -> entry.item.id == preview.id && entry.item.type == preview.type }
