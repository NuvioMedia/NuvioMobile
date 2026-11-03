package com.nuvio.app.features.simkl

internal expect object SimklPlatformClock {
    fun nowEpochMs(): Long
}

internal expect object SimklPkceCrypto {
    fun secureRandomBytes(size: Int): ByteArray
    fun sha256(value: ByteArray): ByteArray
}

internal expect object SimklAuthStorage {
    fun loadMetadataPayload(profileId: Int): String?
    fun saveMetadataPayload(profileId: Int, payload: String)
    fun loadAccessToken(profileId: Int): String?
    fun saveAccessToken(profileId: Int, value: String?)
    fun loadRefreshToken(profileId: Int): String?
    fun saveRefreshToken(profileId: Int, value: String?)
    fun loadCodeVerifier(profileId: Int): String?
    fun saveCodeVerifier(profileId: Int, value: String?)
    fun removeProfile(profileId: Int)
}
