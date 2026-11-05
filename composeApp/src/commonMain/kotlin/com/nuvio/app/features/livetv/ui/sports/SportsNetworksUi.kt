package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.LeagueNetworks
import com.nuvio.app.features.livetv.sports.NetworkTile
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.clockText
import com.nuvio.app.features.livetv.ui.ChannelLogo

/**
 * Networks: each league's own channel (NFL Network, NBA TV, Golf Channel…), found in your
 * playlists by name. Tap plays it; long-press for the other copies or to choose a different one.
 */
@Composable
internal fun SportsNetworksTab(actions: SportsActions) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val found by repo.leagueNetworks.collectAsStateWithLifecycle()
    val use24h = use24h()
    val byKey = remember(found) { found.values.flatten().associateBy { it.key } }
    val tiles = remember(found, settings, byKey) { LeagueNetworks.tiles(found, settings, byKey) }
    val leadKeys = remember(tiles) { tiles.mapNotNull { it.lead?.key } }
    val onNow by produceState<Map<String, com.nuvio.app.features.livetv.data.NowNext>>(emptyMap(), leadKeys) {
        while (true) {
            value = runCatching { repo.nowAndNext(leadKeys) }.getOrDefault(emptyMap())
            kotlinx.coroutines.delay(60_000)
        }
    }
    var menu by remember { mutableStateOf<NetworkTile?>(null) }
    var choosing by remember { mutableStateOf<NetworkTile?>(null) }
    val groups = remember(tiles) { tiles.groupBy { it.network.sport } }

    BoxWithConstraints {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, top = NuvioTokens.Space.s4, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            item(key = "intro") {
                Text("Each league's own channel, found in your playlists by name.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
            groups.forEach { (sport, list) ->
                item(key = "h_$sport") {
                    Text(
                        sport.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = NuvioTokens.Space.s8),
                    )
                }
                items(list.chunked(columns), key = { row -> row.first().network.id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { tile ->
                            NetworkCard(
                                tile = tile,
                                nowText = nowText(tile, onNow, use24h),
                                live = tile.lead?.let { onNow[it.key]?.now } != null,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val lead = tile.lead
                                    if (lead == null) choosing = tile
                                    else actions.onPlay(lead, onNow[lead.key]?.now?.title)
                                },
                                onLongClick = { menu = tile },
                            )
                        }
                        if (row.size < columns) Spacer(Modifier.weight((columns - row.size).toFloat()))
                    }
                }
            }
        }
    }

    menu?.let { tile ->
        NetworkMenuSheet(tile, onNow, actions, onChoose = { menu = null; choosing = tile }, onDismiss = { menu = null })
    }
    choosing?.let { tile ->
        ChannelPickerDialog(
            title = tile.network.name,
            onPick = { ch ->
                choosing = null
                SportsRepository.pickLeagueNetwork(tile.network.id, ch.key)
                NuvioToastController.show("Saved: ${tile.network.name} uses ${ch.name}")
            },
            onDismiss = { choosing = null },
        )
    }
}

private fun nowText(tile: NetworkTile, onNow: Map<String, com.nuvio.app.features.livetv.data.NowNext>, use24h: Boolean): String {
    val lead = tile.lead ?: return "Not found. Choose a channel"
    val nn = onNow[lead.key] ?: return "No guide info"
    nn.now?.let { return it.title }
    nn.next?.let { return "Next: " + clockText(it.startMs, use24h) }
    return "No guide info"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    tile: NetworkTile,
    nowText: String,
    live: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val found = tile.lead != null
    Row(
        modifier = modifier
            .alpha(if (found) 1f else 0.55f)
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceCard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        if (tile.lead != null) {
            ChannelLogo(tile.lead, size = 44.dp)
        } else {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(Color(tile.network.color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(tile.network.mark, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                Text(tile.network.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tile.others.isNotEmpty()) {
                    Text("+${tile.others.size} more", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE53935)))
                Text(nowText, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkMenuSheet(
    tile: NetworkTile,
    onNow: Map<String, com.nuvio.app.features.livetv.data.NowNext>,
    actions: SportsActions,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            tile.network.name,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
        )
        tile.lead?.let { lead ->
            NuvioBottomSheetActionRow(title = "Watch", icon = Icons.Filled.PlayArrow, onClick = { onDismiss(); actions.onPlay(lead, onNow[lead.key]?.now?.title) })
        }
        tile.others.forEach { ch ->
            NuvioBottomSheetActionRow(title = ch.name, icon = Icons.Filled.PlayArrow, onClick = { onDismiss(); actions.onPlay(ch, null) })
        }
        NuvioBottomSheetActionRow(title = "Choose a different channel", icon = Icons.Filled.Tune, onClick = onChoose)
        if (tile.picked) {
            NuvioBottomSheetActionRow(title = "Back to automatic", icon = Icons.Filled.Replay, onClick = {
                onDismiss(); SportsRepository.resetLeagueNetwork(tile.network.id); NuvioToastController.show("${tile.network.name} is automatic again")
            })
        }
        Spacer(Modifier.height(NuvioTokens.Space.s12))
    }
}
