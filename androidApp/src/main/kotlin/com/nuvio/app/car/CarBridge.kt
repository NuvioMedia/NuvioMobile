package com.nuvio.app.car

import java.lang.ref.WeakReference

/**
 * Links the car screen (Car App Library side) with the Nuvio activity running on the car's virtual display.
 * Main thread only.
 */
internal object CarBridge {
    interface Listener {
        /** The car activity reached onResume. */
        fun onCarActivityShown(activity: CarNuvioActivity)

        /** The car activity was destroyed (finished, killed, or replaced). */
        fun onCarActivityGone(activity: CarNuvioActivity)
    }

    private var activityRef: WeakReference<CarNuvioActivity>? = null

    var listener: Listener? = null

    /** The live car activity, or null while none is running. */
    val activity: CarNuvioActivity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun attach(activity: CarNuvioActivity) {
        val previous = activityRef?.get()
        if (previous != null && previous !== activity && !previous.isFinishing) previous.finish()
        activityRef = WeakReference(activity)
    }

    fun shown(activity: CarNuvioActivity) {
        if (activityRef?.get() === activity) listener?.onCarActivityShown(activity)
    }

    fun detach(activity: CarNuvioActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            listener?.onCarActivityGone(activity)
        }
    }
}
