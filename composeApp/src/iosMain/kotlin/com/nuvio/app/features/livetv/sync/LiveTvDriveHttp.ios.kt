package com.nuvio.app.features.livetv.sync

/** Google Drive backup isn't wired up on iOS yet. */
internal actual object LiveTvDriveHttp {
    private val memory = HashMap<String, Any?>()

    actual suspend fun postForm(url: String, fields: Map<String, String>): DriveHttpResponse =
        DriveHttpResponse(0, """{"error":"unavailable"}""")

    actual suspend fun request(method: String, url: String, token: String, contentType: String?, body: String?): DriveHttpResponse =
        DriveHttpResponse(0, "")

    actual fun getString(key: String): String? = memory[key] as? String
    actual fun putString(key: String, value: String?) { if (value == null) memory.remove(key) else memory[key] = value }
    actual fun getLong(key: String): Long = memory[key] as? Long ?: 0L
    actual fun putLong(key: String, value: Long) { memory[key] = value }
    actual fun getBoolean(key: String, default: Boolean): Boolean = memory[key] as? Boolean ?: default
    actual fun putBoolean(key: String, value: Boolean) { memory[key] = value }
    actual fun decodeBase64Url(text: String): String? = null
    actual fun sha256(text: String): String = text.hashCode().toString(16)
}
