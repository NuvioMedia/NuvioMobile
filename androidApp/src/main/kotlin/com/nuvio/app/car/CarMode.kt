package com.nuvio.app.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Which way Nuvio registers with Android Auto. Both entry points are in the app; this turns one off so
 * Android Auto only sees the other.
 *
 * - [NAVIGATION]: Car App Library navigation app (template buttons, car mic, resume prompt). Android Auto
 *   treats a full-screen Nuvio as the navigation app, so Google Maps' cockpit directions go away.
 * - [FULL_SCREEN_APP]: projection app like Fermata (floating buttons, car keyboard, real touches). Android Auto
 *   treats Nuvio as an ordinary app, so Google Maps stays the navigation app for the cockpit.
 */
internal enum class CarMode(val label: String, val description: String) {
    NAVIGATION(
        "Navigation-style",
        "Android Auto's own buttons, car mic voice search and the resume prompt. The cockpit loses Maps directions while Nuvio is full screen.",
    ),
    FULL_SCREEN_APP(
        "Full-screen app (keeps Maps in the cockpit)",
        "Runs like Fermata: Maps stays the navigation app, so the cockpit keeps its directions. Floating buttons and the car keyboard; voice search from the phone remote.",
    );

    companion object {
        fun current(ctx: Context): CarMode {
            val state = ctx.packageManager.getComponentEnabledSetting(component(ctx, NuvioCarAppService::class.java))
            return if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) FULL_SCREEN_APP else NAVIGATION
        }

        fun set(ctx: Context, mode: CarMode) {
            val pm = ctx.packageManager
            fun toggle(cls: Class<*>, on: Boolean) = pm.setComponentEnabledSetting(
                component(ctx, cls),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            toggle(LegacyCarService::class.java, true)
            toggle(NuvioCarAppService::class.java, mode == NAVIGATION)
            CarLog.d("Car mode set to $mode")
        }

        private fun component(ctx: Context, cls: Class<*>) = ComponentName(ctx.packageName, cls.name)
    }
}
