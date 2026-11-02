package com.nuvio.app.features.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.ondemand.OnDemandGroups
import com.nuvio.app.features.livetv.ondemand.OnDemandPlayback
import com.nuvio.app.features.livetv.ondemand.OnDemandRepository
import com.nuvio.app.features.livetv.parental.ParentalControls
import com.nuvio.app.features.livetv.ondemand.VodCategory
import com.nuvio.app.features.livetv.ondemand.VodItem
import com.nuvio.app.features.livetv.ondemand.VodKind
import com.nuvio.app.features.livetv.ondemand.VodMatch
import com.nuvio.app.features.livetv.ondemand.VodSort
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.watched.WatchedRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.livetv_no_results
import nuvio.composeapp.generated.resources.livetv_on_demand
import nuvio.composeapp.generated.resources.livetv_vod_empty_body
import nuvio.composeapp.generated.resources.livetv_vod_empty_title
import nuvio.composeapp.generated.resources.livetv_vod_manage_groups
import nuvio.composeapp.generated.resources.livetv_vod_movies
import nuvio.composeapp.generated.resources.livetv_vod_recently_added
import nuvio.composeapp.generated.resources.livetv_vod_search_hint
import nuvio.composeapp.generated.resources.livetv_vod_series
import nuvio.composeapp.generated.resources.livetv_vod_sort
import org.jetbrains.compose.resources.stringResource

private const val ROW_LIMIT = 24
private const val PAGE = 60

/** Lets the player open an On Demand title; [onOpenDetails] opens the app's own page for a TMDB match. */
internal class OnDemandActions(
    val onPlay: (PlayerLaunch) -> Unit,
    val onOpenDetails: (type: String, tmdbId: String, title: String) -> Unit,
    val onSeeAll: (kind: VodKind, categoryUid: String?, title: String) -> Unit,
)

/**
 * On Demand: your providers' movies and series, a row per group (See all opens the whole group),
 * with a search across them. Tap a title for its details, to play it or (series) pick an episode.
 */
@Composable
internal fun OnDemandScreen(
    onBack: () -> Unit,
    actions: OnDemandActions,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val version by OnDemandRepository.version.collectAsStateWithLifecycle()
    val status by OnDemandRepository.status.collectAsStateWithLifecycle()
    val hasContent by OnDemandRepository.hasContent.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(VodKind.MOVIE.key) }
    val vodKind = VodKind.of(kind)
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var manageGroups by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VodItem?>(null) }
    val listState = rememberLazyListState()

    val categories by produceState(emptyList<VodCategory>(), vodKind, version) {
        value = OnDemandRepository.categories(vodKind)
    }
    // Parental controls: locked categories ("vod:<uid>") stay out of every row until unlocked.
    val unlocked by ParentalControls.unlocked.collectAsStateWithLifecycle()
    val lockedUids = remember(categories, settings, user.lockedGroups, unlocked) {
        categories.filter { c -> ParentalControls.isLocked("vod:${c.uid}", c.name, settings, user.lockedGroups) }.mapTo(HashSet()) { it.uid }
    }
    // Playlist dropdown: the providers that have On Demand titles; one pick limits every row to it.
    val allPlaylists by LiveTvPreferences.playlists.collectAsStateWithLifecycle()
    val savedPlaylist by PlaylistFilter.selected.collectAsStateWithLifecycle()
    val playlistChoices = remember(categories, allPlaylists) {
        allPlaylists.mapNotNull { p ->
            val n = categories.count { it.playlistId == p.id }
            if (n > 0) PlaylistChoice(p.id, p.name, if (n == 1) "1 group" else "$n groups") else null
        }
    }
    val playlistId = PlaylistFilter.effective(savedPlaylist, playlistChoices.map { it.id })
    val otherPlaylists = remember(categories, playlistId) {
        if (playlistId == null) emptySet() else categories.filter { it.playlistId != playlistId }.mapTo(HashSet()) { it.uid }
    }
    val hiddenOrLocked = remember(user.vodHiddenCategories, lockedUids, otherPlaylists) { user.vodHiddenCategories + lockedUids + otherPlaylists }
    var unlockCategory by remember { mutableStateOf<VodCategory?>(null) }
    val visible = remember(categories, user, lockedUids, otherPlaylists) { OnDemandGroups.visible(categories, user).filter { it.uid !in lockedUids && it.uid !in otherPlaylists } }
    val lockedShown = remember(categories, user, lockedUids, otherPlaylists) { OnDemandGroups.visible(categories, user).filter { it.uid in lockedUids && it.uid !in otherPlaylists } }
    val results by produceState(emptyList<VodItem>(), vodKind, query, version, hiddenOrLocked) {
        value = if (query.isBlank()) emptyList() else OnDemandRepository.search(vodKind, query, hiddenOrLocked, merge = settings.vodMergeDuplicates)
    }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.livetv_on_demand),
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Filled.Search, contentDescription = null, tint = tokens.colors.textPrimary)
                }
                IconButton(onClick = { manageGroups = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = stringResource(Res.string.livetv_vod_manage_groups), tint = tokens.colors.textPrimary)
                }
                IconButton(onClick = { OnDemandRepository.refreshNow() }, enabled = !status.loading) {
                    if (status.loading) NuvioLoadingIndicator(size = NuvioTokens.Space.s20)
                    else Icon(Icons.Filled.Refresh, contentDescription = null, tint = tokens.colors.textPrimary)
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Chip(label = stringResource(Res.string.livetv_vod_movies), selected = vodKind == VodKind.MOVIE, onClick = { kind = VodKind.MOVIE.key })
            Chip(label = stringResource(Res.string.livetv_vod_series), selected = vodKind == VodKind.SERIES, onClick = { kind = VodKind.SERIES.key })
        }
        PlaylistDropdown(
            choices = playlistChoices,
            selectedId = playlistId,
            allDetail = categories.size.let { if (it == 1) "1 group" else "$it groups" },
            onSelect = PlaylistFilter::select,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
        )
        AnimatedVisibility(visible = searching) {
            NuvioInputField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(Res.string.livetv_vod_search_hint),
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            )
        }
        status.message?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.loading) tokens.colors.textMuted else tokens.colors.danger,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            searching && query.isNotBlank() -> VodGrid(
                items = results,
                emptyText = stringResource(Res.string.livetv_no_results),
                onItem = { selected = it },
                modifier = Modifier.fillMaxSize(),
            )
            !hasContent -> EmptyOnDemand(status.loading)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = NuvioTokens.Space.s8, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
            ) {
                item(key = "recent") {
                    VodRow(
                        title = stringResource(Res.string.livetv_vod_recently_added),
                        kind = vodKind, category = null, sort = VodSort.RECENTLY_ADDED,
                        hidden = hiddenOrLocked, merge = settings.vodMergeDuplicates, version = version,
                        onSeeAll = { actions.onSeeAll(vodKind, null, it) },
                        onItem = { selected = it },
                    )
                }
                items(visible.size, key = { visible[it].uid }) { i ->
                    val c = visible[i]
                    VodRow(
                        title = c.name,
                        kind = vodKind, category = c, sort = OnDemandGroups.sortFor(c.uid, user, settings),
                        hidden = hiddenOrLocked, merge = settings.vodMergeDuplicates, version = version,
                        onSeeAll = { actions.onSeeAll(vodKind, c.uid, it) },
                        onItem = { selected = it },
                    )
                }
                items(lockedShown.size, key = { "locked:" + lockedShown[it].uid }) { i ->
                    val c = lockedShown[i]
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { unlockCategory = c }.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = tokens.colors.textMuted)
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    unlockCategory?.let { c -> UnlockGroupDialog("vod:${c.uid}", c.name, onDismiss = { unlockCategory = null }) }
    selected?.let { item -> OnDemandTitleSheet(item = item, actions = actions, onDismiss = { selected = null }) }
    if (manageGroups) ManageVodGroupsDialog(kind = vodKind, categories = categories, onDismiss = { manageGroups = false })
}

@Composable
private fun EmptyOnDemand(loading: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s48, horizontal = NuvioTokens.Space.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        if (loading) NuvioLoadingIndicator()
        else Icon(Icons.Filled.Movie, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(NuvioTokens.Space.s56))
        Text(stringResource(Res.string.livetv_vod_empty_title), style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(stringResource(Res.string.livetv_vod_empty_body), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
    }
}

@Composable
private fun VodRow(
    title: String,
    kind: VodKind,
    category: VodCategory?,
    sort: VodSort,
    hidden: Set<String>,
    merge: Boolean,
    version: Int,
    onSeeAll: (String) -> Unit,
    onItem: (VodItem) -> Unit,
) {
    val items by produceState(emptyList<VodItem>(), kind, category?.uid, sort, hidden, merge, version) {
        value = OnDemandRepository.items(kind, category, hidden, ROW_LIMIT, 0, sort, merge)
    }
    if (items.isEmpty()) return
    val watchedState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    NuvioShelfSection(
        title = title,
        entries = items,
        headerHorizontalPadding = NuvioTokens.Space.s16,
        rowContentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
        onViewAllClick = { onSeeAll(title) },
        key = { it.uid },
    ) { item ->
        val match = rememberVodMatch(item)
        val watched = remember(item.uid, match, watchedState.watchedKeys) { OnDemandPlayback.isWatched(item, match) }
        NuvioPosterCard(
            title = match?.title ?: item.displayTitle,
            imageUrl = match?.poster?.takeIf { it.isNotBlank() } ?: item.icon,
            fallbackImageUrl = item.icon,
            detailLine = item.year?.toString(),
            isWatched = watched,
            onClick = { onItem(item) },
            onLongClick = { onItem(item) },
        )
    }
}

/** The TMDB match for a title (poster, proper name), looked up once and remembered. */
@Composable
internal fun rememberVodMatch(item: VodItem): VodMatch? {
    val match by produceState<VodMatch?>(null, item.uid) {
        value = runCatching { OnDemandRepository.match(item) }.getOrNull()
    }
    return match
}

/** A whole group (or everything of a kind), as a grid that loads more as you scroll, with a sort. */
@Composable
internal fun OnDemandSeeAllScreen(
    kindKey: String,
    categoryUid: String?,
    title: String,
    onBack: () -> Unit,
    actions: OnDemandActions,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val kind = VodKind.of(kindKey)
    val version by OnDemandRepository.version.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val categories by produceState(emptyList<VodCategory>(), kind, version) { value = OnDemandRepository.categories(kind) }
    val category = categories.firstOrNull { it.uid == categoryUid }
    val sort = OnDemandGroups.sortFor(categoryUid, user, settings)
    // "All" (Recently added) leaves locked categories out too.
    val unlockedNow by ParentalControls.unlocked.collectAsStateWithLifecycle()
    val savedPlaylist by PlaylistFilter.selected.collectAsStateWithLifecycle()
    val playlistId = PlaylistFilter.effective(savedPlaylist, categories.map { it.playlistId })
    val hiddenOrLocked = remember(categories, user, settings, unlockedNow, playlistId) {
        user.vodHiddenCategories +
            categories.filter { c -> ParentalControls.isLocked("vod:${c.uid}", c.name, settings, user.lockedGroups) }.map { it.uid } +
            categories.filter { c -> playlistId != null && c.playlistId != playlistId }.map { it.uid }
    }
    var sortDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VodItem?>(null) }
    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var wanted by remember { mutableStateOf(PAGE) }
    val gridState = rememberLazyGridState()

    // Pages load as the grid nears its end; a new sort or import starts again from the top.
    LaunchedEffect(kind, categoryUid, sort, version, hiddenOrLocked, settings.vodMergeDuplicates, categories.isEmpty()) {
        if (categoryUid != null && category == null) return@LaunchedEffect
        items = emptyList(); exhausted = false; wanted = PAGE
    }
    LaunchedEffect(wanted, items.size, exhausted, category, sort, version) {
        if (exhausted || loading || items.size >= wanted) return@LaunchedEffect
        if (categoryUid != null && category == null) return@LaunchedEffect
        loading = true
        val page = OnDemandRepository.items(kind, category, hiddenOrLocked, PAGE, items.size, sort, settings.vodMergeDuplicates)
        items = (items + page).distinctBy { it.uid }
        if (page.size < PAGE) exhausted = true
        loading = false
    }
    val nearEnd by remember { derivedStateOf { val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= items.size - 12 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && !exhausted && items.isNotEmpty()) wanted = items.size + PAGE }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = title.ifBlank { stringResource(Res.string.livetv_on_demand) },
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                IconButton(onClick = { sortDialog = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = stringResource(Res.string.livetv_vod_sort), tint = tokens.colors.textPrimary)
                }
            },
        )
        Text(
            text = sort.label + (category?.let { "  ·  ${it.count}" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
        )
        VodGrid(
            items = items,
            emptyText = if (loading) "" else stringResource(Res.string.livetv_no_results),
            onItem = { selected = it },
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            loadingMore = loading,
        )
    }

    selected?.let { item -> OnDemandTitleSheet(item = item, actions = actions, onDismiss = { selected = null }) }
    if (sortDialog) {
        DialogSurface(onDismissRequest = { sortDialog = false }, title = stringResource(Res.string.livetv_vod_sort)) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                VodSort.entries.forEach { s ->
                    DialogOption(text = s.label, selected = s == sort, onClick = {
                        if (categoryUid != null) LiveTvPreferences.setVodSort(categoryUid, s.name)
                        else LiveTvPreferences.updateSettings { it.copy(vodDefaultSort = s.name) }
                        sortDialog = false
                    })
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = { sortDialog = false }) }
        }
    }
}

@Composable
internal fun VodGrid(
    items: List<VodItem>,
    emptyText: String,
    onItem: (VodItem) -> Unit,
    modifier: Modifier = Modifier,
    state: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    loadingMore: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val watchedState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier) {
        val columns = remember(maxWidth) { gridColumnsForWidth(maxWidth) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, top = NuvioTokens.Space.s8, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24)),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
        ) {
            if (items.isEmpty() && emptyText.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16))
                }
            }
            items(items.size, key = { items[it].uid }) { i -> VodTile(items[i], watchedState.watchedKeys, onClick = { onItem(items[i]) }) }
            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s16), contentAlignment = Alignment.Center) { NuvioLoadingIndicator() }
                }
            }
        }
    }
}

private fun gridColumnsForWidth(width: Dp): Int = when {
    width >= 1100.dp -> 7
    width >= 900.dp -> 6
    width >= 700.dp -> 5
    width >= 520.dp -> 4
    else -> 3
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VodTile(item: VodItem, watchedKeys: Set<String>, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val match = rememberVodMatch(item)
    val watched = remember(item.uid, match, watchedKeys) { OnDemandPlayback.isWatched(item, match) }
    val poster = match?.poster?.takeIf { it.isNotBlank() } ?: item.icon
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(tokens.colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = onClick),
        ) {
            if (poster != null) {
                AsyncImage(model = poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(NuvioTokens.Space.s8),
                )
            }
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null, tint = tokens.colors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s6).size(NuvioTokens.Icon.md),
                )
            }
        }
        Text(
            text = match?.title ?: item.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted) }
    }
}

/** Manage groups: show or hide each of a kind's groups, and move them up or down the page. */
@Composable
private fun ManageVodGroupsDialog(kind: VodKind, categories: List<VodCategory>, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val ordered = remember(categories, user.vodGroupOrder) {
        val order = user.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
        categories.withIndex().sortedWith(compareBy<IndexedValue<VodCategory>>({ order[it.value.uid] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }
    fun move(uid: String, delta: Int) {
        val ids = ordered.map { it.uid }.toMutableList()
        val i = ids.indexOf(uid)
        if (i < 0) return
        ids.removeAt(i)
        ids.add((i + delta).coerceIn(0, ids.size), uid)
        // Groups of the other kind keep their place at the end of the saved order.
        val others = user.vodGroupOrder.filter { it !in ids }
        LiveTvPreferences.saveVodGroups(user.vodHiddenCategories, ids + others, user.vodGroupNames)
    }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_vod_manage_groups)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            items(ordered.size, key = { ordered[it].uid }) { i ->
                val c = ordered[i]
                val hidden = c.uid in user.vodHiddenCategories
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DialogOption(
                        text = (user.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() } ?: c.name) + "  ·  ${c.count}",
                        selected = !hidden,
                        role = Role.Checkbox,
                        onClick = { if (hidden) OnDemandGroups.show(c.uid) else OnDemandGroups.hide(c.uid) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { move(c.uid, -1) }, enabled = i > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = tokens.colors.textMuted)
                    }
                    IconButton(onClick = { move(c.uid, 1) }, enabled = i < ordered.lastIndex) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = tokens.colors.textMuted)
                    }
                }
            }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}
