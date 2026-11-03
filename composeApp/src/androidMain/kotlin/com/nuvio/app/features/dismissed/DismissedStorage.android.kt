package com.nuvio.app.features.dismissed

import android.content.Context
import android.content.SharedPreferences

actual object DismissedStorage {
    private const val preferencesName = "nuvio_dismissed"
    private fun payloadKey(profileId: Int) = "dismissed_payload_$profileId"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(payloadKey(profileId), null)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey(profileId), payload)
            ?.apply()
    }
}
