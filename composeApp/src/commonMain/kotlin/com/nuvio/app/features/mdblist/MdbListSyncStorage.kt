package com.nuvio.app.features.mdblist

interface MdbListSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

internal expect object PlatformMdbListSyncStorage : MdbListSyncStorage {
    override suspend fun load(profileId: Int): String?
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    override suspend fun remove(profileId: Int, checkScope: () -> Unit)
    fun clearAll()
}
