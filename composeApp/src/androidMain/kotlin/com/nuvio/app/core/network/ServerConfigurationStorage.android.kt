package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object ServerConfigurationStorage {
    private const val preferencesName = "server_configuration"
    private const val customEnabledKey = "custom_enabled"
    private const val backendUrlKey = "backend_url"
    private const val publishableKey = "publishable_key"
    private const val emailPasswordAuthKey = "email_password_auth"
    private const val tvLoginKey = "tv_login"
    private const val watchPartyKey = "watch_party"
    private const val discoveryUrlKey = "discovery_url"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadCustom(): ServerConfiguration? {
        val values = preferences ?: return null
        if (!values.getBoolean(customEnabledKey, false)) return null
        val backendUrl = values.getString(backendUrlKey, null)?.trim().orEmpty()
        val key = values.getString(publishableKey, null)?.trim().orEmpty()
        val emailPasswordAuth = values.getBoolean(emailPasswordAuthKey, false)
        val tvLogin = values.getBoolean(tvLoginKey, false)
        if (backendUrl.isBlank() || key.isBlank() || !emailPasswordAuth) return null
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = key,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
                watchParty = values.getBoolean(watchPartyKey, false),
            ),
            isCustom = true,
            discoveryUrl = values.getString(discoveryUrlKey, null),
        )
    }

    actual fun saveCustom(configuration: ServerConfiguration): Boolean =
        preferences
            ?.edit()
            ?.putBoolean(customEnabledKey, true)
            ?.putString(backendUrlKey, configuration.backendUrl)
            ?.putString(publishableKey, configuration.publishableKey)
            ?.putBoolean(emailPasswordAuthKey, configuration.capabilities.emailPasswordAuth)
            ?.putBoolean(tvLoginKey, configuration.capabilities.tvLogin)
            ?.putBoolean(watchPartyKey, configuration.capabilities.watchParty)
            ?.putString(discoveryUrlKey, configuration.discoveryUrl)
            ?.commit() == true

    actual fun useOfficial(): Boolean = preferences?.edit()?.clear()?.commit() == true
}
