package com.nuvio.app.features.shuffle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.rememberPlaybackAvailability
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeShuffleSheet(
    meta: MetaDetails,
    settings: EpisodeShuffleSettings,
    onSave: (EpisodeShuffleSettings) -> Boolean,
    watchedKeys: Set<String>,
    progressEntries: List<WatchProgressEntry>,
    blurUnwatchedEpisodes: Boolean,
    onDismiss: () -> Unit,
    onPlay: (MetaVideo) -> Unit,
    onPlayManually: ((MetaVideo) -> Unit)?,
    onStartFromBeginning: (MetaVideo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val availability = rememberPlaybackAvailability()
    val progress = remember(meta.id, progressEntries) { shuffleEpisodeProgress(meta.id, progressEntries) }
    val picker by produceState<RandomEpisodePicker?>(null, meta, watchedKeys, progress) {
        val updated = withContext(Dispatchers.Default) {
            RandomEpisodePicker(meta.id, meta.videos,
                watchedShuffleEpisodes(meta.id, meta.type, meta.videos, watchedKeys), progress)
        }
        updated.inheritHistoryFrom(value)
        value = updated
    }
    var includeWatched by remember { mutableStateOf(settings.includeWatched) }
    var selected by remember { mutableStateOf<MetaVideo?>(null) }
    var starting by remember { mutableStateOf(false) }
    val readyPicker = picker
    val preview = selected?.let { readyPicker?.find(it.id, includeWatched) }
    val allCount = readyPicker?.count(true) ?: 0
    val unwatchedCount = readyPicker?.count(false) ?: 0

    fun dismiss(after: () -> Unit = {}) {
        scope.launch {
            dismissNuvioBottomSheet(sheetState) { onDismiss(); after() }
        }
    }

    fun play(action: (MetaVideo) -> Unit) {
        val episode = preview ?: return
        if (starting) return
        starting = true
        if (onSave(EpisodeShuffleSettings(true, includeWatched))) dismiss { action(episode) } else starting = false
    }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!starting) dismiss() },
        sheetState = sheetState,
        fullHeight = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = nuvioSafeBottomPadding(20.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preview != null) {
                    TextButton(enabled = !starting, onClick = { selected = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                }
                Column {
                    Text(stringResource(Res.string.random_episode_title), style = MaterialTheme.typography.titleLarge)
                    Text(meta.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (preview == null) {
                Text(stringResource(Res.string.shuffle_choose_episodes), style = MaterialTheme.typography.bodyLarge)
                when {
                    readyPicker == null -> Text(stringResource(Res.string.random_episode_loading))
                    allCount == 0 -> Text(stringResource(Res.string.random_episode_empty_subtitle))
                    else -> {
                        for (include in listOf(false, true)) {
                            OutlinedButton(
                                onClick = { includeWatched = include; selected = readyPicker.pick(include) },
                                enabled = include || unwatchedCount > 0,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(if (include) Res.string.random_episode_include_watched else Res.string.random_episode_unwatched))
                            }
                        }
                        if (unwatchedCount == 0) Text(stringResource(Res.string.random_episode_caught_up),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val watched = readyPicker?.isWatched(preview) == true
                val hidden = blurUnwatchedEpisodes && !watched
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hidden && !preview.thumbnail.isNullOrBlank()) {
                        AsyncImage(model = preview.thumbnail, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (hidden) Icons.Default.VisibilityOff else Icons.Default.Shuffle,
                                contentDescription = null, modifier = Modifier.size(32.dp))
                            if (hidden) Text(stringResource(Res.string.shuffle_artwork_hidden), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        stringResource(if (watched) Res.string.episodes_cd_watched else Res.string.shuffle_unwatched),
                        style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Text(stringResource(if (includeWatched) Res.string.random_episode_include_watched else Res.string.random_episode_unwatched),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(Res.string.shuffle_preview_title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(localizedSeasonEpisodeCode(preview.season, preview.episode).orEmpty(),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(preview.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                preview.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val resume = progress[preview.season to preview.episode]?.let {
                    !it.isEffectivelyCompleted && (it.lastPositionMs > 0 || it.progressFraction > 0)
                } == true
                val canPlay = availability.canPlay(meta.type, preview.id, meta.id, preview.season, preview.episode)
                NuvioPrimaryButton(
                    text = stringResource(when {
                        starting -> Res.string.shuffle_starting
                        !canPlay -> Res.string.playback_unavailable
                        resume -> Res.string.action_resume
                        else -> Res.string.shuffle_play_episode
                    }), enabled = !starting && canPlay, onClick = { play(onPlay) },
                )
                if ((readyPicker?.count(includeWatched) ?: 0) > 1) {
                    OutlinedButton(enabled = !starting, onClick = { selected = readyPicker?.pick(includeWatched) },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.shuffle_again), modifier = Modifier.padding(start = 8.dp))
                    }
                } else Text(stringResource(Res.string.shuffle_only_episode), style = MaterialTheme.typography.bodySmall)
                if (onPlayManually != null && availability.canStream(meta.type, preview.id)) {
                    TextButton(enabled = !starting, onClick = { play(onPlayManually) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.play_manually))
                    }
                }
                if (resume) TextButton(enabled = !starting && canPlay, onClick = { play(onStartFromBeginning) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.cw_action_start_from_beginning))
                }
            }
        }
    }
}

@Composable
internal fun rememberShuffleSave(
    contentId: String,
    profileId: Int,
    current: EpisodeShuffleSettings,
): (EpisodeShuffleSettings) -> Boolean {
    val off = stringResource(Res.string.shuffle_disabled)
    val all = stringResource(Res.string.shuffle_enabled_all)
    val unwatched = stringResource(Res.string.shuffle_enabled_unwatched)
    val failed = stringResource(Res.string.shuffle_save_failed)
    return remember(contentId, profileId, current, off, all, unwatched, failed) {
        { settings ->
            val saved = EpisodeShuffleRepository.save(contentId, settings, profileId)
            val changed = current.enabled != settings.enabled ||
                (settings.enabled && current.includeWatched != settings.includeWatched)
            when {
                !saved -> NuvioToastController.show(failed)
                changed -> NuvioToastController.show(when {
                    !settings.enabled -> off
                    settings.includeWatched -> all
                    else -> unwatched
                })
            }
            saved
        }
    }
}
