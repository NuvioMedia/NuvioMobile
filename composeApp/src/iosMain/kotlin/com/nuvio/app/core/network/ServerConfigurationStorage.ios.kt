package com.nuvio.app.core.network

import platform.Foundation.NSUserDefaults

internal actual object ServerConfigurationStorage {
    private const val customEnabledKey = "server_custom_enabled"
    private const val backendUrlKey = "server_backend_url"
    private const val publishableKey = "server_publishable_key"
    private const val emailPasswordAuthKey = "server_email_password_auth"
    private const val tvLoginKey = "server_tv_login"
    private const val watchPartyKey = "server_watch_party"
    private const val discoveryUrlKey = "server_discovery_url"

    actual fun loadCustom(): ServerConfiguration? {
        val values = NSUserDefaults.standardUserDefaults
        if (!values.boolForKey(customEnabledKey)) return null
        val backendUrl = values.stringForKey(backendUrlKey)?.trim().orEmpty()
        val key = values.stringForKey(publishableKey)?.trim().orEmpty()
        val emailPasswordAuth = values.boolForKey(emailPasswordAuthKey)
        val tvLogin = values.boolForKey(tvLoginKey)
        if (backendUrl.isBlank() || key.isBlank() || !emailPasswordAuth) return null
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = key,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
                watchParty = values.boolForKey(watchPartyKey),
            ),
            isCustom = true,
            discoveryUrl = values.stringForKey(discoveryUrlKey),
        )
    }

    actual fun saveCustom(configuration: ServerConfiguration): Boolean {
        val values = NSUserDefaults.standardUserDefaults
        values.setBool(true, forKey = customEnabledKey)
        values.setObject(configuration.backendUrl, forKey = backendUrlKey)
        values.setObject(configuration.publishableKey, forKey = publishableKey)
        values.setBool(configuration.capabilities.emailPasswordAuth, forKey = emailPasswordAuthKey)
        values.setBool(configuration.capabilities.tvLogin, forKey = tvLoginKey)
        values.setBool(configuration.capabilities.watchParty, forKey = watchPartyKey)
        values.setObject(configuration.discoveryUrl, forKey = discoveryUrlKey)
        return values.synchronize()
    }

    actual fun useOfficial(): Boolean {
        val values = NSUserDefaults.standardUserDefaults
        values.removeObjectForKey(customEnabledKey)
        values.removeObjectForKey(backendUrlKey)
        values.removeObjectForKey(publishableKey)
        values.removeObjectForKey(emailPasswordAuthKey)
        values.removeObjectForKey(tvLoginKey)
        values.removeObjectForKey(watchPartyKey)
        values.removeObjectForKey(discoveryUrlKey)
        return values.synchronize()
    }
}
