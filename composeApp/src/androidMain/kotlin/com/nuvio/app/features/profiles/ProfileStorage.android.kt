package com.nuvio.app.features.profiles

import android.content.Context
import android.content.SharedPreferences

actual object ProfileStorage {
    private const val preferencesName = "nuvio_profile_cache"
    private const val payloadKey = "profile_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (loadPayload().isNullOrBlank() || ProfileRepository.state.value.isLoaded) {
            onActiveProfileChanged(ProfileRepository.activeProfileId)
        }
    }

    actual fun onActiveProfileChanged(id: Int) {
        if (preferences != null) com.nuvio.vpn.android.VpnPlaybackProtection.setActiveUserProfile(id)
    }

    actual fun onProfileRemoved(id: Int) {
        if (preferences != null) com.nuvio.vpn.android.VpnPlaybackProtection.removeUserProfile(id)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }
}