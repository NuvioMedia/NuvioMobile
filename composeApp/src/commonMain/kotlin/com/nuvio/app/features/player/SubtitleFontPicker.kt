package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun subtitleFontLabel(id: String, fonts: List<SubtitleFont>? = null): String = when (id) {
    "sans" -> stringResource(Res.string.subtitle_font_sans)
    "serif" -> stringResource(Res.string.subtitle_font_serif)
    "mono" -> stringResource(Res.string.subtitle_font_mono)
    "default" -> stringResource(Res.string.subtitle_font_default)
    else -> (fonts ?: remember(id) { SubtitleFonts.installed() }).find { it.id == id }?.name
        ?: stringResource(Res.string.subtitle_font_default)
}

@Composable
internal fun SubtitleFontPicker(selectedId: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var fonts by remember { mutableStateOf(SubtitleFonts.installed()) }
    var importFailed by remember { mutableStateOf(false) }
    val importFont = rememberSubtitleFontImporter { result ->
        importFailed = result.isFailure
        result.getOrNull()?.let {
            fonts = SubtitleFonts.installed()
            onSelect(it.id)
        }
    }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.subtitle_font)) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BuiltInSubtitleFonts.forEach { id ->
                DialogOption(
                    text = subtitleFontLabel(id, fonts),
                    selected = selectedId == id,
                    onClick = { onSelect(id) },
                    leading = { Text("Aa", fontFamily = rememberSubtitleFontFamily(id)) },
                )
            }
            fonts.forEach { font ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DialogOption(
                        text = font.name,
                        selected = selectedId == font.id,
                        onClick = { onSelect(font.id) },
                        modifier = Modifier.weight(1f),
                        leading = { Text("Aa", fontFamily = rememberSubtitleFontFamily(font.id)) },
                    )
                    IconButton(onClick = {
                        SubtitleFonts.remove(font.id)
                        fonts = SubtitleFonts.installed()
                        if (selectedId == font.id) onSelect("default")
                    }) {
                        Icon(Icons.Rounded.DeleteOutline, stringResource(Res.string.subtitle_font_remove, font.name))
                    }
                }
            }
            if (selectedId !in BuiltInSubtitleFonts && fonts.none { it.id == selectedId }) {
                Text(stringResource(Res.string.subtitle_font_missing), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(Res.string.subtitle_font_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importFailed) {
                Text(
                    stringResource(Res.string.subtitle_font_import_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.subtitle_font_import), onClick = importFont)
            DialogButton(text = stringResource(Res.string.action_done), onClick = onDismiss)
        }
    }
}
