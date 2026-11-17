package com.nuvio.app.features.mdblist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MdbListAuthError {
    MISSING_CLIENT_ID,
    INVALID_RESPONSE,
    INSUFFICIENT_SCOPE,
    CODE_EXPIRED,
    ACCESS_DENIED,
    AUTHORIZATION_REVOKED
}

data class MdbListAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class MdbListDeviceSession(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "MdbListDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

data class MdbListAuthState(
    val scope: MdbListAuthScope = MdbListAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val user: MdbListUser? = null,
    val session: MdbListDeviceSession? = null,
    val error: MdbListAuthError? = null
)

@Serializable
data class MdbListUser(
    @SerialName("user_id") val id: Long? = null,
    val username: String? = null,
    @SerialName("is_supporter") val isSupporter: Boolean = false,
    @SerialName("rate_limit") val rateLimit: Int? = null,
    @SerialName("rate_limit_remaining") val rateLimitRemaining: Int? = null,
    @SerialName("rate_limit_reset") val rateLimitReset: Long? = null
)

@Serializable
data class MdbListTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
) {
    override fun toString(): String = "MdbListTokens(expiresAtEpochMs=$expiresAtEpochMs)"
}

class MdbListAuthorization(val scope: MdbListAuthScope, val tokens: MdbListTokens) {
    override fun toString(): String = "MdbListAuthorization(scope=$scope)"
}

sealed interface MdbListDevicePollResult {
    data object Pending : MdbListDevicePollResult
    data object Authorized : MdbListDevicePollResult
    data object Expired : MdbListDevicePollResult
    data object Denied : MdbListDevicePollResult
}

class MdbListAuthException(val error: MdbListAuthError) : Exception(error.name)

@Serializable
internal data class MdbListStoredAuth(
    val tokens: MdbListTokens? = null,
    val user: MdbListUser? = null,
    val session: MdbListDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "MdbListStoredAuth()"
}

@Serializable
internal data class MdbListDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int = 5
)

@Serializable
internal data class MdbListTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null
)
