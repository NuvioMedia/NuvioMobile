package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.availableAppThemes
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cd_selected
import nuvio.composeapp.generated.resources.custom_theme_edit
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppearanceThemePicker(
    isTablet: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    val memberAccess by remember {
        MemberAccessRepository.ensureStarted()
        MemberAccessRepository.access
    }.collectAsStateWithLifecycle()
    val customColors by ThemeSettingsRepository.customThemeColors.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profileState.activeProfile?.profileIndex
    val gradientEnabled = memberAccess.tier != null
    var showEditor by rememberSaveable(profileId, gradientEnabled) { mutableStateOf(false) }
    val themes = availableAppThemes(memberAccess.entitlements)
    val themeSpacing = if (isTablet) NuvioTokens.Space.s16 else NuvioTokens.Space.s12
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) NuvioTokens.Space.s20 else NuvioTokens.Space.s16,
                vertical = if (isTablet) NuvioTokens.Space.s18 else NuvioTokens.Space.s14,
            ),
    ) {
        val preferredColumns = if (isTablet) 4 else 3
        val minThemeCellWidth = if (isTablet) 92.dp else 78.dp
        val themeColumns = ((maxWidth + themeSpacing) / (minThemeCellWidth + themeSpacing))
            .toInt().coerceIn(1, preferredColumns)
        Column(verticalArrangement = Arrangement.spacedBy(themeSpacing)) {
            themes.chunked(themeColumns).forEach { rowThemes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(themeSpacing),
                ) {
                    rowThemes.forEach { theme ->
                        ThemeChip(
                            theme = theme,
                            customColors = customColors,
                            isSelected = theme == selectedTheme,
                            onClick = {
                                if (theme == AppTheme.CUSTOM) showEditor = true else onThemeSelected(theme)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(themeColumns - rowThemes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (showEditor) {
        CustomThemeEditor(
            initialColors = customColors,
            allowGradient = gradientEnabled,
            onSave = ThemeSettingsRepository::setCustomTheme,
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun ThemeChip(
    theme: AppTheme,
    customColors: CustomThemeColors,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val palette = remember(theme, customColors) { ThemeColors.getColorPalette(theme, customColors) }
    Column(
        modifier = modifier
            .clip(tokens.shapes.compactCard)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s4, vertical = NuvioTokens.Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(NuvioTokens.Space.s56).then(
                if (isSelected) Modifier.border(tokens.borders.medium, palette.focusRing, CircleShape)
                else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(NuvioTokens.Space.s40 + NuvioTokens.Space.s4)
                    .clip(CircleShape)
                    .background(palette.accentBrush()),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected || theme == AppTheme.CUSTOM) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = stringResource(
                            if (isSelected) Res.string.cd_selected else Res.string.custom_theme_edit,
                        ),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(NuvioTokens.Space.s22),
                    )
                }
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s6))
        Text(
            text = stringResource(theme.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textSecondary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
