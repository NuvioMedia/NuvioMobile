package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.ondemand.OnDemandPlayback
import com.nuvio.app.features.livetv.ondemand.OnDemandRepository
import com.nuvio.app.features.livetv.ondemand.VodEpisode
import com.nuvio.app.features.livetv.ondemand.VodInfo
import com.nuvio.app.features.livetv.ondemand.VodItem
import com.nuvio.app.features.livetv.ondemand.VodKind
import com.nuvio.app.features.livetv.ondemand.VodTitles
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.livetv_vod_details
import nuvio.composeapp.generated.resources.livetv_vod_mark_unwatched
import nuvio.composeapp.generated.resources.livetv_vod_mark_watched
import nuvio.composeapp.generated.resources.livetv_vod_no_episodes
import nuvio.composeapp.generated.resources.livetv_vod_play_failed
import nuvio.composeapp.generated.resources.livetv_vod_season
import nuvio.composeapp.generated.resources.livetv_vod_versions
import org.jetbrains.compose.resources.stringResource

/**
 * A title's sheet: poster, what the provider says about it, Play (movies) or the seasons and
 * episodes (series), a watched mark, the other copies of it, and the app's own page when the
 * title was matched on TMDB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnDemandTitleSheet(item: VodItem, actions: OnDemandActions, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val match = rememberVodMatch(item)
    val info by produceState<VodInfo?>(null, item.uid) { value = runCatching { OnDemandRepository.info(item) }.getOrNull() }
    val watchedState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val profileId = ProfileRepository.activeProfileId
    var versions by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf<Int?>(null) }
    val playFailed = stringResource(Res.string.livetv_vod_play_failed)
    fun close(then: () -> Unit = {}) {
        scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss(); then() } }
    }
    fun play(target: VodItem, episode: VodEpisode?) {
        val launch = OnDemandPlayback.launch(target, episode, match, profileId)
        if (launch == null) NuvioToastController.show(playFailed) else close { actions.onPlay(launch) }
    }

    val title = match?.title ?: item.displayTitle
    val poster = match?.poster?.takeIf { it.isNotBlank() } ?: info?.cover ?: item.icon
    val watched = remember(item.uid, match, watchedState.watchedKeys) { OnDemandPlayback.isWatched(item, match) }
    val episodes = info?.episodes.orEmpty()
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val shownSeason = season ?: seasons.firstOrNull()

    if (!versions) {
        NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Box(
                    modifier = Modifier.width(84.dp).height(126.dp).clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard),
                ) {
                    if (poster != null) AsyncImage(model = poster, contentDescription = null, modifier = Modifier.size(84.dp, 126.dp), contentScale = ContentScale.Crop)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        item.year?.toString(),
                        item.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ ${((it * 10).toInt() / 10.0)}" },
                        VodTitles.qualityOf(item.name),
                        info?.durationText,
                        OnDemandRepository.playlist(item.playlistId)?.name,
                    ).joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    (info?.genre ?: item.genre)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    info?.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                }
            }
            NuvioBottomSheetDivider()
            if (item.kind == VodKind.MOVIE) {
                NuvioBottomSheetActionRow(title = stringResource(Res.string.action_play), icon = Icons.Filled.PlayArrow, onClick = { play(item, null) })
            }
            NuvioBottomSheetActionRow(
                title = stringResource(if (watched) Res.string.livetv_vod_mark_unwatched else Res.string.livetv_vod_mark_watched),
                icon = if (watched) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                onClick = { OnDemandPlayback.toggleWatched(item, match, null, LiveTvClock.nowMs()) },
            )
            val tmdbId = match?.tmdbId ?: info?.tmdbId ?: item.tmdbId
            if (!tmdbId.isNullOrBlank()) {
                NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_vod_details), icon = Icons.Filled.Info, onClick = {
                    close { actions.onOpenDetails(item.kind.key, tmdbId, title) }
                })
            }
            if (item.copies > 1 && item.kind == VodKind.MOVIE) {
                NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_vod_versions), icon = Icons.Filled.Layers, onClick = { versions = true })
            }
            if (item.kind == VodKind.SERIES) {
                NuvioBottomSheetDivider()
                when {
                    info == null -> Box(modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s16), contentAlignment = Alignment.Center) { NuvioLoadingIndicator() }
                    episodes.isEmpty() -> Text(stringResource(Res.string.livetv_vod_no_episodes), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16))
                    else -> {
                        if (seasons.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
                                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                            ) {
                                seasons.forEach { s -> Chip(label = stringResource(Res.string.livetv_vod_season, s), selected = s == shownSeason, onClick = { season = s }) }
                            }
                        }
                        val shown = episodes.filter { it.season == shownSeason }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                            items(shown.size, key = { shown[it].id + ":" + shown[it].season + ":" + shown[it].episode }) { i ->
                                val e = shown[i]
                                val seen = remember(e, match, watchedState.watchedKeys) { OnDemandPlayback.isWatched(item, match, e) }
                                EpisodeRow(e, seen, onPlay = { play(item, e) }, onToggleWatched = { OnDemandPlayback.toggleWatched(item, match, e, LiveTvClock.nowMs()) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(NuvioTokens.Space.s12))
        }
    } else {
        VersionsDialog(item, onDismiss = { versions = false }, onPick = { v -> versions = false; play(v, null) })
    }
}

@Composable
private fun EpisodeRow(e: VodEpisode, watched: Boolean, onPlay: () -> Unit, onToggleWatched: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Box(modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard)) {
            e.image?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(96.dp, 54.dp), contentScale = ContentScale.Crop) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("E${e.episode}  ${e.title}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(e.durationSecs?.let { "${it / 60} min" }, e.plot).joinToString("  ·  ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            imageVector = if (watched) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (watched) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(NuvioTokens.Icon.md).clickable(onClick = onToggleWatched),
        )
    }
}

@Composable
private fun VersionsDialog(item: VodItem, onDismiss: () -> Unit, onPick: (VodItem) -> Unit) {
    val versions by produceState(emptyList<OnDemandRepository.Version>(), item.uid) { value = OnDemandRepository.versions(item) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_vod_versions)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            if (versions.isEmpty()) NuvioLoadingIndicator(modifier = Modifier.padding(NuvioTokens.Space.s12))
            versions.forEach { v ->
                DialogOption(text = v.label, description = v.detail.ifBlank { null }, selected = v.item.uid == item.uid, onClick = { onPick(v.item) })
            }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}
