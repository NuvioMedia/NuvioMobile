package com.nuvio.app.features.livetv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

internal actual object SportsPlatform {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    actual suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string() ?: throw IOException("empty answer")
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP ") == true || e.message == "empty answer") throw e
            throw IOException(
                when (e) {
                    is UnknownHostException -> "no internet or the address can't be found"
                    is SocketTimeoutException -> "timed out"
                    else -> e.message?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
                },
                e
            )
        }
    }

    actual fun countryCode(): String = Locale.getDefault().country.uppercase(Locale.ROOT)

    actual fun languageCode(): String = Locale.getDefault().language.lowercase(Locale.ROOT)

    private val MARKS = Regex("\\p{M}+")
    actual fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    actual fun foldAccents(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
}
