package com.nuvio.app.features.mdblist

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createMdbListHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }
}
