package com.nuvio.app.features.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = true

    private const val launcherPackage = "com.nuvio.app.launcher"
    private val launcherComponents = AppIconOption.entries.map { option ->
        option.platformName to "$launcherPackage.${option.platformName ?: "AppIconDefault"}"
    }

    private var context: Context? = null

    actual fun loadSelectedIconKey(): String? =
        context?.getSharedPreferences("app_icon_settings", Context.MODE_PRIVATE)
            ?.getString("selected_app_icon", null)

    actual fun saveSelectedIconKey(key: String) {
        context?.getSharedPreferences("app_icon_settings", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("selected_app_icon", key)
            ?.apply()
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        this.context = appContext
        restoreDefaultIfNeeded(appContext)
    }

    actual fun currentIconName(): String? {
        val appContext = context ?: return null
        return currentIconName(appContext)
    }

    fun currentLauncherIconResource(context: Context): Int {
        val option = AppIconOption.fromPlatformName(currentIconName(context))
        val resourceName = if (option == AppIconOption.ORIGINAL) {
            "ic_launcher"
        } else {
            "ic_launcher_${option.key}"
        }
        return context.resources
            .getIdentifier(resourceName, "mipmap", context.packageName)
            .takeIf { it != 0 }
            ?: com.nuvio.app.R.mipmap.ic_launcher
    }

    fun currentLauncherComponent(context: Context): ComponentName {
        val currentName = currentIconName(context)
        val className = launcherComponents.first { it.first == currentName }.second
        return component(context, className)
    }

    private fun currentIconName(context: Context): String? {
        val packageManager = context.packageManager
        val explicitlyEnabled = launcherComponents.firstOrNull { (_, className) ->
            packageManager.getComponentEnabledSetting(component(context, className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        return explicitlyEnabled?.first
    }

    private fun restoreDefaultIfNeeded(context: Context) {
        val packageManager = context.packageManager
        val hasEnabledComponent = launcherComponents.any { (_, className) ->
            packageManager.getComponentEnabledSetting(component(context, className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (hasEnabledComponent) return

        val defaultClass = launcherComponents.first { it.first == null }.second
        if (
            packageManager.getComponentEnabledSetting(component(context, defaultClass)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            return
        }
        packageManager.setComponentEnabledSetting(
            component(context, defaultClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    actual suspend fun activateIcon(name: String?): Boolean {
        val appContext = context ?: return false
        val selectedClass = launcherComponents.firstOrNull { it.first == name }?.second ?: return false
        val packageManager = appContext.packageManager

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    launcherComponents.map { (_, className) ->
                        PackageManager.ComponentEnabledSetting(
                            component(appContext, className),
                            if (className == selectedClass) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            },
                            PackageManager.DONT_KILL_APP,
                        )
                    },
                )
            } else {
                launcherComponents.forEach { (_, className) ->
                    packageManager.setComponentEnabledSetting(
                        component(appContext, className),
                        if (className == selectedClass) {
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        } else {
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        },
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }.isSuccess
    }

    private fun component(context: Context, className: String): ComponentName =
        ComponentName(context.packageName, className)
}
