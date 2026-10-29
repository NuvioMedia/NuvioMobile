package com.nuvio.app.core.poster

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult

/**
 * Coil interceptor that detects failed custom poster loads and retries with the
 * original poster URL stored in [memoryCacheKeyExtras] under [FALLBACK_URL_KEY].
 */
class CustomPosterFallbackInterceptor : Interceptor {

    companion object {
        const val FALLBACK_URL_KEY = "custom_poster_fallback_url"
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val extras = chain.request.memoryCacheKeyExtras
        val hasFallback = extras.containsKey(FALLBACK_URL_KEY)

        val result = chain.proceed()

        if (result is ErrorResult && hasFallback) {
            val fallbackUrl = extras[FALLBACK_URL_KEY]
            if (!fallbackUrl.isNullOrBlank()) {
                val fallbackRequest = chain.request.newBuilder()
                    .data(fallbackUrl)
                    .memoryCacheKeyExtras(extras - FALLBACK_URL_KEY)
                    .build()
                return chain.withRequest(fallbackRequest).proceed()
            }
        }

        return result
    }
}
