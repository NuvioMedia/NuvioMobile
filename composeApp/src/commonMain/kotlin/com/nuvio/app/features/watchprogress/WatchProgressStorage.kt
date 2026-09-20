package com.nuvio.app.features.watchprogress

internal expect object WatchProgressStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
    fun flushPendingWrites(): Boolean
}
