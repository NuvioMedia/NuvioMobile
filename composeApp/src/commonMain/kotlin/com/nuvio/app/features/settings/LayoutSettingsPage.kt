package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.compose_settings_page_continue_watching
import nuvio.composeapp.generated.resources.compose_settings_page_homescreen
import nuvio.composeapp.generated.resources.compose_settings_page_meta_screen
import nuvio.composeapp.generated.resources.compose_settings_page_poster_customization
import nuvio.composeapp.generated.resources.compose_settings_page_streams
import nuvio.composeapp.generated.resources.compose_settings_root_streams_description
import nuvio.composeapp.generated.resources.settings_appearance_continue_watching_description
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass_description
import nuvio.composeapp.generated.resources.settings_appearance_nav_bar_style
import nuvio.composeapp.generated.resources.settings_appearance_poster_customization_description
import nuvio.composeapp.generated.resources.settings_appearance_section_detail_page
import nuvio.composeapp.generated.resources.settings_appearance_section_home
import nuvio.composeapp.generated.resources.settings_appearance_section_streams
import nuvio.composeapp.generated.resources.settings_content_discovery_collections_description
import nuvio.composeapp.generated.resources.settings_content_discovery_homescreen_description
import nuvio.composeapp.generated.resources.settings_content_discovery_meta_screen_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glow_off
import nuvio.composeapp.generated.resources.settings_nav_bar_glow_on
import nuvio.composeapp.generated.resources.settings_nav_bar_summary
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.layoutSettingsContent(
    isTablet: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    onHomescreenClick: () -> Unit,
    onMetaScreenClick: () -> Unit,
    onStreamsClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onContinueWatchingClick: () -> Unit,
    onPosterCustomizationClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_home),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_homescreen),
                    description = stringResource(Res.string.settings_content_discovery_homescreen_description),
                    isTablet = isTablet,
                    onClick = onHomescreenClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.collections_header),
                    description = stringResource(Res.string.settings_content_discovery_collections_description),
                    isTablet = isTablet,
                    onClick = onCollectionsClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_continue_watching),
                    description = stringResource(Res.string.settings_appearance_continue_watching_description),
                    isTablet = isTablet,
                    onClick = onContinueWatchingClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_poster_customization),
                    description = stringResource(Res.string.settings_appearance_poster_customization_description),
                    isTablet = isTablet,
                    onClick = onPosterCustomizationClick,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_streams),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_streams),
                    description = stringResource(Res.string.compose_settings_root_streams_description),
                    isTablet = isTablet,
                    onClick = onStreamsClick,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_detail_page),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_meta_screen),
                    description = stringResource(Res.string.settings_content_discovery_meta_screen_description),
                    isTablet = isTablet,
                    onClick = onMetaScreenClick,
                )
            }
        }
    }
    if (!isIos || liquidGlassNativeTabBarSupported) {
        item {
            var showNavBarStyleSheet by remember { mutableStateOf(false) }
            val selectedNavBarStyle by ThemeSettingsRepository.navBarStyle.collectAsStateWithLifecycle()
            val glowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
            val liquidGlassNativeTabBarEnabled by ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.collectAsStateWithLifecycle()
            val effectiveNavBarStyle = if (isTablet) NavBarStyle.COMPACT else selectedNavBarStyle
            val navBarStyleAvailable = !isIos
            SettingsSection(
                title = stringResource(Res.string.settings_appearance_nav_bar_style),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (liquidGlassNativeTabBarSupported) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_appearance_liquid_glass),
                            description = stringResource(Res.string.settings_appearance_liquid_glass_description),
                            checked = liquidGlassNativeTabBarEnabled,
                            isTablet = isTablet,
                            onCheckedChange = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                        )
                    }
                    if (navBarStyleAvailable) {
                        if (liquidGlassNativeTabBarSupported) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_appearance_nav_bar_style),
                            description = if (floatingNavigationGlowSupported && effectiveNavBarStyle != NavBarStyle.CLASSIC) {
                                stringResource(
                                    Res.string.settings_nav_bar_summary,
                                    stringResource(effectiveNavBarStyle.labelRes),
                                    stringResource(if (glowEnabled) Res.string.settings_nav_bar_glow_on else Res.string.settings_nav_bar_glow_off),
                                )
                            } else {
                                stringResource(effectiveNavBarStyle.labelRes)
                            },
                            isTablet = isTablet,
                            onClick = { showNavBarStyleSheet = true },
                        )
                    }
                }
            }
            if (navBarStyleAvailable && showNavBarStyleSheet) {
                NavigationBarSettingsSheet(
                    isTablet = isTablet,
                    selectedStyle = effectiveNavBarStyle,
                    onStyleSelected = ThemeSettingsRepository::setNavBarStyle,
                    glowEnabled = glowEnabled,
                    onGlowChanged = ThemeSettingsRepository::setNavBarGlowEnabled,
                    onDismiss = { showNavBarStyleSheet = false },
                )
            }
        }
    }
}
