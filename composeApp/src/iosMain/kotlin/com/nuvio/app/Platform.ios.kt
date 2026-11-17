package com.nuvio.app

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

internal actual val isIos: Boolean = true
internal actual val isLegacyIosVideoOutput: Boolean =
    (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 99) < 16

internal actual val supportsPosterNavigationMotion: Boolean = false
