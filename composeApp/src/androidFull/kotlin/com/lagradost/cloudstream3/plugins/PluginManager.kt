package com.lagradost.cloudstream3.plugins

import java.io.File

/**
 * Small compatibility surface for providers that inspect the CloudStream plugin registry.
 * Nuvio owns download/update state; downloaded plugins cannot invoke CloudStream's app UI.
 */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

object PluginManager {
    private val localPluginData = linkedMapOf<String, PluginData>()
    private val onlinePluginData = linkedMapOf<String, PluginData>()
    private val loadedPlugins = linkedMapOf<String, BasePlugin>()

    var currentlyLoading: String? = null
    val plugins: Map<String, BasePlugin> get() = synchronized(this) { loadedPlugins.toMap() }
    val urlPlugins: Map<String, BasePlugin> get() = synchronized(this) {
        onlinePluginData.values.mapNotNull { data ->
            data.url?.let { url -> loadedPlugins[data.filePath]?.let { url to it } }
        }.toMap()
    }
    val pluginsLocal: Array<PluginData> get() = synchronized(this) { localPluginData.values.toTypedArray() }
    val pluginsOnline: Array<PluginData> get() = synchronized(this) { onlinePluginData.values.toTypedArray() }
    val loadedLocalPlugins: Boolean get() = synchronized(this) { localPluginData.isNotEmpty() }
    val loadedOnlinePlugins: Boolean get() = synchronized(this) { onlinePluginData.isNotEmpty() }

    fun register(data: PluginData, plugin: BasePlugin) = synchronized(this) {
        loadedPlugins[data.filePath] = plugin
        if (data.isOnline) onlinePluginData[data.filePath] = data else localPluginData[data.filePath] = data
    }

    fun unregister(filePath: String) = synchronized(this) {
        loadedPlugins.remove(filePath)
        localPluginData.remove(filePath)
        onlinePluginData.remove(filePath)
    }

    /** Nuvio owns installed packages; plugins may request deletion but cannot mutate host state. */
    suspend fun deletePlugin(file: File): Boolean = false

    fun clear() = synchronized(this) {
        currentlyLoading = null
        loadedPlugins.clear()
        localPluginData.clear()
        onlinePluginData.clear()
    }
}
