package com.nuvio.app.features.profiles

internal expect object ProfileStorage {
    fun onActiveProfileChanged(id: Int)
    fun onProfileRemoved(id: Int)

    fun loadPayload(): String?
    fun savePayload(payload: String)
}