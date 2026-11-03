package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Binary-compatible CloudStream interceptor used by providers protected by Cloudflare.
 *
 * This follows CloudStream's host behaviour: ordinary responses stay on the caller's
 * chain, while a 403/503 Cloudflare response is retried after obtaining cf_clearance
 * cookies through the shared WebView resolver.
 */
@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val errorCodes = setOf(403, 503)
        private val cloudflareServers = setOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(';').mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                (key to value).takeIf { key.isNotBlank() && value.isNotBlank() }
            }.toMap()
    }

    init {
        safe { CookieManager.getInstance().removeAllCookies(null) }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) }.orEmpty()
        return getHeaders(userAgentHeaders, savedCookies[URI(url).host].orEmpty())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        savedCookies[request.url.host]?.let { return@runBlocking proceed(request, it) }

        val response = chain.proceed(request)
        if (response.header("Server") !in cloudflareServers || response.code !in errorCodes) {
            return@runBlocking response
        }
        response.close()
        bypassCloudflare(request)?.let {
            Log.d(TAG, "Succeeded bypassing Cloudflare: ${request.url}")
            return@runBlocking it
        }

        debugWarning({ true }) { "Failed Cloudflare challenge at: ${request.url}" }
        chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? = safe {
        CookieManager.getInstance()?.getCookie(url)
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean =
        getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentHeaders = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        }.orEmpty()
        val headers = getHeaders(request.headers.toMap() + userAgentHeaders, cookies + request.cookies)
        return app.baseClient.newCall(request.newBuilder().headers(headers).build()).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySolveWithSavedCookies(request)) {
            WebViewResolver(
                interceptUrl = Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
            ).resolveUsingWebView(request.url.toString()) {
                trySolveWithSavedCookies(request)
            }
        }
        return savedCookies[request.url.host]?.let { proceed(request, it) }
    }
}
