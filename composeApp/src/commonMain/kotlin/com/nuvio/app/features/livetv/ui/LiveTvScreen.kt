package com.nuvio.app.features.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.TheaterComedy
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.livetv_group_channels
import nuvio.composeapp.generated.resources.livetv_group_one_channel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.NowNext
import com.nuvio.app.features.livetv.model.ChannelGroup
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.ondemand.OnDemandRepository
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import com.nuvio.app.features.livetv.record.LiveTvRecorder
import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.sports.SportsLive
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_add_playlist
import nuvio.composeapp.generated.resources.livetv_empty_body
import nuvio.composeapp.generated.resources.livetv_empty_title
import nuvio.composeapp.generated.resources.livetv_guide
import nuvio.composeapp.generated.resources.livetv_header_guide
import nuvio.composeapp.generated.resources.livetv_header_search
import nuvio.composeapp.generated.resources.livetv_header_settings
import nuvio.composeapp.generated.resources.livetv_no_results
import nuvio.composeapp.generated.resources.livetv_on_demand
import nuvio.composeapp.generated.resources.livetv_rec_record_now
import nuvio.composeapp.generated.resources.livetv_rec_schedule
import nuvio.composeapp.generated.resources.livetv_rec_stop
import nuvio.composeapp.generated.resources.livetv_calendar
import nuvio.composeapp.generated.resources.livetv_recordings
import nuvio.composeapp.generated.resources.livetv_search_hint
import nuvio.composeapp.generated.resources.livetv_settings
import nuvio.composeapp.generated.resources.livetv_sports
import nuvio.composeapp.generated.resources.livetv_manage_menu
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import nuvio.composeapp.generated.resources.livetv_title
import nuvio.composeapp.generated.resources.livetv_update_now
import org.jetbrains.compose.resources.stringResource

/**
 * The Live TV tab: channels grouped (favorites first), each with what's on now and how far
 * through it is. Tap plays; long-press opens the channel's options.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onPlayChannel: (LiveChannel, nowTitle: String?) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Replay the show on now from its beginning (catch-up). */
    onPlayCatchup: (LiveChannel, EpgProgram) -> Unit = { _, _ -> },
    /** Opens On Demand (your providers' movies and series). */
    onOpenOnDemand: () -> Unit = {},
    /** Opens Recordings. */
    onOpenRecordings: () -> Unit = {},
    /** Opens the Sports hub. */
    onOpenSports: () -> Unit = {},
    /** Opens Manage (reorder and show or hide groups and channels). */
    onOpenManage: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    // Restore the open groups and the scroll position when coming back from the player.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = LiveTvListMemory.scrollIndex,
        initialFirstVisibleItemScrollOffset = LiveTvListMemory.scrollOffset,
    )
    DisposableEffect(listState) {
        onDispose { LiveTvListMemory.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    val playlists by LiveTvPreferences.playlists.collectAsStateWithLifecycle()
    val savedPlaylist by PlaylistFilter.selected.collectAsStateWithLifecycle()
    val hasOnDemand by OnDemandRepository.hasContent.collectAsStateWithLifecycle()
    val showOnDemand = hasOnDemand || playlists.any { it.enabled && it.importVod }
    val recordings by remember { LiveTvRecordingScheduler.start(); LiveTvRecorder.recordings }.collectAsStateWithLifecycle()
    val schedules by LiveTvRecordingScheduler.schedules.collectAsStateWithLifecycle()
    val seriesRules by LiveTvRecordingScheduler.series.collectAsStateWithLifecycle()
    val showRecordings = recordings.isNotEmpty() || schedules.isNotEmpty() || seriesRules.isNotEmpty()
    var manualRecording by remember { mutableStateOf<Pair<LiveChannel, Boolean>?>(null) }
    val sportsSettings by remember { SportsRepository.start(); SportsRepository.settings }.collectAsStateWithLifecycle()
    val sportsOn = sportsSettings.showInSidebar
    val state by remember {
        LiveTvRepository.ensureLoaded()
        LiveTvViewState.list
    }.collectAsStateWithLifecycle()
    val status by LiveTvRepository.status.collectAsStateWithLifecycle()
    val programs by LiveTvRepository.programs.collectAsStateWithLifecycle()
    val now by produceState(LiveTvClock.nowMs()) {
        while (true) {
            value = LiveTvClock.nowMs()
            delay(30_000)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Groups start collapsed; tapping a header opens it. Kept across the trip to the player.
    val expanded = remember { mutableStateOf(LiveTvListMemory.expanded) }
    var sheetChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var unlockGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }
    ScreenActivityEffect { active ->
        if (active) {
            LiveTvRepository.refreshIfStale()
            while (true) {
                delay(5 * 60_000L)
                LiveTvRepository.refreshWindow()
            }
        }
    }

    // Playlist dropdown: only playlists that have groups here; hidden with fewer than two.
    val playlistChoices = remember(state.sections, playlists) {
        playlists.mapNotNull { p ->
            val n = state.sections.count { it.group.sourceId == p.id }
            if (n > 0) PlaylistChoice(p.id, p.name, if (n == 1) "1 group" else "$n groups") else null
        }
    }
    val playlistId = PlaylistFilter.effective(savedPlaylist, playlistChoices.map { it.id })
    val playlistNames = remember(playlists) { playlists.associate { it.id to it.name } }
    val sections = remember(state.sections, playlistId) {
        if (playlistId == null) state.sections
        else state.sections.filter { it.group.sourceId == null || it.group.sourceId == playlistId }
    }
    val searchResults by remember(state.visible, query, playlistId) {
        derivedStateOf { LiveTvViewState.search(state.visible.filter { playlistId == null || it.sourceId == playlistId }, query) }
    }
    val nowNext: Map<String, NowNext> = remember(programs, now) {
        LiveTvRepository.nowNext(programs.keys, now)
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Where the sticky header ends, so the open group's name can sit pinned just under it.
    var boxTopPx by remember { mutableStateOf(0f) }
    var headerBottomPx by remember { mutableStateOf(0f) }
    val showList = state.hasSources && !(searching && query.isNotBlank())
    // The open group whose channels are at the top of the list, once its own card has scrolled away.
    val pinnedGroupId by remember(sections, showList) {
        derivedStateOf {
            if (!showList) return@derivedStateOf null
            val visible = listState.layoutInfo.visibleItemsInfo
            val header = visible.firstOrNull { it.index == 0 } ?: return@derivedStateOf null
            val edge = header.offset + header.size
            val first = visible.firstOrNull { it.index > 0 && it.offset + it.size > edge } ?: return@derivedStateOf null
            val key = first.key as? String ?: return@derivedStateOf null
            if (key.startsWith("g:")) return@derivedStateOf null
            sections.filter { it.group.id in expanded.value && key.startsWith(it.group.id + "/") }
                .maxByOrNull { it.group.id.length }?.group?.id
        }
    }
    /** The list position of a group's card (the sticky header is item 0). Mirrors the items below. */
    fun groupCardIndex(groupId: String): Int? {
        var index = 1
        sections.forEach { s ->
            if (s.group.id == groupId) return index
            index += 1 + if (s.group.id in expanded.value) s.channels.size else 0
        }
        return null
    }

    Box(modifier = modifier.fillMaxSize().liveTvSideInsets().onGloballyPositioned { boxTopPx = it.positionInRoot().y }) {
        NuvioScreen(modifier = Modifier.fillMaxSize(), listState = listState) {
            stickyHeader {
                Column(modifier = Modifier.background(tokens.colors.background).onGloballyPositioned { headerBottomPx = it.positionInRoot().y + it.size.height }) {
                    // The title has the top row to itself (with the menu for managing playlists); every page
                    // button sits in one labelled row under it, so none are hidden.
                    val headerActions = buildList {
                        add(HeaderAction(Icons.Filled.Search, stringResource(Res.string.livetv_search_hint), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_header_search), showClose = searching) { searching = !searching; if (!searching) query = "" })
                        add(HeaderAction(Icons.Filled.CalendarViewDay, stringResource(Res.string.livetv_guide), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_header_guide)) { onOpenGuide() })
                        add(HeaderAction(Icons.Filled.CalendarMonth, stringResource(Res.string.livetv_calendar), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_calendar)) { onOpenCalendar() })
                        if (showOnDemand) add(HeaderAction(Icons.Filled.Movie, stringResource(Res.string.livetv_on_demand), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_on_demand)) { onOpenOnDemand() })
                        if (sportsOn) add(HeaderAction(Icons.Filled.SportsSoccer, stringResource(Res.string.livetv_sports), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_sports)) { onOpenSports() })
                        if (showRecordings) add(HeaderAction(Icons.Filled.FiberManualRecord, stringResource(Res.string.livetv_recordings), if (recordings.any { it.isRecording }) tokens.colors.danger else tokens.colors.textPrimary, short = stringResource(Res.string.livetv_recordings)) { onOpenRecordings() })
                        add(HeaderAction(Icons.Filled.Settings, stringResource(Res.string.livetv_settings), tokens.colors.textPrimary, short = stringResource(Res.string.livetv_header_settings)) { onOpenSettings() })
                    }
                    NuvioScreenHeader(
                        title = stringResource(Res.string.livetv_title),
                        actions = {
                            if (state.hasSources) {
                                IconButton(onClick = { overflow = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = tokens.colors.textPrimary)
                                }
                                Menu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    MenuItem(text = stringResource(Res.string.livetv_manage_menu), onClick = { overflow = false; onOpenManage() })
                                    MenuItem(text = stringResource(Res.string.livetv_update_now), onClick = { overflow = false; LiveTvRepository.refreshAll(force = true) })
                                }
                            }
                        },
                    )
                    Row(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(bottom = NuvioTokens.Space.s4)) {
                        headerActions.forEach { a -> HeaderButton(a, Modifier.weight(1f)) }
                    }
                    PlaylistDropdown(
                        choices = playlistChoices,
                        selectedId = playlistId,
                        allDetail = (state.sections.count { it.group.sourceId != null }).let { if (it == 1) "1 group" else "$it groups" },
                        onSelect = PlaylistFilter::select,
                        modifier = Modifier.padding(bottom = NuvioTokens.Space.s8),
                    )
                    AnimatedVisibility(visible = searching) {
                        NuvioInputField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(Res.string.livetv_search_hint),
                            modifier = Modifier.padding(bottom = NuvioTokens.Space.s8),
                        )
                    }
                    if (status.loading && status.message != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTokens.Space.s8),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        ) {
                            NuvioLoadingIndicator(size = NuvioTokens.Space.s16)
                            Text(status.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (status.error != null) {
                        Text(
                            text = status.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.danger,
                            modifier = Modifier.padding(bottom = NuvioTokens.Space.s8),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!state.hasSources) {
                item { EmptyLiveTv(onOpenSettings) }
            } else if (searching && query.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    item {
                        Text(stringResource(Res.string.livetv_no_results), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16))
                    }
                }
                items(searchResults.size, key = { "s:" + searchResults[it].key }) { i ->
                    val c = searchResults[i]
                    ChannelRow(c, nowNext[c.key], now, c.key in state.favoriteKeys, onClick = { onPlayChannel(c, nowNext[c.key]?.now?.title) }, onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheetChannel = c
                    })
                }
            } else {
                if (sections.isEmpty() && !status.loading) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s16), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                            Text(stringResource(Res.string.livetv_no_results), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                            NuvioPrimaryButton(text = stringResource(Res.string.livetv_update_now)) { LiveTvRepository.refreshAll(force = true) }
                        }
                    }
                }
                sections.forEach { section ->
                    val id = section.group.id
                    val isCollapsed = id !in expanded.value
                    item(key = "g:$id") {
                        GroupCard(section.group, section.channels, isCollapsed, playlistTag = if (playlistId == null && playlistChoices.size > 1) playlistNames[section.group.sourceId] else null) {
                            if (section.group.locked) unlockGroup = section.group
                            else {
                                expanded.value = if (isCollapsed) expanded.value + id else expanded.value - id
                                LiveTvListMemory.expanded = expanded.value
                            }
                        }
                    }
                    if (!isCollapsed) {
                        items(section.channels.size, key = { "$id/" + section.channels[it].key }) { i ->
                            val c = section.channels[i]
                            ChannelRow(c, nowNext[c.key], now, c.key in state.favoriteKeys, onClick = {
                                LiveTvListMemory.expanded = expanded.value + id
                                LiveTvListMemory.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onPlayChannel(c, nowNext[c.key]?.now?.title)
                            }, onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheetChannel = c
                            }, modifier = Modifier.padding(start = NuvioTokens.Space.s12))
                        }
                    }
                }
            }
        }
        val pinned = pinnedGroupId?.let { gid -> sections.firstOrNull { it.group.id == gid } }
        if (pinned != null && headerBottomPx > boxTopPx) {
            Box(
                modifier = Modifier
                    .offset(y = with(density) { (headerBottomPx - boxTopPx).toDp() })
                    .fillMaxWidth()
                    .background(tokens.colors.background)
                    .padding(horizontal = tokens.spacing.screenHorizontal)
                    .padding(bottom = NuvioTokens.Space.s6),
            ) {
                PinnedGroupBar(pinned.group) {
                    val gid = pinned.group.id
                    val index = groupCardIndex(gid)
                    expanded.value = expanded.value - gid
                    LiveTvListMemory.expanded = expanded.value
                    // Back to the group's card, just under the header (not hidden behind it).
                    if (index != null) scope.launch {
                        listState.scrollToItem(index)
                        val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                        if (header != null) listState.scrollBy(-(header.offset + header.size).toFloat())
                    }
                }
            }
        }
    }

    sheetChannel?.let { channel ->
        ChannelActionsSheet(
            channel = channel,
            isFavorite = channel.key in state.favoriteKeys,
            customGroups = state.customGroups,
            onDismiss = { sheetChannel = null },
            onPlay = { onPlayChannel(channel, nowNext[channel.key]?.now?.title) },
            onWatchFromStart = nowNext[channel.key]?.now
                ?.takeIf { LiveTvCatchup.canWatchFromStart(channel, it, now) }
                ?.let { program -> { onPlayCatchup(channel, program) } },
            extraRows = { close ->
                val active = recordings.firstOrNull { it.channelKey == channel.key && it.isRecording }
                if (active != null) {
                    NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_rec_stop), icon = Icons.Filled.Stop, onClick = { close { LiveTvRecorder.stop(active.id) } })
                } else {
                    NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_rec_record_now), icon = Icons.Filled.FiberManualRecord, onClick = { close { manualRecording = channel to false } })
                }
                NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_rec_schedule), icon = Icons.Filled.Schedule, onClick = { close { manualRecording = channel to true } })
            },
        )
    }

    manualRecording?.let { (channel, scheduleOnly) ->
        ManualRecordingDialog(channel = channel, scheduleOnly = scheduleOnly, onDismiss = { manualRecording = null })
    }
    unlockGroup?.let { g -> UnlockGroupDialog(g.id, g.title, onDismiss = { unlockGroup = null }) }
}

@Composable
private fun EmptyLiveTv(onOpenSettings: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s48, horizontal = NuvioTokens.Space.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Icon(Icons.Filled.LiveTv, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(NuvioTokens.Space.s56))
        Text(stringResource(Res.string.livetv_empty_title), style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(stringResource(Res.string.livetv_empty_body), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        NuvioPrimaryButton(text = stringResource(Res.string.livetv_add_playlist), onClick = onOpenSettings)
    }
}

@Composable
private fun GroupCard(
    group: ChannelGroup,
    channels: List<LiveChannel>,
    collapsed: Boolean,
    playlistTag: String? = null,
    onToggle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val open = !collapsed && !group.locked
    val arrow by animateFloatAsState(if (open) 180f else 0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.xl))
            .background(tokens.colors.surface)
            .clickable(onClick = onToggle)
            .padding(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        GroupIconBox(group, size = 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlistTag != null) {
                    Text(
                        text = playlistTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 110.dp)
                            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                            .background(tokens.colors.surfaceCard)
                            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
                    )
                }
            }
            if (!group.locked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                    // A peek at the group: its first few channel logos.
                    if (settings.phoneChannelLogos) {
                        channels.asSequence().filter { !it.logo.isNullOrBlank() }.take(4).forEach {
                            ChannelLogo(it, width = 30.dp, height = 20.dp, inset = NuvioTokens.Space.s2, radius = NuvioTokens.Radius.xs)
                        }
                    }
                    Text(
                        text = if (group.count == 1) stringResource(Res.string.livetv_group_one_channel) else stringResource(Res.string.livetv_group_channels, group.count),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = NuvioTokens.Space.s2),
                    )
                }
            }
        }
        if (!group.locked) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = if (open) tokens.colors.accent else tokens.colors.textMuted,
                modifier = Modifier.rotate(arrow),
            )
        }
    }
}

/** The open group's name, pinned under the header while its channels scroll; tap closes the group. */
@Composable
private fun PinnedGroupBar(group: ChannelGroup, onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tokens.colors.surface)
            .clickable(onClick = onClose)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        GroupIconBox(group, size = 28.dp)
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ExpandLess, contentDescription = null, tint = tokens.colors.accent)
    }
}

@Composable
internal fun GroupIconBox(group: ChannelGroup, size: androidx.compose.ui.unit.Dp) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tokens.colors.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = groupIcon(group),
            contentDescription = null,
            tint = if (group.locked) tokens.colors.textMuted else tokens.colors.accent,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** An icon picked from the group's name (news, sports, kids…); a plain TV for anything else. */
internal fun groupIcon(group: ChannelGroup): ImageVector {
    if (group.locked) return Icons.Filled.Lock
    when (group.id) {
        ChannelGroup.FAVORITES -> return Icons.Filled.Star
        ChannelGroup.RECENT -> return Icons.Filled.History
        ChannelGroup.ALL -> return Icons.Filled.LiveTv
    }
    val t = group.title.lowercase()
    fun has(vararg words: String) = words.any { it in t }
    return when {
        has("news") -> Icons.Filled.Newspaper
        has("sport", "football", "soccer", "nfl", "nba", "nhl", "mlb", "espn", "golf", "tennis", "racing", "fight", "ufc", "boxing", "cricket", "rugby") -> Icons.Filled.SportsSoccer
        has("kid", "child", "cartoon", "family", "junior") -> Icons.Filled.ChildCare
        has("movie", "cinema", "film", "vod") -> Icons.Filled.Movie
        has("music", "radio", "mtv") -> Icons.Filled.MusicNote
        has("doc", "nature", "science", "history", "discovery", "learning", "travel") -> Icons.Filled.Explore
        has("local", "regional", "locals") -> Icons.Filled.Home
        has("entertain", "comedy", "drama", "series", "shows", "general", "variety", "lifestyle", "reality") -> Icons.Filled.TheaterComedy
        else -> Icons.Filled.LiveTv
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelRow(
    channel: LiveChannel,
    nowNext: NowNext?,
    nowMs: Long,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val showLogo = settings.phoneChannelLogos
    val showName = settings.phoneChannelNames
    val showNumber = settings.phoneChannelNumbers
    val logoOnly = showLogo && !showName && !showNumber
    val current = nowNext?.now
    val liveGames by SportsLive.byChannel.collectAsStateWithLifecycle()
    val sportsSettings by SportsRepository.settings.collectAsStateWithLifecycle()
    val game = liveGames[channel.key]?.takeIf { it.state == GameState.LIVE && !sportsSettings.hideScores && sportsSettings.scoreOnInfoBar }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.xl))
            .background(tokens.colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        // Favorites get a small star on the logo's corner (or on the number box when logos are off).
        val starOnTile = showLogo || (showNumber && !showName)
        if (starOnTile) {
            Box {
                if (showLogo) {
                    ChannelLogo(
                        channel,
                        width = if (logoOnly) 104.dp else 64.dp,
                        height = if (logoOnly) 64.dp else 44.dp,
                        inset = if (logoOnly) NuvioTokens.Space.s3 else NuvioTokens.Space.s4,
                    )
                } else {
                    // Numbers without names or logos: the number takes the logo's place.
                    Box(
                        modifier = Modifier.width(64.dp).height(44.dp).clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(channel.number.toString(), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                if (isFavorite) {
                    FavoriteBadge(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            val numberInLine = showNumber && (showName || showLogo)
            val starInLine = isFavorite && !starOnTile
            if (showName || numberInLine || starInLine) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                    if (numberInLine) {
                        Text(channel.number.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                    }
                    if (showName) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (starInLine) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(NuvioTokens.Icon.sm))
                    }
                }
            }
            if (game != null) {
                // The live score of the game this channel is showing (Sports): "DET 21 – 17 CHI · 3rd 4:12".
                val sides = game.ordered(com.nuvio.app.features.livetv.sports.SportsCatalog.league(game.leagueId)?.sport ?: com.nuvio.app.features.livetv.sports.Sport.OTHER)
                Text(
                    text = sides.joinToString("  –  ") { (it.team.abbr.ifBlank { it.team.name }) + " " + (it.score ?: "") } + (game.status?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.LiveNowLine,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current != null) {
                Text(
                    text = current.title + (current.episode?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (showName) tokens.colors.textSecondary else tokens.colors.textPrimary,
                    fontWeight = if (showName) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NuvioProgressBar(progress = current.progress(nowMs), height = NuvioTokens.Space.s3, fillColor = tokens.colors.accent)
                nowNext.next?.let { next ->
                    Text(
                        text = formatClock(next.startMs) + "  " + next.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(if (showName) channel.group else channel.name, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ChannelLogo(channel: LiveChannel, size: androidx.compose.ui.unit.Dp, show: Boolean = true) {
    ChannelLogo(channel, width = size, height = size, show = show)
}

/** The small favorite star that sits on the corner of a channel's logo (a dark circle so it shows on any logo). */
@Composable
internal fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.nuvio.colors.accent, modifier = Modifier.size(12.dp))
    }
}

/** The channel's logo in a rounded box (or its first letters when it has none). */
@Composable
internal fun ChannelLogo(
    channel: LiveChannel,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    show: Boolean = true,
    inset: androidx.compose.ui.unit.Dp = NuvioTokens.Space.s4,
    radius: androidx.compose.ui.unit.Dp = NuvioTokens.Radius.md,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(tokens.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        val logo = channel.logo?.takeIf { show && it.isNotBlank() }
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(inset),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = channel.name.trim().take(2).uppercase(),
                style = if (minOf(width, height) < 28.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** "20:30" or "8:30 PM", following the Live TV 24-hour setting when it's on. */
@Composable
internal fun formatClock(epochMs: Long): String {
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    return if (settings.use24HourClock) clock24(epochMs) else com.nuvio.app.core.format.formatLocalTime(epochMs)
}

internal fun clock24(epochMs: Long): String {
    val t = com.nuvio.app.features.livetv.data.CivilTime.at(epochMs, com.nuvio.app.features.livetv.data.LiveTvTimeZones.SYSTEM)
    return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}

internal val Color.Companion.LiveNowLine: Color get() = Color(0xFFE53935)

/**
 * What the channel list looked like when a channel was opened: which groups were open and
 * where the list was scrolled. The Live TV screen leaves composition while the player is up,
 * so this lives outside it; backing out of the player returns to the same group and spot.
 */
internal object LiveTvListMemory {
    var expanded: Set<String> = emptySet()
    var scrollIndex: Int = 0
        private set
    var scrollOffset: Int = 0
        private set

    fun saveScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }
}

/** A button in the Live TV header row: an icon with its short name under it. */
private class HeaderAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val tint: Color,
    val short: String,
    val showClose: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun HeaderButton(a: HeaderAction, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.heightIn(min = 52.dp).clickable(onClick = a.onClick).padding(vertical = NuvioTokens.Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (a.showClose) Icons.Rounded.Close else a.icon, contentDescription = a.label, tint = a.tint, modifier = Modifier.size(24.dp))
        Text(a.short, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (a.tint == tokens.colors.textPrimary) tokens.colors.textSecondary else a.tint, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}
