package com.nuvio.app.features.trakt

internal expect object TraktAuthStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun savePayloadDurably(profileId: Int, payload: String): Boolean
    fun removeProfile(profileId: Int)
}
