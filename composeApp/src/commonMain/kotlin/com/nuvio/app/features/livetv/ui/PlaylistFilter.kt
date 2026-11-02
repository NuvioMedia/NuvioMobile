package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.sync.LiveTvDriveHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which playlist the group view, the guide and On Demand show ("All playlists" = null). One choice
 * for all three, remembered on this phone only (it is not part of the synced setup).
 */
internal object PlaylistFilter {
    private const val KEY = "playlist_filter"

    private val _selected = MutableStateFlow(runCatching { LiveTvDriveHttp.getString(KEY) }.getOrNull()?.takeIf { it.isNotBlank() })
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(id: String?) {
        _selected.value = id
        runCatching { LiveTvDriveHttp.putString(KEY, id) }
    }

    /** The saved choice if that playlist is one of [ids], otherwise null (all playlists). */
    fun effective(saved: String?, ids: Collection<String>): String? = saved?.takeIf { it in ids }
}

internal data class PlaylistChoice(val id: String, val name: String, val detail: String)

/**
 * The playlist dropdown. Shows nothing with fewer than two playlists, since there is nothing to
 * pick from.
 */
@Composable
internal fun PlaylistDropdown(
    choices: List<PlaylistChoice>,
    selectedId: String?,
    allDetail: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (choices.size < 2) return
    val tokens = MaterialTheme.nuvio
    var menuOpen by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.id == selectedId }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(tokens.colors.surface)
                .clickable { menuOpen = true }
                .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(NuvioTokens.Icon.md))
            Text(
                text = current?.name ?: "All playlists",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = current?.detail ?: allDetail, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = tokens.colors.textMuted)
        }
        Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItem(text = "All playlists", selected = current == null, onClick = { menuOpen = false; onSelect(null) })
            choices.forEach { c ->
                MenuItem(text = c.name, selected = current?.id == c.id, onClick = { menuOpen = false; onSelect(c.id) })
            }
        }
    }
}
