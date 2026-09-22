package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.TrackingLibraryTab
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_lists_empty
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryListRows(
    tabs: List<TrackingLibraryTab>,
    onEdit: (TrackingLibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    if (tabs.isEmpty()) {
        Column(
            modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null, modifier = Modifier.size(36.dp), tint = tokens.colors.textMuted)
            Text(stringResource(Res.string.library_lists_empty), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textMuted)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tabs, key = { it.key }) { tab ->
            Surface(onClick = { onEdit(tab) }, shape = tokens.shapes.compactCard,
                color = tokens.colors.surfaceCard, contentColor = tokens.colors.textPrimary) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null, tint = tokens.colors.textMuted)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tab.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        tab.privacy?.let { Text(privacyLabel(it), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = tokens.colors.textMuted)
                }
            }
        }
    }
}
