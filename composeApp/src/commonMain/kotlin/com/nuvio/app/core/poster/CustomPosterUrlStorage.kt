package com.nuvio.app.core.poster

internal expect object CustomPosterUrlStorage {
    fun loadPattern(): String?
    fun savePattern(pattern: String?)
}
