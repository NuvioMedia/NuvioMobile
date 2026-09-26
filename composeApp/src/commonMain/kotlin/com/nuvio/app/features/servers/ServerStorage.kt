package com.nuvio.app.features.servers

internal expect object ServerStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun clear()
}
