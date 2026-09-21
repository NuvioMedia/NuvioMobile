package com.nuvio.app.core.ui

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

private const val liquidGlassNativeTabBarEnabledKey = "NuvioLiquidGlassNativeTabBarEnabled"
private const val nativeTabBarVisibleKey = "NuvioNativeTabBarVisible"
private const val nativeSelectedTabKey = "NuvioNativeSelectedTab"
private const val nativeTabAccentColorKey = "NuvioNativeTabAccentColor"
private const val nativeTabTitleHomeKey = "NuvioNativeTabTitleHome"
private const val nativeTabTitleSearchKey = "NuvioNativeTabTitleSearch"
private const val nativeTabTitleLiveTvKey = "NuvioNativeTabTitleLiveTv"
private const val nativeTabTitleLibraryKey = "NuvioNativeTabTitleLibrary"
private const val nativeTabTitleProfileKey = "NuvioNativeTabTitleProfile"
private const val nativeProfileNameKey = "NuvioNativeProfileName"
private const val nativeProfileAvatarColorKey = "NuvioNativeProfileAvatarColor"
private const val nativeProfileAvatarUrlKey = "NuvioNativeProfileAvatarURL"
private const val nativeProfileAvatarBackgroundColorKey = "NuvioNativeProfileAvatarBackgroundColor"
private const val nativeTabChromeDidChangeNotification = "NuvioNativeTabChromeDidChange"

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean {
    return UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone &&
        (UIDevice.currentDevice.systemVersion.substringBefore(".").toIntOrNull() ?: 0) >= 26
}

internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
    publishBool(liquidGlassNativeTabBarEnabledKey, enabled)
}

internal actual fun publishNativeTabBarVisible(visible: Boolean) {
    publishBool(nativeTabBarVisibleKey, visible)
}

internal actual fun publishNativeSelectedTab(tabName: String) {
    NSUserDefaults.standardUserDefaults.setObject(tabName, forKey = nativeSelectedTabKey)
    notifyNativeTabChromeChanged()
}

internal actual fun publishNativeTabAccentColor(hexColor: String) {
    NSUserDefaults.standardUserDefaults.setObject(hexColor, forKey = nativeTabAccentColorKey)
    notifyNativeTabChromeChanged()
}

internal actual fun publishNativeTabTitles(
    home: String,
    search: String,
    liveTv: String,
    library: String,
    profile: String,
) {
    publishString(nativeTabTitleHomeKey, home)
    publishString(nativeTabTitleSearchKey, search)
    publishString(nativeTabTitleLiveTvKey, liveTv)
    publishString(nativeTabTitleLibraryKey, library)
    publishString(nativeTabTitleProfileKey, profile)
    notifyNativeTabChromeChanged()
}

internal actual fun publishNativeProfileTabIcon(
    name: String?,
    avatarColorHex: String?,
    avatarImageUrl: String?,
    avatarBackgroundColorHex: String?,
) {
    publishString(nativeProfileNameKey, name)
    publishString(nativeProfileAvatarColorKey, avatarColorHex)
    publishString(nativeProfileAvatarUrlKey, avatarImageUrl)
    publishString(nativeProfileAvatarBackgroundColorKey, avatarBackgroundColorHex)
    notifyNativeTabChromeChanged()
}

private fun publishBool(key: String, value: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(value, forKey = key)
    notifyNativeTabChromeChanged()
}

private fun publishString(key: String, value: String?) {
    if (value.isNullOrBlank()) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    } else {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}

private fun notifyNativeTabChromeChanged() {
    NSNotificationCenter.defaultCenter.postNotificationName(nativeTabChromeDidChangeNotification, null)
}
