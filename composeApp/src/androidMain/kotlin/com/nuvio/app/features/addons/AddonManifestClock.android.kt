package com.nuvio.app.features.addons

actual object AddonManifestClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
