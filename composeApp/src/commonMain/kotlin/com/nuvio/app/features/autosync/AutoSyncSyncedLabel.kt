package com.nuvio.app.features.autosync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_label_synced
import org.jetbrains.compose.resources.stringResource

/**
 * The add-on subtitle whose on-screen timing AutoSync corrected or confirmed.
 * Cleared whenever the sidecar starts or stops a subtitle, since that renders the original timing.
 */
internal object AutoSyncSyncedSubtitle {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    fun mark(subtitleUrl: String) {
        _url.value = subtitleUrl
    }

    fun clear() {
        _url.value = null
    }
}

/** "Auto synced" chip for the selected add-on subtitle in the subtitle panel; empty otherwise. */
@Composable
internal fun AutoSyncedChip(subtitleUrl: String, selected: Boolean) {
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    if (!selected || syncedUrl != subtitleUrl) return

    val tokens = MaterialTheme.nuvio
    val contentColor = tokens.colors.onAccent.copy(alpha = 0.9f)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tokens.colors.onAccent.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(Res.string.autosync_label_synced),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
