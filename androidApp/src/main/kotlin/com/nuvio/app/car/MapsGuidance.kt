package com.nuvio.app.car

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Google Maps' turn-by-turn, read from its navigation notification, so Nuvio Auto can hand it to Android Auto
 * as its own trip while Nuvio fills the car screen. Android Auto shows the active navigation app's next turn
 * in the car's instrument cluster, and a full-screen Nuvio is that app. Experimental, off by default.
 */
internal object MapsGuidance {
    data class Guidance(
        val instruction: String,
        val distanceText: String?,
        val distanceMeters: Double?,
        val detail: String?,
        val icon: Bitmap?,
    )

    fun interface Listener {
        fun onGuidance(guidance: Guidance?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    @Volatile
    var current: Guidance? = null
        private set

    fun addListener(listener: Listener) {
        main.post { listeners += listener }
    }

    fun removeListener(listener: Listener) {
        main.post { listeners -= listener }
    }

    internal fun publish(guidance: Guidance?) {
        main.post {
            if (current == guidance) return@post
            current = guidance
            listeners.toList().forEach { it.onGuidance(guidance) }
        }
    }

    /** "0.3 mi", "500 ft", "1.2 km", "200 m" → meters. */
    fun parseMeters(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""([\d.,]+)\s*(mi|ft|km|m)\b""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "mi" -> value * 1609.344
            "ft" -> value * 0.3048
            "km" -> value * 1000.0
            else -> value
        }
    }
}

/** Notification access for [MapsGuidance]; only Google Maps' navigation notification is read. */
class MapsGuidanceListener : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()
            ?.firstOrNull { isMapsNavigation(it) }
            ?.let(::read)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isMapsNavigation(sbn)) read(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS) MapsGuidance.publish(null)
    }

    override fun onListenerDisconnected() {
        MapsGuidance.publish(null)
    }

    private fun isMapsNavigation(sbn: StatusBarNotification): Boolean =
        sbn.packageName == MAPS && sbn.isOngoing &&
            (sbn.notification.category == Notification.CATEGORY_NAVIGATION ||
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) != null)

    private fun read(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        // Maps puts the distance to the turn in the title and the instruction in the text.
        val titleMeters = MapsGuidance.parseMeters(title)
        val instruction = when {
            titleMeters != null && !text.isNullOrBlank() -> text
            !title.isNullOrBlank() -> title
            else -> text
        }
        if (instruction.isNullOrBlank()) return
        MapsGuidance.publish(
            MapsGuidance.Guidance(
                instruction = instruction,
                distanceText = if (titleMeters != null) title else null,
                distanceMeters = titleMeters ?: MapsGuidance.parseMeters(text),
                detail = sub,
                icon = iconBitmap(sbn.notification),
            ),
        )
    }

    private fun iconBitmap(n: Notification): Bitmap? = runCatching {
        val icon: Icon = n.getLargeIcon() ?: return@runCatching null
        val drawable = icon.loadDrawable(this) ?: return@runCatching null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return@runCatching drawable.bitmap
        val size = 128
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bmp))
        }
    }.getOrNull()

    private companion object {
        const val MAPS = "com.google.android.apps.maps"
    }
}
