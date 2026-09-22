package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.toColorPalette
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.custom_theme_preview
import nuvio.composeapp.generated.resources.custom_theme_preview_accent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CustomThemePreview(colors: CustomThemeColors) {
    val tokens = MaterialTheme.nuvio
    val palette = remember(colors) { colors.toColorPalette() }
    Column(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card)
            .background(palette.background).padding(tokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(
            text = stringResource(Res.string.custom_theme_preview),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textSecondary,
        )
        Box(
            Modifier.fillMaxWidth().height(NuvioTokens.Space.s32)
                .clip(tokens.shapes.chip).background(palette.accentBrush()),
        )
        Box(
            modifier = Modifier.fillMaxWidth().clip(tokens.shapes.button)
                .background(palette.backgroundCard)
                .border(tokens.borders.medium, palette.accentBrush(), tokens.shapes.button)
                .padding(NuvioTokens.Space.s12),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.custom_theme_preview_accent),
                style = MaterialTheme.typography.titleMedium.copy(brush = palette.accentBrush()),
            )
        }
    }
}
