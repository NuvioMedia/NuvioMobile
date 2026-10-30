package com.nuvio.app.features.mdblist

internal expect object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    override fun read(profileId: Int): String?
    override fun write(profileId: Int, value: String?)
    override fun clear()
}
