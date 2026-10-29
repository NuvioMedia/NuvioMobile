package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.reflect.KClass

internal class NuvioNavigator(
    private val backStack: NavBackStack<NavKey>,
    private val onExternalNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    private val onExternalBack: (() -> Unit)? = null,
    private val onExternalReplace: ((AppRoute) -> Unit)? = null,
    private val onLocalNavigate: ((AppRoute?, AppRoute) -> Unit)? = null,
    private val onLocalPop: (() -> Unit)? = null,
) {
    val currentRoute: AppRoute?
        get() = backStack.lastOrNull() as? AppRoute

    val routes: List<AppRoute>
        get() = backStack.filterIsInstance<AppRoute>()

    fun navigate(route: AppRoute, options: NuvioNavigateOptions.() -> Unit = {}) {
        val resolvedOptions = NuvioNavigateOptions().apply(options)

        if (resolvedOptions.launchSingleTop && currentRoute == route) return

        val popUpToRoute = resolvedOptions.popUpToRoute
        if (popUpToRoute != null) {
            val targetIndex = backStack.indexOfLast { popUpToRoute.isInstance(it) }
            if (targetIndex >= 0) {
                if (
                    onExternalReplace != null &&
                    resolvedOptions.popUpToInclusive &&
                    targetIndex == backStack.lastIndex
                ) {
                    onExternalReplace(route)
                    return
                }

                val firstRemovedIndex = if (resolvedOptions.popUpToInclusive) targetIndex else targetIndex + 1
                if (firstRemovedIndex <= backStack.lastIndex) {
                    backStack.subList(firstRemovedIndex, backStack.size).clear()
                }
            }
        }

        if (resolvedOptions.launchSingleTop && currentRoute == route) return
        if (onExternalNavigate != null) {
            onExternalNavigate.invoke(route, resolvedOptions.launchSingleTop)
        } else {
            onLocalNavigate?.invoke(currentRoute, route)
            backStack.add(route)
        }
    }

    fun popBackStack(expectedRoute: AppRoute? = null): Boolean {
        if (expectedRoute != null && currentRoute != expectedRoute) return false
        if (backStack.size > 1) {
            onLocalPop?.invoke()
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        onExternalBack?.invoke()
        return onExternalBack != null
    }
}

internal class NuvioNavigateOptions {
    var launchSingleTop: Boolean = false
    internal var popUpToRoute: KClass<out AppRoute>? = null
    internal var popUpToInclusive: Boolean = false

    inline fun <reified T : AppRoute> popUpTo(noinline options: NuvioPopUpToOptions.() -> Unit = {}) {
        val resolved = NuvioPopUpToOptions().apply(options)
        popUpToRoute = T::class
        popUpToInclusive = resolved.inclusive
    }
}

internal class NuvioPopUpToOptions {
    var inclusive: Boolean = false
}
