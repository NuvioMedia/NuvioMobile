package com.nuvio.app.features.profiles

import platform.Foundation.NSUserDefaults

actual object ProfileStorage {
    private const val payloadKey = "profile_payload"

    actual fun onActiveProfileChanged(id: Int) = Unit
    actual fun onProfileRemoved(id: Int) = Unit

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}