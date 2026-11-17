package com.nuvio.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class NetworkErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (error: RuntimeException) {
            throw error.cause as? IOException ?: error
        }
}
