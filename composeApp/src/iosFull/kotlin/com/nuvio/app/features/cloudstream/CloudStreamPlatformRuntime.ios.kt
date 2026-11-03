package com.nuvio.app.features.cloudstream

internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = false

    actual fun initialize(context: Any?) = Unit
    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? = null
    actual suspend fun openSettings(plugin: CloudStreamPluginItem): Boolean = false
    actual suspend fun hasSettings(plugin: CloudStreamPluginItem): Boolean = false
    actual fun unload(pluginId: String) = Unit
    actual fun clear() = Unit
}
