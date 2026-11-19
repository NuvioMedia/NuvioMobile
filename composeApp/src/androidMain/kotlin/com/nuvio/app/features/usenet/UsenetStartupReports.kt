package com.nuvio.app.features.usenet

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last Usenet startup's timings, kept on this device for the diagnostics page: elapsed marks
 * and the engine's own report, in NuvioTV's format. No link, release name or provider detail.
 */
internal object UsenetStartupReports {
    private const val PREFERENCES = "usenet_startup_diagnostics"
    private const val KEY = "lastReport"
    private var preferences: SharedPreferences? = null
    private val state = MutableStateFlow("")
    val latest: StateFlow<String> = state.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        state.value = preferences?.getString(KEY, "").orEmpty()
    }

    @Synchronized
    fun save(report: String) {
        state.value = report
        preferences?.edit()?.putString(KEY, report)?.apply()
    }

    @Synchronized
    fun clear() {
        state.value = ""
        preferences?.edit()?.remove(KEY)?.apply()
    }
}
