package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

const val PREFERENCES_NAME: String = "rebuild_preference"

/**
 * Minimal persistent storage ABI used by extensions.
 * Data stays inside Nuvio's application sandbox and is never shared with CloudStream.
 */
object DataStore {
    fun getFolderName(folder: String, path: String): String = "${folder.trimEnd('/')}/${path.trimStart('/')}"

    fun Context.getSharedPrefs(): SharedPreferences =
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
