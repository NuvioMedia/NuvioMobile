package com.nuvio.app.features.telegram

import android.app.Application
import android.util.Log

/**
 * Installed reflectively by the shared Android Application only in the Full distribution.
 * The Play Store build does not package TDLib or this bootstrap class.
 */
object TelegramAndroidBootstrap {
    private const val TAG = "TelegramBootstrap"
    @Volatile private var installed = false

    @JvmStatic
    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                val adapter = TelegramAndroidAdapter(application)
                TelegramIntegrationBridge.register(adapter)
                adapter.initialize()
                installed = true
                Log.i(TAG, "TDLib Telegram adapter registered")
            } catch (error: Throwable) {
                Log.e(TAG, "Could not install Telegram integration", error)
            }
        }
    }
}
