package com.nuvio.app.features.dismissed

expect object DismissedStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
