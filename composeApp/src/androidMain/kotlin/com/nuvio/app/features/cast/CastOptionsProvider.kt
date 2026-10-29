package com.nuvio.app.features.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Provides CastOptions for Google Cast SDK.
 * Uses Default Media Receiver (DEFAULT_MEDIA_RECEIVER_APPLICATION_ID) which supports
 * video/mp4, HLS, DASH, WebVTT subtitles, and standard controls.
 * No custom receiver needed for Nuvio - proxy serves content as mp4 with headers handled.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
