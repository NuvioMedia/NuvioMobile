package com.nuvio.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
/** Old iOS OpenGL ES player can only present 8-bit SDR, never native EDR. */
internal expect val isLegacyIosVideoOutput: Boolean

internal expect val supportsPosterNavigationMotion: Boolean
