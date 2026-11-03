package com.lagradost.cloudstream3

import android.app.Activity
import java.lang.ref.WeakReference

/** Current-activity ABI used by extensions that need a UI-bound Android context. */
object CommonActivity {
    private var activityReference: WeakReference<Activity>? = null

    var activity: Activity?
        get() = activityReference?.get()
        private set(value) {
            activityReference = value?.let(::WeakReference)
        }

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }
}
