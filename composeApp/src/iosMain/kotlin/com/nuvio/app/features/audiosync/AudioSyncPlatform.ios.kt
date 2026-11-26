package com.nuvio.app.features.audiosync

import kotlinx.atomicfu.atomic
import platform.Foundation.NSUserDefaults

private val initialized = atomic(false)

internal actual fun ensureAudioSyncInitialized() {
    if (!initialized.compareAndSet(expect = false, update = true)) return
    val defaults = NSUserDefaults.standardUserDefaults
    AudioSyncSettings.installPersistence(
        load = { key -> if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null },
        save = { key, value -> defaults.setBool(value, forKey = key) },
        loadString = { key -> defaults.stringForKey(key) },
        saveString = { key, value -> defaults.setObject(value, forKey = key) },
    )
    IosAsrModel.initialize()
}
