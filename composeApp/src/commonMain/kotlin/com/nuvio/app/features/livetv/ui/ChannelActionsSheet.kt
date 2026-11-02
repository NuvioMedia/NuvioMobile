package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.CustomGroup
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_action_add_favorite
import nuvio.composeapp.generated.resources.livetv_action_assign_epg
import nuvio.composeapp.generated.resources.livetv_action_hide
import nuvio.composeapp.generated.resources.livetv_action_move_to_group
import nuvio.composeapp.generated.resources.livetv_action_remove_favorite
import nuvio.composeapp.generated.resources.livetv_action_rename
import nuvio.composeapp.generated.resources.livetv_epg_automatic
import nuvio.composeapp.generated.resources.livetv_epg_none
import nuvio.composeapp.generated.resources.livetv_epg_search_hint
import nuvio.composeapp.generated.resources.livetv_group_new
import nuvio.composeapp.generated.resources.livetv_group_new_hint
import nuvio.composeapp.generated.resources.livetv_rename_hint
import nuvio.composeapp.generated.resources.livetv_watch_from_start
import org.jetbrains.compose.resources.stringResource

/** Long-press options for a channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelActionsSheet(
    channel: LiveChannel,
    isFavorite: Boolean,
    customGroups: List<CustomGroup>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    /** Set when the show on now can be restarted from its beginning (catch-up). */
    onWatchFromStart: (() -> Unit)? = null,
    /** More rows, after Play (recordings and the like). */
    extraRows: @Composable (close: (() -> Unit) -> Unit) -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<ChannelDialog?>(null) }
    fun close(then: () -> Unit = {}) {
        scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss(); then() } }
    }

    if (dialog == null) {
        NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                ChannelLogo(channel, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel.group + "  ·  " + channel.sourceName, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(title = stringResource(Res.string.action_play), icon = Icons.Filled.PlayArrow, onClick = { close(onPlay) })
            if (onWatchFromStart != null) {
                NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_watch_from_start), icon = Icons.Filled.Replay, onClick = { close(onWatchFromStart) })
            }
            extraRows { then -> close(then) }
            NuvioBottomSheetActionRow(
                title = stringResource(if (isFavorite) Res.string.livetv_action_remove_favorite else Res.string.livetv_action_add_favorite),
                icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                onClick = { LiveTvPreferences.toggleFavorite(channel.key); close() },
            )
            NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_action_rename), icon = Icons.Filled.Edit, onClick = { dialog = ChannelDialog.Rename })
            NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_action_assign_epg), icon = Icons.Filled.Schedule, onClick = { dialog = ChannelDialog.AssignEpg })
            NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_action_move_to_group), icon = Icons.Filled.FolderOpen, onClick = { dialog = ChannelDialog.MoveToGroup })
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.livetv_action_hide),
                icon = Icons.Filled.VisibilityOff,
                onClick = { LiveTvPreferences.setChannelHidden(channel.key, true); close() },
            )
            Spacer(Modifier.height(NuvioTokens.Space.s12))
        }
    }

    when (dialog) {
        ChannelDialog.Rename -> RenameChannelDialog(channel, onDismiss = { dialog = null; onDismiss() })
        ChannelDialog.AssignEpg -> AssignEpgDialog(channel, onDismiss = { dialog = null; onDismiss() })
        ChannelDialog.MoveToGroup -> MoveToGroupDialog(channel, customGroups, onDismiss = { dialog = null; onDismiss() })
        null -> Unit
    }
}

private enum class ChannelDialog { Rename, AssignEpg, MoveToGroup }

@Composable
private fun RenameChannelDialog(channel: LiveChannel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(channel.name) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_action_rename)) {
        NuvioInputField(value = name, onValueChange = { name = it }, placeholder = stringResource(Res.string.livetv_rename_hint))
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    LiveTvPreferences.setChannelName(channel.key, name.trim().takeIf { it.isNotBlank() && it != channel.name })
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun MoveToGroupDialog(channel: LiveChannel, customGroups: List<CustomGroup>, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var newName by remember { mutableStateOf("") }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_action_move_to_group)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            customGroups.forEach { g ->
                val inGroup = channel.key in g.channelKeys
                DialogOption(
                    text = g.name,
                    selected = inGroup,
                    onClick = {
                        if (inGroup) LiveTvPreferences.removeFromCustomGroup(g.id, channel.key)
                        else LiveTvPreferences.addToCustomGroup(g.id, channel.key)
                        onDismiss()
                    },
                )
            }
            if (customGroups.isEmpty()) {
                Text(stringResource(Res.string.livetv_group_new_hint), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        NuvioInputField(value = newName, onValueChange = { newName = it }, placeholder = stringResource(Res.string.livetv_group_new))
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.livetv_group_new),
                style = DialogButtonStyle.Primary,
                enabled = newName.isNotBlank(),
                onClick = {
                    val id = LiveTvPreferences.createCustomGroup(newName.trim())
                    LiveTvPreferences.addToCustomGroup(id, channel.key)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun AssignEpgDialog(channel: LiveChannel, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sources by LiveTvRepository.epgSources.collectAsStateWithLifecycle()
    val loading by LiveTvRepository.epgListsLoading.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val auto by LiveTvRepository.autoMatches.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LiveTvRepository.ensureSourceLists() }
    var query by remember { mutableStateOf("") }
    val current = user.epgOverrides[channel.key]
    val matched = auto[channel.key]
    val entries = remember(sources, query) {
        val q = query.trim().lowercase()
        sources.flatMap { src ->
            src.channels.asSequence()
                .filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.id.lowercase().contains(q) }
                .take(if (q.isEmpty()) 40 else 200)
                .map { src to it }
                .toList()
        }
    }
    fun pick(a: EpgAssignment?) {
        LiveTvRepository.applyEpgChanges(mapOf(channel.key to a))
        onDismiss()
    }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_action_assign_epg)) {
        NuvioInputField(value = query, onValueChange = { query = it }, placeholder = stringResource(Res.string.livetv_epg_search_hint))
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            item {
                DialogOption(
                    text = stringResource(Res.string.livetv_epg_automatic),
                    description = matched?.let { "${it.xmltvId}" },
                    selected = current == null,
                    onClick = { pick(null) },
                )
            }
            item {
                DialogOption(text = stringResource(Res.string.livetv_epg_none), selected = current?.isNone == true, onClick = { pick(EpgAssignment.None) })
            }
            if (loading && sources.isEmpty()) {
                item { NuvioLoadingIndicator(modifier = Modifier.padding(NuvioTokens.Space.s12)) }
            }
            items(entries, key = { (src, e) -> src.sourceId + "\u0000" + e.id }) { (src, e) ->
                val selected = current?.sourceId == src.sourceId && current.xmltvId == e.id
                DialogOption(
                    text = e.displayName,
                    description = e.id + "  ·  " + src.label,
                    selected = selected,
                    onClick = { pick(EpgAssignment(src.sourceId, e.id)) },
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
    }
}
