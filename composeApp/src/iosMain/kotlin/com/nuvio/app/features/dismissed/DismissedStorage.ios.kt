package com.nuvio.app.features.dismissed

import platform.Foundation.NSUserDefaults

actual object DismissedStorage {
    private fun payloadKey(profileId: Int) = "dismissed_payload_$profileId"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey(profileId))
    }
}
