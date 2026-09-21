package com.nuvio.app.core.ui

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean = false

internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) = Unit

internal actual fun publishNativeTabBarVisible(visible: Boolean) = Unit

internal actual fun publishNativeSelectedTab(tabName: String) = Unit

internal actual fun publishNativeTabAccentColor(hexColor: String) = Unit

internal actual fun publishNativeTabTitles(
    home: String,
    search: String,
    liveTv: String,
    library: String,
    profile: String,
) = Unit

internal actual fun publishNativeProfileTabIcon(
    name: String?,
    avatarColorHex: String?,
    avatarImageUrl: String?,
    avatarBackgroundColorHex: String?,
) = Unit
