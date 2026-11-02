package com.nuvio.app.features.livetv.sync

/** An HTTP answer from Google: the status and the body as text. */
internal class DriveHttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The few HTTP calls Google sign-in and Drive need, and the small key-value store the sync
 * state lives in (refresh token, file id, last hash). Per platform, so Android can use its
 * own HTTP stack and keep the token out of the shared preferences file.
 */
internal expect object LiveTvDriveHttp {
    /** POST application/x-www-form-urlencoded. */
    suspend fun postForm(url: String, fields: Map<String, String>): DriveHttpResponse

    /** A request with a bearer token: GET, or POST/PATCH with a JSON or multipart/related body. */
    suspend fun request(
        method: String,
        url: String,
        token: String,
        contentType: String? = null,
        body: String? = null,
    ): DriveHttpResponse

    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    /** Decodes a base64url string (an id_token's payload) to text, or null. */
    fun decodeBase64Url(text: String): String?

    /** SHA-256 of [text], hex. */
    fun sha256(text: String): String
}
