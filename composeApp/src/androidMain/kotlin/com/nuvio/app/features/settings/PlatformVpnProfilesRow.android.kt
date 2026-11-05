package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.vpn.android.VpnProfilesActivity
import com.nuvio.vpn.android.VpnAppearance
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.vpn.android.R as VpnR

@Composable
internal actual fun PlatformVpnProfilesRow(isTablet: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.nuvio.colors
    val type = MaterialTheme.typography
    val appearance = VpnAppearance(false)
        .colors(
            colors.background.toArgb(), colors.surface.toArgb(), colors.borderSubtle.toArgb(),
            colors.textPrimary.toArgb(), colors.textMuted.toArgb(), colors.accent.toArgb(),
            colors.focusBackground.toArgb(), colors.textPrimary.toArgb(), colors.focusRing.toArgb(),
            colors.danger.toArgb(),
        )
        .typography(type.headlineLarge.fontSize.value, type.bodyLarge.fontSize.value, type.bodyMedium.fontSize.value)
        .corners(NuvioTokens.Radius.card.value, NuvioTokens.Radius.compactCard.value)
        .fontAsset("composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_regular.ttf")
    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(VpnR.string.vpn_profiles_title),
        description = stringResource(VpnR.string.vpn_profiles_description),
        icon = Icons.Default.VpnKey,
        isTablet = isTablet,
        onClick = { VpnProfilesActivity.open(context, appearance) },
    )
}
