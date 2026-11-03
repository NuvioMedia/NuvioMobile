package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.AppTheme
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

internal val AppIconOption.labelResource: StringResource
    get() = when (this) {
        AppIconOption.ORIGINAL -> Res.string.settings_appearance_app_icon_original
        AppIconOption.ARCTIC_BLUE -> Res.string.settings_appearance_app_icon_arctic_blue
        AppIconOption.EMERALD -> Res.string.settings_appearance_app_icon_emerald
        AppIconOption.ROSE_GOLD -> Res.string.settings_appearance_app_icon_rose_gold
        AppIconOption.COPPER -> Res.string.settings_appearance_app_icon_copper
        AppIconOption.GRAPHITE -> Res.string.settings_appearance_app_icon_graphite
    }

internal val AppIconOption.previewResource: DrawableResource
    get() = when (this) {
        AppIconOption.ORIGINAL -> Res.drawable.app_icon_original
        AppIconOption.ARCTIC_BLUE -> Res.drawable.app_icon_arctic_blue
        AppIconOption.EMERALD -> Res.drawable.app_icon_emerald
        AppIconOption.ROSE_GOLD -> Res.drawable.app_icon_rose_gold
        AppIconOption.COPPER -> Res.drawable.app_icon_copper
        AppIconOption.GRAPHITE -> Res.drawable.app_icon_graphite
    }

internal val AppIconOption.wordmarkResource: DrawableResource
    get() = when (this) {
        AppIconOption.ORIGINAL -> Res.drawable.app_logo_wordmark_original
        AppIconOption.ARCTIC_BLUE -> Res.drawable.app_logo_wordmark_arctic_blue
        AppIconOption.EMERALD -> Res.drawable.app_logo_wordmark_emerald
        AppIconOption.ROSE_GOLD -> Res.drawable.app_logo_wordmark_rose_gold
        AppIconOption.COPPER -> Res.drawable.app_logo_wordmark_copper
        AppIconOption.GRAPHITE -> Res.drawable.app_logo_wordmark_graphite
    }

internal fun AppTheme.wordmarkResource(fallback: AppIconOption): DrawableResource =
    when (this) {
        AppTheme.GOLD -> Res.drawable.app_logo_wordmark_gold
        AppTheme.JADE -> AppIconOption.EMERALD.wordmarkResource
        AppTheme.ROSE_GOLD -> AppIconOption.ROSE_GOLD.wordmarkResource
        AppTheme.ARCTIC_BLUE -> AppIconOption.ARCTIC_BLUE.wordmarkResource
        AppTheme.GRAPHITE -> AppIconOption.GRAPHITE.wordmarkResource
        else -> fallback.wordmarkResource
    }

/** The "Nuvio + IPTV" version of a Nuvio wordmark (same logo, "+ IPTV" in the logo's colours). */
internal fun DrawableResource.iptvWordmark(): DrawableResource =
    when (this) {
        Res.drawable.app_logo_wordmark_gold -> Res.drawable.livetv_wordmark_iptv_gold
        Res.drawable.app_logo_wordmark_arctic_blue -> Res.drawable.livetv_wordmark_iptv_arctic_blue
        Res.drawable.app_logo_wordmark_emerald -> Res.drawable.livetv_wordmark_iptv_emerald
        Res.drawable.app_logo_wordmark_rose_gold -> Res.drawable.livetv_wordmark_iptv_rose_gold
        Res.drawable.app_logo_wordmark_copper -> Res.drawable.livetv_wordmark_iptv_copper
        Res.drawable.app_logo_wordmark_graphite -> Res.drawable.livetv_wordmark_iptv_graphite
        else -> Res.drawable.livetv_wordmark_iptv_original
    }
