package com.nuvio.app.features.addons

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

actual object AddonManifestClock {
    @OptIn(ExperimentalForeignApi::class)
    actual fun nowEpochMs(): Long = time(null) * 1000L
}
