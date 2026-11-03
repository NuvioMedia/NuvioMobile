package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference
import com.lagradost.cloudstream3.utils.DataStore

/** Host-context ABI used by a small subset of CloudStream extensions. */
class CloudStreamApp {
    companion object {
        private var contextReference: WeakReference<Context>? = null

        var context: Context?
            get() = contextReference?.get()
            set(value) {
                contextReference = value?.let(::WeakReference)
                if (value != null) com.lagradost.api.setContext(WeakReference(value as Any))
            }

        tailrec fun Context.getActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.getActivity()
            else -> null
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            val json = sharedPreferences()?.getString(path, null) ?: return null
            return runCatching { mapper.readValue(json, valueType) }.getOrNull()
        }

        fun <T : Any> setKeyClass(path: String, value: T) = setKey(path, value)

        fun <T> setKey(path: String, value: T) {
            val preferences = sharedPreferences() ?: return
            preferences.edit().apply {
                if (value == null) remove(path) else putString(path, mapper.writeValueAsString(value))
            }.apply()
        }

        fun <T> setKey(folder: String, path: String, value: T) =
            setKey(DataStore.getFolderName(folder, path), value)

        fun removeKey(path: String) {
            sharedPreferences()?.edit()?.remove(path)?.apply()
        }

        fun removeKey(folder: String, path: String) = removeKey(DataStore.getFolderName(folder, path))

        fun removeKeys(folder: String): Int? {
            val preferences = sharedPreferences() ?: return null
            val keys = getKeys(folder).orEmpty()
            preferences.edit().apply { keys.forEach(::remove) }.apply()
            return keys.size
        }

        fun getKeys(folder: String): List<String>? {
            val preferences = sharedPreferences() ?: return null
            val prefix = folder.trimEnd('/') + "/"
            return preferences.all.keys.filter { it.startsWith(prefix) }
        }

        private fun sharedPreferences() = context?.let { currentContext ->
            with(DataStore) { currentContext.getSharedPrefs() }
        }
    }
}
