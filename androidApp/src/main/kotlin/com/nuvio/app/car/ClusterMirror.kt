package com.nuvio.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.nuvio.android.R
import java.util.TimeZone

/**
 * Experimental: while Nuvio fills the car screen it is Android Auto's navigation app, so the car's cockpit
 * display loses Google Maps' next turn. This re-publishes Maps' guidance (from [MapsGuidance]) as Nuvio's own
 * trip so the cockpit keeps showing it.
 */
internal class ClusterMirror(private val ctx: CarContext) : MapsGuidance.Listener {

    private val nav: NavigationManager = ctx.getCarService(NavigationManager::class.java)
    private var navigating = false
    private var stoppedByHost = false

    fun start() {
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                CarLog.d("Android Auto stopped Nuvio's mirrored guidance")
                navigating = false
                stoppedByHost = true
            }
        })
        MapsGuidance.addListener(this)
        onGuidance(MapsGuidance.current)
    }

    fun stop() {
        MapsGuidance.removeListener(this)
        end()
        runCatching { nav.clearNavigationManagerCallback() }
    }

    override fun onGuidance(guidance: MapsGuidance.Guidance?) {
        if (guidance == null) {
            end()
            return
        }
        if (stoppedByHost) return
        try {
            if (!navigating) {
                nav.navigationStarted()
                navigating = true
                CarLog.d("Mirroring Google Maps guidance to the cockpit")
            }
            nav.updateTrip(trip(guidance))
        } catch (e: Exception) {
            CarLog.d("Couldn't mirror guidance", e)
        }
    }

    private fun trip(g: MapsGuidance.Guidance): Trip {
        val icon = g.icon?.let { CarIcon.Builder(IconCompat.createWithBitmap(it)).build() }
            ?: CarIcon.Builder(IconCompat.createWithResource(ctx, R.drawable.car_ic_turn)).build()
        val maneuver = Maneuver.Builder(Maneuver.TYPE_STRAIGHT).setIcon(icon).build()
        val step = Step.Builder(g.instruction).setManeuver(maneuver).build()
        val meters = g.distanceMeters ?: 0.0
        val distance = if (meters >= 160) {
            Distance.create(meters / 1609.344, Distance.UNIT_MILES_P1)
        } else {
            Distance.create(meters / 0.3048, Distance.UNIT_FEET)
        }
        val arrival = DateTimeWithZone.create(System.currentTimeMillis(), TimeZone.getDefault())
        val estimate = TravelEstimate.Builder(distance, arrival).build()
        return Trip.Builder()
            .addStep(step, estimate)
            .apply { g.detail?.let { setCurrentRoad(it) } }
            .build()
    }

    private fun end() {
        if (!navigating) return
        navigating = false
        runCatching { nav.navigationEnded() }
        CarLog.d("Stopped mirroring guidance")
    }
}
