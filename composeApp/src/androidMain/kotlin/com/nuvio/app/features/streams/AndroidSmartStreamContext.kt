package com.nuvio.app.features.streams

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display

object AndroidSmartStreamContext {
    fun current(context: Context): SmartStreamSelector.Context {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)

        val mode = display?.mode
        val displayWidth = mode?.physicalWidth?.takeIf { it > 0 }
        val displayHeight = mode?.physicalHeight?.takeIf { it > 0 }
        val hdrTypes = display?.supportedHdrTypes().orEmpty()

        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val bandwidthKbps = connectivityManager?.getActiveNetwork()
            ?.let(connectivityManager::getNetworkCapabilities)
            ?.getLinkDownstreamBandwidthKbps()
            ?.takeIf { it > 0 }

        return SmartStreamSelector.Context(
            estimatedBandwidthKbps = bandwidthKbps,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            supportsHdr = hdrTypes.isNotEmpty(),
            supportedHdrTypes = hdrTypes,
        )
    }

    private fun Display.supportedHdrTypes(): Set<String> {
        val types = if (Build.VERSION.SDK_INT >= 34) {
            mode?.supportedHdrTypes?.toSet().orEmpty()
        } else {
            hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        }
        return types.mapNotNull { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolbyvision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10+"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
                else -> null
            }
        }.toSet()
    }
}
