package com.nuvio.app.features.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal actual object LiveTvDriveHttp {
    private var store: SharedPreferences? = null

    fun initialize(context: Context) {
        store = context.applicationContext.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    actual suspend fun postForm(url: String, fields: Map<String, String>): DriveHttpResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { r ->
            DriveHttpResponse(r.code, r.body?.string().orEmpty())
        }
    }

    actual suspend fun request(method: String, url: String, token: String, contentType: String?, body: String?): DriveHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            val requestBody = body?.toRequestBody((contentType ?: "application/json; charset=UTF-8").toMediaType())
            when (method.uppercase()) {
                "POST" -> builder.post(requestBody ?: "".toRequestBody(null))
                "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(null))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            http.newCall(builder.build()).execute().use { r ->
                DriveHttpResponse(r.code, r.body?.string().orEmpty())
            }
        }

    actual fun getString(key: String): String? = store?.getString(key, null)
    actual fun putString(key: String, value: String?) {
        store?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }
    actual fun getLong(key: String): Long = store?.getLong(key, 0L) ?: 0L
    actual fun putLong(key: String, value: Long) { store?.edit()?.putLong(key, value)?.apply() }
    actual fun getBoolean(key: String, default: Boolean): Boolean = store?.getBoolean(key, default) ?: default
    actual fun putBoolean(key: String, value: Boolean) { store?.edit()?.putBoolean(key, value)?.apply() }

    actual fun decodeBase64Url(text: String): String? = runCatching {
        String(Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }.getOrNull()

    actual fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
