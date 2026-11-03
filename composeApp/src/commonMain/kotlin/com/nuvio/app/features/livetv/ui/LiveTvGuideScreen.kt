package com.nuvio.app.features.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioProgressBar
import nuvio.composeapp.generated.resources.livetv_search_hint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.ChannelGroup
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.reminders.LiveTvReminders
import com.nuvio.app.features.livetv.model.Reminder
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.livetv_guide
import nuvio.composeapp.generated.resources.livetv_guide_all
import nuvio.composeapp.generated.resources.livetv_guide_no_info
import nuvio.composeapp.generated.resources.livetv_guide_now
import nuvio.composeapp.generated.resources.livetv_rec_cancel
import nuvio.composeapp.generated.resources.livetv_rec_record
import nuvio.composeapp.generated.resources.livetv_rec_series
import nuvio.composeapp.generated.resources.livetv_replay
import nuvio.composeapp.generated.resources.livetv_remind_me
import nuvio.composeapp.generated.resources.livetv_reminder_set
import nuvio.composeapp.generated.resources.livetv_watch_from_start
import org.jetbrains.compose.resources.stringResource

private val ChannelColumnWidth = 92.dp
private val RowHeight = 64.dp
private val RowGap = 6.dp
private val HourWidth = 240.dp
private const val HOUR_MS = 60L * 60L * 1000L
private const val SLOT_MS = 30L * 60L * 1000L

/**
 * The TV guide: a time grid, channels down the side, hours across. Tap a show for its details,
 * a channel's logo to play it; "Now" jumps the grid back to the current time.
 */
@Composable
fun LiveTvGuideScreen(
    onBack: () -> Unit,
    onPlayChannel: (LiveChannel, nowTitle: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Replay a show from the provider's catch-up (a past show, or the one on now from its start). */
    onPlayCatchup: (LiveChannel, EpgProgram) -> Unit = { _, _ -> },
) {
    val tokens = MaterialTheme.nuvio
    val state by remember {
        LiveTvRepository.ensureLoaded()
        LiveTvViewState.list
    }.collectAsStateWithLifecycle()
    val generation by LiveTvRepository.guideGeneration.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val now by produceState(LiveTvClock.nowMs()) {
        while (true) { value = LiveTvClock.nowMs(); delay(30_000) }
    }
    val playlists by LiveTvPreferences.playlists.collectAsStateWithLifecycle()
    val savedPlaylist by PlaylistFilter.selected.collectAsStateWithLifecycle()
    val allSections = state.sections.filter { it.channels.isNotEmpty() }
    val playlistChoices = remember(allSections, playlists) {
        playlists.mapNotNull { p ->
            val n = allSections.count { it.group.sourceId == p.id }
            if (n > 0) PlaylistChoice(p.id, p.name, if (n == 1) "1 group" else "$n groups") else null
        }
    }
    val playlistId = PlaylistFilter.effective(savedPlaylist, playlistChoices.map { it.id })
    val sections = if (playlistId == null) allSections else allSections.filter { it.group.sourceId == null || it.group.sourceId == playlistId }
    // Saved, so backing out of a channel returns to the same group and spot in the guide.
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = sections.firstOrNull { it.group.id == selectedGroupId } ?: sections.firstOrNull()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val playlistChannels = state.visible.filter { playlistId == null || it.sourceId == playlistId }
    val channels = when {
        searching && query.isNotBlank() -> remember(playlistChannels, query) { LiveTvViewState.search(playlistChannels, query) }
        selectedGroupId == ChannelGroup.ALL -> playlistChannels
        else -> selected?.channels.orEmpty()
    }

    // The grid goes back as far as the "Guide hours back" setting (so catch-up shows can be
    // picked from it) and 24 hours ahead of now.
    val pastHours = settings.epgPastHours.coerceAtLeast(1)
    val gridStart = remember(now / HOUR_MS, pastHours) { (now / SLOT_MS) * SLOT_MS - pastHours * HOUR_MS }
    val gridEnd = (now / SLOT_MS) * SLOT_MS + 24 * HOUR_MS
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var programs by remember { mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap()) }
    LaunchedEffect(channels, generation, gridStart) {
        val keys = channels.map { it.key }
        // The visible group in chunks, so a big group fills in as it's read.
        val out = HashMap<String, List<EpgProgram>>()
        keys.chunked(150).forEach { part ->
            out += LiveTvRepository.programsIn(part, gridStart, gridEnd)
            programs = HashMap(out)
        }
    }
    var detail by remember { mutableStateOf<Pair<LiveChannel, EpgProgram>?>(null) }

    fun pxPerMs(): Float = with(density) { HourWidth.toPx() } / HOUR_MS
    fun jumpToNow() {
        scope.launch {
            val target = ((now - gridStart - 15 * 60_000L).coerceAtLeast(0) * pxPerMs()).toInt()
            hScroll.animateScrollTo(target)
        }
    }
    // Jump to now only on first open; coming back from a channel keeps the guide where it was.
    var jumpedToNow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!jumpedToNow) { jumpedToNow = true; jumpToNow() } }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.livetv_guide),
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                GuideRoundButton(
                    icon = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    description = stringResource(Res.string.livetv_search_hint),
                    onClick = { searching = !searching; if (!searching) query = "" },
                )
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                GuideRoundButton(icon = Icons.Filled.Schedule, description = stringResource(Res.string.livetv_guide_now), onClick = ::jumpToNow)
            },
        )
        AnimatedVisibility(visible = searching) {
            NuvioInputField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(Res.string.livetv_search_hint),
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s4),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            val searchingNow = searching && query.isNotBlank()
            sections.forEach { s ->
                GroupPill(s.group, selected = !searchingNow && selected?.group?.id == s.group.id && selectedGroupId != ChannelGroup.ALL) {
                    selectedGroupId = s.group.id; searching = false; query = ""
                }
            }
            GroupPill(
                ChannelGroup(ChannelGroup.ALL, stringResource(Res.string.livetv_guide_all), 0, special = true),
                selected = !searchingNow && selectedGroupId == ChannelGroup.ALL,
            ) { selectedGroupId = ChannelGroup.ALL; searching = false; query = "" }
        }
        // Time ruler, with the playlist menu in the corner above the channel column.
        Row(modifier = Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(ChannelColumnWidth).padding(start = NuvioTokens.Space.s8, end = NuvioTokens.Space.s4)) {
                PlaylistDropdown(
                    choices = playlistChoices,
                    selectedId = playlistId,
                    allDetail = allSections.count { it.group.sourceId != null }.let { if (it == 1) "1 group" else "$it groups" },
                    onSelect = PlaylistFilter::select,
                    compact = true,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll, enabled = false)) {
                Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    var t = gridStart
                    while (t < gridEnd) {
                        val current = now >= t && now < t + SLOT_MS
                        Text(
                            text = formatClock(t),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (current) tokens.colors.textPrimary else tokens.colors.textMuted,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.width(HourWidth / 2).padding(start = NuvioTokens.Space.s6),
                        )
                        t += SLOT_MS
                    }
                }
                NowLine(gridStart, now, ::pxPerMs, height = 34.dp)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = NuvioTokens.Space.s2, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        ) {
            items(channels, key = { it.key }) { channel ->
                GuideRow(
                    channel = channel,
                    isFavorite = channel.key in state.favoriteKeys,
                    programs = programs[channel.key].orEmpty(),
                    gridStart = gridStart,
                    gridEnd = gridEnd,
                    now = now,
                    hScroll = hScroll,
                    pxPerMs = ::pxPerMs,
                    onPlay = { onPlayChannel(channel, programs[channel.key]?.firstOrNull { it.isLive(now) }?.title) },
                    onProgram = { detail = channel to it },
                )
            }
        }
    }

    detail?.let { (channel, program) ->
        ProgramDetailsDialog(
            channel, program, now,
            onDismiss = { detail = null },
            onPlay = { detail = null; onPlayChannel(channel, program.title.takeIf { program.isLive(now) }) },
            onReplay = if (LiveTvCatchup.isReplayable(channel, program, now)) ({ detail = null; onPlayCatchup(channel, program) }) else null,
        )
    }
}

@Composable
private fun NowLine(gridStart: Long, now: Long, pxPerMs: () -> Float, height: Dp) {
    // Settings > Live TV > Channel Display > Now line (shared with the TV app).
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    if (!settings.showNowLine) return
    val density = LocalDensity.current
    val x = with(density) { ((now - gridStart) * pxPerMs()).toDp() }
    if (x > 0.dp) {
        Box(modifier = Modifier.offset(x = x).width(2.dp).height(height).background(MaterialTheme.nuvio.colors.accent))
    }
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    isFavorite: Boolean,
    programs: List<EpgProgram>,
    gridStart: Long,
    gridEnd: Long,
    now: Long,
    hScroll: androidx.compose.foundation.ScrollState,
    pxPerMs: () -> Float,
    onPlay: () -> Unit,
    onProgram: (EpgProgram) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val density = LocalDensity.current
    Row(modifier = Modifier.fillMaxWidth().height(RowHeight)) {
        GuideChannelTile(
            channel = channel,
            isFavorite = isFavorite,
            onPlay = onPlay,
            modifier = Modifier.width(ChannelColumnWidth).fillMaxHeight().padding(start = NuvioTokens.Space.s8, end = NuvioTokens.Space.s6),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            val totalWidth = with(density) { ((gridEnd - gridStart) * pxPerMs()).toDp() }
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                if (programs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(end = NuvioTokens.Space.s4).clip(RoundedCornerShape(NuvioTokens.Radius.lg)).background(tokens.colors.surface.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(stringResource(Res.string.livetv_guide_no_info), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, modifier = Modifier.padding(start = NuvioTokens.Space.s12))
                    }
                }
                programs.forEach { p ->
                    val start = p.startMs.coerceAtLeast(gridStart)
                    val stop = p.stopMs.coerceAtMost(gridEnd)
                    if (stop <= start) return@forEach
                    val x = with(density) { ((start - gridStart) * pxPerMs()).toDp() }
                    val w = with(density) { ((stop - start) * pxPerMs()).toDp() } - NuvioTokens.Space.s4
                    if (w <= 0.dp) return@forEach
                    val live = p.isLive(now)
                    val replayable = !live && LiveTvCatchup.isReplayable(channel, p, now)
                    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
                    Box(
                        modifier = Modifier
                            .offset(x = x)
                            .width(w)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(if (live) tokens.colors.accent.copy(alpha = 0.22f).compositeOver(tokens.colors.surfaceCard) else tokens.colors.surface)
                            .border(1.dp, if (live) tokens.colors.accent.copy(alpha = 0.4f) else tokens.colors.textPrimary.copy(alpha = 0.06f), shape)
                            .clickable { onProgram(p) },
                    ) {
                        Column(modifier = Modifier.padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s6)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                                if (replayable) {
                                    Icon(Icons.Filled.Replay, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.width(12.dp).height(12.dp))
                                }
                                Text(
                                    text = p.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.colors.textPrimary,
                                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (w > 50.dp) {
                                Text(
                                    text = if (w > 120.dp) formatClock(p.startMs) + " – " + formatClock(p.stopMs) else formatClock(p.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (live && w > 70.dp) {
                            NuvioProgressBar(
                                progress = p.progress(now),
                                height = NuvioTokens.Space.s3,
                                fillColor = tokens.colors.accent,
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = NuvioTokens.Space.s10, end = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s6),
                            )
                        }
                    }
                }
                NowLine(gridStart, now, pxPerMs, height = RowHeight)
            }
        }
    }
}

/** The channel's tile in the guide: logo, name and number, as the Channel Display settings say. */
@Composable
private fun GuideChannelTile(channel: LiveChannel, isFavorite: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val showLogo = settings.phoneChannelLogos
    val showName = settings.phoneChannelNames
    val showNumber = settings.phoneChannelNumbers
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.textPrimary.copy(alpha = 0.06f), shape)
                .clickable(onClick = onPlay)
                .padding(NuvioTokens.Space.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                // Only logos: the logo fills the tile.
                showLogo && !showName && !showNumber -> LogoFill(channel, Modifier.fillMaxSize())
                showLogo -> {
                    LogoFill(channel, Modifier.fillMaxWidth().weight(1f))
                    val line = listOfNotNull(
                        channel.number.toString().takeIf { showNumber },
                        channel.name.takeIf { showName },
                    ).joinToString("  ")
                    Text(line, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    if (showNumber) {
                        Text(
                            channel.number.toString(),
                            style = if (showName) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                            color = if (showName) tokens.colors.textMuted else tokens.colors.textPrimary,
                            fontWeight = if (showName) FontWeight.Normal else FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    if (showName) {
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = if (showNumber) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Favorites: a small star on the tile's corner.
        if (isFavorite) {
            FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s3))
        }
    }
}

@Composable
private fun LogoFill(channel: LiveChannel, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val logo = channel.logo?.takeIf { it.isNotBlank() }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = channel.name, modifier = Modifier.fillMaxSize().padding(NuvioTokens.Space.s2), contentScale = ContentScale.Fit)
        } else {
            Text(channel.name.trim().take(3).uppercase(), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textMuted, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A group in the guide's top row (its name as written in the playlist): filled with the theme colour when picked. */
@Composable
private fun GroupPill(group: ChannelGroup, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.full)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .border(1.dp, if (selected) tokens.colors.accent else tokens.colors.textPrimary.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        val color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary
        Text(group.title, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun GuideRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).background(tokens.colors.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tokens.colors.textPrimary, modifier = Modifier.size(NuvioTokens.Icon.md))
    }
}

@Composable
internal fun ProgramDetailsDialog(
    channel: LiveChannel,
    program: EpgProgram,
    now: Long,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    /** Set when the show can be replayed from catch-up. */
    onReplay: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    var full by remember { mutableStateOf(program) }
    LaunchedEffect(program) {
        LiveTvRepository.programDetails(channel.key, program.startMs)?.let { full = it }
    }
    val schedules by remember { LiveTvRecordingScheduler.start(); LiveTvRecordingScheduler.schedules }.collectAsStateWithLifecycle()
    val scheduled = remember(schedules, program) { LiveTvRecordingScheduler.isScheduled(channel.key, program) }
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val reminded = remember(user.reminders, program) { user.reminders.any { it.channelKey == channel.key && it.startMs == program.startMs } }
    val upcoming = program.startMs > now
    // Record: a show still to come or on now; a past show too when the channel keeps it in catch-up.
    val recordable = !LiveTvRepository.isPlaceholderTitle(program.title) && (program.stopMs > now || LiveTvCatchup.isReplayable(channel, program, now))
    DialogSurface(onDismissRequest = onDismiss, title = full.title) {
        Text(
            text = channel.name + "  ·  " + formatClock(full.startMs) + " – " + formatClock(full.stopMs) + (full.episode?.let { "  ·  $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        if (recordable || upcoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (upcoming) {
                    Chip(
                        label = stringResource(if (reminded) Res.string.livetv_reminder_set else Res.string.livetv_remind_me),
                        selected = reminded,
                        onClick = {
                            LiveTvReminders.start()
                            LiveTvReminders.toggle(Reminder(channel.key, channel.name, full.title, program.startMs))
                        },
                    )
                }
                if (recordable) Chip(
                    label = stringResource(if (scheduled) Res.string.livetv_rec_cancel else Res.string.livetv_rec_record),
                    selected = scheduled,
                    onClick = {
                        if (scheduled) LiveTvRecordingScheduler.cancel(channel.key, program.startMs)
                        else NuvioToastController.show(LiveTvRecordingScheduler.add(channel, full))
                        onDismiss()
                    },
                )
                val series = LiveTvRecordingScheduler.seriesFor(channel.key, program.title)
                if (recordable) Chip(
                    label = stringResource(Res.string.livetv_rec_series),
                    selected = series != null,
                    onClick = {
                        if (series != null) LiveTvRecordingScheduler.removeSeries(series.id)
                        else NuvioToastController.show(LiveTvRecordingScheduler.addSeries(channel, full))
                        onDismiss()
                    },
                )
            }
        }
        full.category?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted) }
        full.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
        if (full.people.isNotEmpty()) {
            Text(full.people.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
            if (onReplay != null) {
                DialogButton(
                    text = stringResource(if (program.isLive(now)) Res.string.livetv_watch_from_start else Res.string.livetv_replay),
                    style = if (program.isLive(now)) DialogButtonStyle.Secondary else DialogButtonStyle.Primary,
                    onClick = onReplay,
                )
            }
            if (program.isLive(now)) {
                DialogButton(text = stringResource(Res.string.action_play), style = DialogButtonStyle.Primary, onClick = onPlay)
            }
        }
    }
}
