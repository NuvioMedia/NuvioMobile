package com.nuvio.app.features.mdblist

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

internal actual object PlatformMdbListSyncStorage : MdbListSyncStorage {
    private lateinit var preferences: SharedPreferences

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_mdblist_sync", Context.MODE_PRIVATE)
    }

    actual override suspend fun load(profileId: Int): String? =
        if (::preferences.isInitialized) preferences.getString("profile.$profileId", null) else null

    actual override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        if (!preferences.edit().putString("profile.$profileId", payload).commit()) throw IOException("Unable to save MDBList cache")
    }

    actual override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        if (!::preferences.isInitialized) return
        if (!preferences.edit().remove("profile.$profileId").commit()) throw IOException("Unable to remove MDBList cache")
    }

    actual fun clearAll() {
        if (!::preferences.isInitialized) return
        if (!preferences.edit().clear().commit()) throw IOException("Unable to clear MDBList cache")
    }
}
