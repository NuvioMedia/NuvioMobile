package com.nuvio.app.features.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NetflixColors
import com.nuvio.app.core.ui.NetflixDisplayFamily
import com.nuvio.app.core.ui.NetflixGreyButton
import com.nuvio.app.core.ui.NetflixPlayButton
import com.nuvio.app.core.ui.NetflixProgressBar
import com.nuvio.app.core.ui.NetflixRowTitle
import com.nuvio.app.core.ui.NetflixSans
import com.nuvio.app.core.ui.NetflixWordmark
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.launch

/** One row of the Netflix home, in order. */
@Immutable
internal sealed interface NetflixHomeRow {
    val key: String
    val title: String

    data class Catalog(
        val section: HomeCatalogSection,
        val entries: List<MetaPreview>,
        val onViewAll: (() -> Unit)?,
        override val key: String,
    ) : NetflixHomeRow {
        override val title: String get() = section.title
    }

    data class CollectionRow(
        val collection: Collection,
        override val key: String,
    ) : NetflixHomeRow {
        override val title: String get() = collection.title
    }
}

internal enum class NetflixHomeFilter { All, Shows, Movies }

private fun String.isShowType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")

private fun String.isMovieType(): Boolean = trim().lowercase() == "movie"

private fun NetflixHomeFilter.accepts(type: String): Boolean = when (this) {
    NetflixHomeFilter.All -> true
    NetflixHomeFilter.Shows -> type.isShowType()
    NetflixHomeFilter.Movies -> type.isMovieType()
}

private sealed interface NetflixEntry {
    val key: String

    data object Hero : NetflixEntry { override val key = "nf_hero" }
    data object TopSpacer : NetflixEntry { override val key = "nf_top_spacer" }
    data class Continue(val items: List<ContinueWatchingItem>, val title: String, override val key: String, val upcoming: Boolean) : NetflixEntry
    data class Row(val row: NetflixHomeRow, val top10: Boolean) : NetflixEntry { override val key: String get() = row.key }
    data object Loading : NetflixEntry { override val key = "nf_loading" }
    data object Empty : NetflixEntry { override val key = "nf_empty" }
}

private val NetflixSidePadding = 12.dp
private val NetflixPosterWidth = 108.dp

@Composable
internal fun NetflixHomeContent(
    listState: LazyListState,
    profileName: String?,
    heroItems: List<MetaPreview>,
    showHero: Boolean,
    heroLoading: Boolean,
    rows: List<NetflixHomeRow>,
    continueWatchingItems: List<ContinueWatchingItem>,
    upcomingItems: List<ContinueWatchingItem>,
    upcomingTitle: String,
    isLoading: Boolean,
    emptyContent: (@Composable () -> Unit)?,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    animateCollectionGifs: Boolean,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)?,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)?,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(NetflixHomeFilter.All) }
    val scope = rememberCoroutineScope()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = statusBarTop + 96.dp

    val filteredHero = remember(heroItems, filter) { heroItems.filter { filter.accepts(it.type) } }
    val filteredRows = remember(rows, filter) {
        rows.filter { row ->
            when (row) {
                is NetflixHomeRow.Catalog -> filter == NetflixHomeFilter.All ||
                    filter.accepts(row.section.target.contentType) ||
                    row.entries.any { filter.accepts(it.type) }
                is NetflixHomeRow.CollectionRow -> filter == NetflixHomeFilter.All
            }
        }.map { row ->
            if (row is NetflixHomeRow.Catalog && filter != NetflixHomeFilter.All) {
                row.copy(entries = row.entries.filter { filter.accepts(it.type) })
            } else {
                row
            }
        }.filter { it !is NetflixHomeRow.Catalog || it.entries.isNotEmpty() }
    }
    val filteredContinue = remember(continueWatchingItems, filter) {
        continueWatchingItems.filter { filter.accepts(it.parentMetaType) }
    }
    val filteredUpcoming = remember(upcomingItems, filter) {
        upcomingItems.filter { filter.accepts(it.parentMetaType) }
    }

    val continueTitle = if (profileName.isNullOrBlank()) "Continue Watching" else "Continue Watching for $profileName"
    val entries = remember(filteredHero, showHero, heroLoading, filteredRows, filteredContinue, filteredUpcoming, isLoading, emptyContent != null, continueTitle, upcomingTitle) {
        buildList {
            if (showHero && (filteredHero.isNotEmpty() || heroLoading)) add(NetflixEntry.Hero) else add(NetflixEntry.TopSpacer)
            if (filteredContinue.isNotEmpty()) add(NetflixEntry.Continue(filteredContinue, continueTitle, "nf_continue", upcoming = false))
            if (filteredUpcoming.isNotEmpty()) add(NetflixEntry.Continue(filteredUpcoming, upcomingTitle, "nf_upcoming", upcoming = true))
            val top10Index = filteredRows.indexOfFirst { row ->
                row is NetflixHomeRow.Catalog && row.title.lowercase().let { t ->
                    "trending" in t || "popular" in t || "top" in t
                }
            }
            filteredRows.forEachIndexed { index, row -> add(NetflixEntry.Row(row, top10 = index == top10Index)) }
            if (isLoading && filteredRows.isEmpty()) add(NetflixEntry.Loading)
            if (!isLoading && emptyContent != null && filteredRows.isEmpty()) add(NetflixEntry.Empty)
        }
    }

    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }

    Box(modifier = modifier.fillMaxSize().background(NetflixColors.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            items(entries, key = { it.key }, contentType = { it::class.simpleName }) { entry ->
                when (entry) {
                    NetflixEntry.Hero -> NetflixHeroBlock(
                        items = filteredHero,
                        loading = heroLoading && filteredHero.isEmpty(),
                        topPadding = topBarHeight,
                        onOpen = onPosterClick,
                        onLongPress = onPosterLongClick,
                    )
                    NetflixEntry.TopSpacer -> Spacer(Modifier.height(topBarHeight + 8.dp))
                    is NetflixEntry.Continue -> NetflixContinueRow(
                        title = entry.title,
                        items = entry.items,
                        showProgress = !entry.upcoming,
                        onClick = onContinueWatchingClick,
                        onLongPress = onContinueWatchingLongPress,
                    )
                    is NetflixEntry.Row -> when (val row = entry.row) {
                        is NetflixHomeRow.Catalog -> if (entry.top10) {
                            NetflixTop10Row(
                                title = row.title,
                                entries = row.entries.take(10),
                                onClick = onPosterClick,
                                onLongClick = onPosterLongClick,
                            )
                        } else {
                            NetflixPosterRow(
                                title = row.title,
                                entries = row.entries,
                                watchedKeys = watchedKeys,
                                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                                onViewAll = row.onViewAll,
                                onClick = onPosterClick,
                                onLongClick = onPosterLongClick,
                            )
                        }
                        is NetflixHomeRow.CollectionRow -> HomeCollectionRowSection(
                            collection = row.collection,
                            modifier = Modifier.padding(bottom = 14.dp),
                            sectionPadding = NetflixSidePadding,
                            animateGifs = animateCollectionGifs,
                            onFolderClick = onFolderClick,
                        )
                    }
                    NetflixEntry.Loading -> NetflixLoadingRows()
                    NetflixEntry.Empty -> Box(Modifier.padding(top = 24.dp)) { emptyContent?.invoke() }
                }
            }
        }

        NetflixHomeTopBar(
            profileName = profileName,
            filter = filter,
            scrolled = scrolled,
            categories = filteredRows,
            onFilterChange = { filter = it },
            onCategoryClick = { row ->
                val index = entries.indexOfFirst { it.key == row.key }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun NetflixHomeTopBar(
    profileName: String?,
    filter: NetflixHomeFilter,
    scrolled: Boolean,
    categories: List<NetflixHomeRow>,
    onFilterChange: (NetflixHomeFilter) -> Unit,
    onCategoryClick: (NetflixHomeRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (scrolled) Color.Black.copy(alpha = 0.92f) else Color.Transparent,
        label = "nf_topbar_bg",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (scrolled) {
                    Brush.verticalGradient(listOf(background, background))
                } else {
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                },
            )
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetflixWordmark(fontSize = 30)
            Spacer(Modifier.width(12.dp))
            if (!profileName.isNullOrBlank()) {
                Text(
                    text = "For $profileName",
                    style = TextStyle(
                        fontFamily = NetflixSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (filter != NetflixHomeFilter.All) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, NetflixColors.ChipBorder, CircleShape)
                        .clickable { onFilterChange(NetflixHomeFilter.All) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear filter", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            if (filter == NetflixHomeFilter.All || filter == NetflixHomeFilter.Shows) {
                NetflixChip(
                    label = "Shows",
                    selected = filter == NetflixHomeFilter.Shows,
                    onClick = { onFilterChange(if (filter == NetflixHomeFilter.Shows) NetflixHomeFilter.All else NetflixHomeFilter.Shows) },
                )
            }
            if (filter == NetflixHomeFilter.All || filter == NetflixHomeFilter.Movies) {
                NetflixChip(
                    label = "Movies",
                    selected = filter == NetflixHomeFilter.Movies,
                    onClick = { onFilterChange(if (filter == NetflixHomeFilter.Movies) NetflixHomeFilter.All else NetflixHomeFilter.Movies) },
                )
            }
            var categoriesOpen by remember { mutableStateOf(false) }
            Box {
                NetflixChip(
                    label = "Categories",
                    selected = false,
                    trailingIcon = true,
                    onClick = { categoriesOpen = true },
                )
                DropdownMenu(
                    expanded = categoriesOpen,
                    onDismissRequest = { categoriesOpen = false },
                    containerColor = NetflixColors.Surface,
                ) {
                    categories.forEach { row ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    row.title,
                                    style = TextStyle(fontFamily = NetflixSans, fontSize = 15.sp, color = Color.White),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                categoriesOpen = false
                                onCategoryClick(row)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: Boolean = false,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) Modifier.background(Color.White.copy(alpha = 0.22f))
                else Modifier.border(1.dp, NetflixColors.ChipBorder, RoundedCornerShape(16.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White),
        )
        if (trailingIcon) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixHeroBlock(
    items: List<MetaPreview>,
    loading: Boolean,
    topPadding: Dp,
    onOpen: ((MetaPreview) -> Unit)?,
    onLongPress: ((MetaPreview) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 40.dp).coerceAtMost(460.dp)
        val cardHeight = cardWidth * 1.42f
        val heroSidePeek = (maxWidth - cardWidth) / 2
        val pagerState = rememberPagerState(pageCount = { items.size.coerceAtLeast(1) })
        val current = items.getOrNull(pagerState.currentPage)

        // Tinted backdrop: the current poster, heavily blurred, fading into black.
        Box(
            Modifier
                .fillMaxWidth()
                .height(topPadding + cardHeight + 60.dp),
        ) {
            if (current != null) {
                AsyncImage(
                    model = current.poster ?: current.rawPosterUrl ?: current.banner,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(70.dp).alpha(0.75f),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.55f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black,
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = topPadding + 6.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading || items.isEmpty()) {
                Box(
                    Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NetflixColors.Card),
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = heroSidePeek),
                    pageSpacing = 12.dp,
                ) { page ->
                    val item = items[page]
                    NetflixHeroCard(
                        item = item,
                        width = cardWidth,
                        height = cardHeight,
                        onOpen = { onOpen?.invoke(item) },
                        onLongPress = onLongPress?.let { { it(item) } },
                    )
                }
                if (items.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(items.size.coerceAtMost(10)) { index ->
                            Box(
                                Modifier
                                    .size(width = if (index == pagerState.currentPage) 14.dp else 5.dp, height = 5.dp)
                                    .clip(CircleShape)
                                    .background(if (index == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.35f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixHeroCard(
    item: MetaPreview,
    width: Dp,
    height: Dp,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val libraryState by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val saved = remember(libraryState, item.id, item.type) { LibraryRepository.isSaved(item.id, item.type) }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .shadow(18.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(NetflixColors.Card)
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.poster ?: item.rawPosterUrl ?: item.banner,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(height * 0.55f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var logoFailed by remember(item.logo) { mutableStateOf(false) }
            if (!item.logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth(0.72f).height(78.dp),
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                )
            } else {
                Text(
                    text = item.name.uppercase(),
                    style = TextStyle(
                        fontFamily = NetflixDisplayFamily,
                        fontSize = 44.sp,
                        lineHeight = 44.sp,
                        color = Color.White,
                        letterSpacing = 1.sp,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val tags = item.genres.take(4)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tags.joinToString("  •  "),
                    style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NetflixPlayButton(
                    label = "Play",
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                )
                NetflixGreyButton(
                    label = "My List",
                    icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                    onClick = {
                        scope.launch {
                            runCatching { LibraryRepository.toggleSaved(item.toLibraryItem(savedAtEpochMs = 0L)) }
                                .onSuccess { result ->
                                    if (result.requiresRemovalConfirmation) onLongPress?.invoke()
                                }
                                .onFailure { error ->
                                    NuvioToastController.show(error.message ?: "Couldn't update My List")
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NetflixRowHeader(title: String, onViewAll: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NetflixSidePadding, end = NetflixSidePadding, bottom = 8.dp)
            .then(if (onViewAll != null) Modifier.clickable(onClick = onViewAll) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetflixRowTitle(title, modifier = Modifier.weight(1f, fill = false))
        if (onViewAll != null) {
            Text(
                text = "  ›",
                style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = NetflixColors.TextSecondary),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixPosterRow(
    title: String,
    entries: List<MetaPreview>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onViewAll: (() -> Unit)?,
    onClick: ((MetaPreview) -> Unit)?,
    onLongClick: ((MetaPreview) -> Unit)?,
) {
    val landscape = entries.firstOrNull()?.posterShape == PosterShape.Landscape
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        NetflixRowHeader(title, onViewAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = NetflixSidePadding),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(entries.withDuplicateSafeLazyKeys { it.stableKey() }, key = { it.lazyKey }) { keyed ->
                val item = keyed.value
                val watched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = item,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                )
                NetflixPoster(
                    item = item,
                    landscape = landscape,
                    watched = watched,
                    onClick = { onClick?.invoke(item) },
                    onLongClick = onLongClick?.let { { it(item) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixPoster(
    item: MetaPreview,
    landscape: Boolean,
    watched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    width: Dp = if (landscape) 186.dp else NetflixPosterWidth,
) {
    val imageUrl = if (landscape) item.landscapePoster ?: item.banner ?: item.poster else item.poster ?: item.rawPosterUrl
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
            .clip(RoundedCornerShape(4.dp))
            .background(NetflixColors.Card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        var failed by remember(imageUrl) { mutableStateOf(imageUrl.isNullOrBlank()) }
        if (!failed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
        if (failed || landscape) {
            Text(
                text = item.name,
                style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(8.dp),
            )
        }
        if (watched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixTop10Row(
    title: String,
    entries: List<MetaPreview>,
    onClick: ((MetaPreview) -> Unit)?,
    onLongClick: ((MetaPreview) -> Unit)?,
) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        NetflixRowHeader(title.let { if ("top 10" in it.lowercase()) it else "Top 10: $it" }, null)
        LazyRow(
            contentPadding = PaddingValues(horizontal = NetflixSidePadding),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            itemsIndexed(entries.withDuplicateSafeLazyKeys { "top10_" + it.stableKey() }, key = { _, it -> it.lazyKey }) { index, keyed ->
                val item = keyed.value
                Box(Modifier.width(if (index + 1 >= 10) 176.dp else 150.dp).height(144.dp)) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.align(Alignment.BottomStart).offset(y = 18.dp),
                        style = TextStyle(
                            fontFamily = NetflixSans,
                            fontWeight = FontWeight.Black,
                            fontSize = 128.sp,
                            letterSpacing = (-8).sp,
                            color = NetflixColors.Black,
                            drawStyle = Stroke(width = with(density) { 3.dp.toPx() }),
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.align(Alignment.BottomStart).offset(y = 18.dp),
                        style = TextStyle(
                            fontFamily = NetflixSans,
                            fontWeight = FontWeight.Black,
                            fontSize = 128.sp,
                            letterSpacing = (-8).sp,
                            color = Color(0xFF8C8C8C),
                            drawStyle = Stroke(width = with(density) { 2.dp.toPx() }),
                        ),
                        maxLines = 1,
                    )
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        NetflixPoster(
                            item = item,
                            landscape = false,
                            watched = false,
                            width = 96.dp,
                            onClick = { onClick?.invoke(item) },
                            onLongClick = onLongClick?.let { { it(item) } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixContinueRow(
    title: String,
    items: List<ContinueWatchingItem>,
    showProgress: Boolean,
    onClick: ((ContinueWatchingItem) -> Unit)?,
    onLongPress: ((ContinueWatchingItem) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        NetflixRowHeader(title, null)
        LazyRow(
            contentPadding = PaddingValues(horizontal = NetflixSidePadding),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(items.withDuplicateSafeLazyKeys { "${it.parentMetaId}:${it.seasonNumber}:${it.episodeNumber}" }, key = { it.lazyKey }) { keyed ->
                val item = keyed.value
                val episodeLabel = if (item.seasonNumber != null && item.episodeNumber != null) {
                    "S${item.seasonNumber}:E${item.episodeNumber}"
                } else {
                    null
                }
                Column(
                    modifier = Modifier
                        .width(NetflixPosterWidth)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NetflixColors.Surface),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 2.6f)
                            .background(NetflixColors.Card)
                            .combinedClickable(
                                onClick = { onClick?.invoke(item) },
                                onLongClick = onLongPress?.let { { it(item) } },
                            ),
                    ) {
                        AsyncImage(
                            model = item.poster ?: item.rawPosterUrl ?: item.imageUrl,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                    if (showProgress) {
                        NetflixProgressBar(
                            fraction = item.progressFraction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = episodeLabel ?: item.title,
                            modifier = Modifier.weight(1f),
                            style = TextStyle(fontFamily = NetflixSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = NetflixColors.TextSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clickable(enabled = onLongPress != null) { onLongPress?.invoke(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = NetflixColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixLoadingRows() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .padding(start = NetflixSidePadding, bottom = 10.dp)
                    .width(140.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NetflixColors.Card),
            )
            Row(
                modifier = Modifier.padding(start = NetflixSidePadding, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                repeat(4) {
                    Box(
                        Modifier
                            .width(NetflixPosterWidth)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NetflixColors.Card),
                    )
                }
            }
        }
    }
}
