package com.nuvio.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Tells moving from parked using the phone's GPS speed. Android Auto only gives the car's own speed sensor to
 * apps holding CAR_SPEED, which a sideloaded app can't get, so this is the same approach YT Auto uses.
 * Hysteresis keeps it from flickering at lights: moving above ~10 mph, parked again below ~2 mph.
 */
internal class DrivingDetector(
    private val ctx: Context,
    private val onChange: (moving: Boolean) -> Unit,
) {
    var moving = false
        private set

    private var lastFix: Location? = null
    private var listening = false

    private val listener = LocationListener { fix ->
        val prev = lastFix
        lastFix = fix
        val mps = when {
            fix.hasSpeed() -> fix.speed
            prev != null -> {
                val dt = (fix.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
                if (dt > 0f) fix.distanceTo(prev) / dt else return@LocationListener
            }
            else -> return@LocationListener
        }
        val next = if (moving) mps > STOPPED_MPS else mps > MOVING_MPS
        if (next != moving) {
            moving = next
            onChange(next)
        }
    }

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops GPS to match the setting; reports parked when stopping. */
    @SuppressLint("MissingPermission")
    fun sync() {
        val wanted = CarPrefs.hideVideoWhileDriving(ctx) && hasPermission()
        if (wanted == listening) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        if (wanted) {
            runCatching {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                listening = true
            }
        } else {
            stop()
        }
    }

    fun stop() {
        if (listening) {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
            listening = false
        }
        lastFix = null
        if (moving) {
            moving = false
            onChange(false)
        }
    }

    private companion object {
        const val MOVING_MPS = 4.5f // ~10 mph
        const val STOPPED_MPS = 1.0f // ~2 mph
    }
}
