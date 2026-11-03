package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Binary-compatible DDoS-Guard interceptor expected by standard CloudStream plugins. */
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean) : Interceptor {
    val savedCookiesMap: MutableMap<String, Map<String, String>> = mutableMapOf()
    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(request)
        val response = chain.proceed(request)
        if (response.code == 403) {
            response.close()
            bypassDdosGuard(request)
        } else {
            response
        }
    }

    private suspend fun bypassDdosGuard(request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: Regex("'(.*?)'").find(
            app.get("https://check.ddos-guard.net/check.js").text,
        )?.groupValues?.getOrNull(1)

        val cookies = savedCookiesMap[request.url.host]
            ?: Requests().get(
                request.url.scheme + "://" + request.url.host + ddosBypassPath.orEmpty(),
            ).cookies.also { savedCookiesMap[request.url.host] = it }
        val headers = getHeaders(request.headers.toMap(), cookies + request.cookies)
        return app.baseClient.newCall(request.newBuilder().headers(headers).build()).execute()
    }
}
