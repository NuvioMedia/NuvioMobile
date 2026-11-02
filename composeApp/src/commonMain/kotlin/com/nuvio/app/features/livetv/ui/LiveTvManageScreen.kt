package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.ChannelGroup
import com.nuvio.app.features.livetv.model.LiveChannel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_action_rename
import nuvio.composeapp.generated.resources.livetv_manage
import nuvio.composeapp.generated.resources.livetv_manage_channels
import nuvio.composeapp.generated.resources.livetv_manage_groups
import nuvio.composeapp.generated.resources.livetv_manage_hint
import nuvio.composeapp.generated.resources.livetv_manage_reset_order
import nuvio.composeapp.generated.resources.livetv_manage_show_all
import nuvio.composeapp.generated.resources.livetv_rename_hint
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Manage: groups (drag to reorder, rename, show or hide) and channels (pick a group; drag to
 * reorder, the eye shows or hides each one). Saved under the TV app's keys (group_order,
 * group_names, hidden_groups, channel_order, hidden_channels, favorites, custom_groups), so
 * the arrangement is the same on a TV restored from the same backup.
 */
@Composable
fun LiveTvManageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startWithChannels: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    var tab by rememberSaveable { mutableStateOf(if (startWithChannels) "channels" else "groups") }
    var menu by remember { mutableStateOf(false) }
    val channels by LiveTvRepository.displayChannels.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val playlists by LiveTvPreferences.playlists.collectAsStateWithLifecycle()
    // Everything, hidden and locked included: that's what's being managed.
    val all = remember(channels, user, settings, playlists) {
        LiveTvViewState.build(
            channels,
            user.copy(hiddenChannels = emptySet(), hiddenGroups = emptySet()),
            settings.copy(parentalPin = "", showFavoritesGroup = true, showRecentGroup = false),
            playlists.any { it.liveEnabled },
        )
    }
    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.livetv_manage),
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Edit, contentDescription = null, tint = tokens.colors.textPrimary) }
                Menu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(text = stringResource(Res.string.livetv_manage_reset_order), onClick = {
                        menu = false
                        if (tab == "groups") LiveTvPreferences.resetGroupEdits() else LiveTvPreferences.resetChannelOrder()
                    })
                    MenuItem(text = stringResource(Res.string.livetv_manage_show_all), onClick = {
                        menu = false
                        if (tab == "groups") LiveTvPreferences.clearHiddenGroups() else LiveTvPreferences.clearHiddenChannels()
                    })
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Chip(stringResource(Res.string.livetv_manage_groups), selected = tab == "groups", onClick = { tab = "groups" })
            Chip(stringResource(Res.string.livetv_manage_channels), selected = tab == "channels", onClick = { tab = "channels" })
        }
        Text(stringResource(Res.string.livetv_manage_hint), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4))
        if (tab == "groups") ManageGroups(all.sections.map { it.group }.filter { !it.special }, user.hiddenGroups)
        else ManageChannels(all, user.hiddenChannels)
    }
}

// ------------------------------------------------------------------ groups

@Composable
private fun ManageGroups(groups: List<ChannelGroup>, hidden: Set<String>) {
    val tokens = MaterialTheme.nuvio
    val haptics = LocalHapticFeedback.current
    // The list being dragged: a copy that follows the finger, saved when the drag ends.
    var order by remember(groups) { mutableStateOf(groups) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        val cur = order.toMutableList()
        val i = cur.indexOfFirst { it.id == from.key }
        val j = cur.indexOfFirst { it.id == to.key }
        if (i >= 0 && j >= 0) { cur.add(j, cur.removeAt(i)); order = cur; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    var renaming by remember { mutableStateOf<ChannelGroup?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        items(order, key = { it.id }) { g ->
            ReorderableItem(reorder, key = g.id) {
                val isHidden = g.id in hidden
                ManageRow(
                    title = g.title,
                    subtitle = listOfNotNull(if (g.id.startsWith(ChannelGroup.CUSTOM_PREFIX)) "Your group" else null, "${g.count} channels").joinToString(" · "),
                    hidden = isHidden,
                    onToggle = { LiveTvPreferences.setGroupHidden(g.id, !isHidden) },
                    onClick = { renaming = g },
                    handle = Modifier.draggableHandle(
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = { LiveTvPreferences.setGroupOrder(order.map { it.id }) },
                    ),
                )
            }
        }
    }
    renaming?.let { g ->
        var name by remember(g.id) { mutableStateOf(g.title) }
        DialogSurface(onDismissRequest = { renaming = null }, title = stringResource(Res.string.livetv_action_rename)) {
            NuvioInputField(value = name, onValueChange = { name = it }, placeholder = stringResource(Res.string.livetv_rename_hint))
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_cancel), onClick = { renaming = null })
                DialogButton(text = stringResource(Res.string.action_save), style = DialogButtonStyle.Primary, onClick = {
                    LiveTvPreferences.setGroupName(g.id, name.trim().takeIf { it.isNotBlank() && it != g.title })
                    renaming = null
                })
            }
        }
    }
}

// ------------------------------------------------------------------ channels

@Composable
private fun ManageChannels(all: LiveTvListState, hidden: Set<String>) {
    val tokens = MaterialTheme.nuvio
    val haptics = LocalHapticFeedback.current
    val groups = all.sections.map { it.group }.filter { it.id != ChannelGroup.RECENT }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = all.sections.firstOrNull { it.group.id == groupId } ?: all.sections.firstOrNull { it.group.id != ChannelGroup.RECENT }
    val source: List<LiveChannel> = selected?.channels.orEmpty()
    var order by remember(source) { mutableStateOf(source) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        val cur = order.toMutableList()
        val i = cur.indexOfFirst { it.key == from.key }
        val j = cur.indexOfFirst { it.key == to.key }
        if (i >= 0 && j >= 0) { cur.add(j, cur.removeAt(i)); order = cur; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    fun saveOrder() {
        val g = selected?.group ?: return
        val keys = order.map { it.key }
        when {
            g.id == ChannelGroup.FAVORITES -> LiveTvPreferences.setFavoritesOrder(keys)
            g.id.startsWith(ChannelGroup.CUSTOM_PREFIX) -> LiveTvPreferences.setCustomGroupOrder(g.id, keys)
            else -> LiveTvPreferences.setChannelOrder(g.id, keys)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            groups.forEach { g -> Chip(g.title, selected = selected?.group?.id == g.id, onClick = { groupId = g.id }) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            items(order, key = { it.key }) { c ->
                ReorderableItem(reorder, key = c.key) {
                    val isHidden = c.key in hidden
                    ManageRow(
                        title = c.name,
                        subtitle = c.group + "  ·  " + c.sourceName,
                        hidden = isHidden,
                        leading = { ChannelLogo(c, size = 36.dp, show = true) },
                        onToggle = { LiveTvPreferences.setChannelHidden(c.key, !isHidden) },
                        onClick = { LiveTvPreferences.setChannelHidden(c.key, !isHidden) },
                        handle = Modifier.draggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = { saveOrder() },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageRow(
    title: String,
    subtitle: String,
    hidden: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    handle: Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface).clickable(onClick = onClick)
            .padding(start = NuvioTokens.Space.s12, end = NuvioTokens.Space.s4, top = NuvioTokens.Space.s6, bottom = NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (hidden) tokens.colors.textMuted else tokens.colors.textPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggle) {
            Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null, tint = if (hidden) tokens.colors.textMuted else tokens.colors.accent)
        }
        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = tokens.colors.textMuted, modifier = handle.padding(NuvioTokens.Space.s8))
    }
}
