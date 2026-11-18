package com.nuvio.app.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point. Registered as a navigation app because that's the only category the host hands
 * a raw drawing surface to (the same trick YT Auto uses); [NuvioCarScreen] runs Nuvio on that surface.
 */
class NuvioCarAppService : CarAppService() {
    // Sideloaded personal build: accept any host (Android Auto, the Desktop Head Unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = NuvioCarScreen(carContext)
    }
}
