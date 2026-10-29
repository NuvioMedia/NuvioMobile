package com.nuvio.app.features.addons

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.generic_addon
import org.jetbrains.compose.resources.getString

data class AddonManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val logoUrl: String? = null,
    val resources: List<AddonResource>,
    val types: List<String>,
    val idPrefixes: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    val behaviorHints: AddonBehaviorHints = AddonBehaviorHints(),
    val transportUrl: String,
)

data class AddonResource(
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String> = emptyList(),
)

fun AddonManifest.supportsResource(name: String, type: String, id: String): Boolean =
    resources.any { resource ->
        resource.name == name &&
            resource.types.contains(type) &&
            (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
    }

data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<AddonExtraProperty> = emptyList(),
)

data class AddonExtraProperty(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int? = null,
)

data class AddonBehaviorHints(
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
    val adult: Boolean = false,
    val p2p: Boolean = false,
)

data class ManagedAddon(
    val manifestUrl: String,
    val manifest: AddonManifest? = null,
    val userSetName: String? = null,
    val enabled: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = enabled && manifest != null

    val displayTitle: String
        get() = userSetName?.takeIf { it.isNotBlank() && it != manifest?.name }
            ?: manifest?.name
            ?: manifestUrl.substringBefore("?").substringAfterLast("/").ifBlank {
                runBlocking { getString(Res.string.generic_addon) }
            }
}

data class AddonsUiState(
    val addons: List<ManagedAddon> = emptyList(),
)

data class AddonOverview(
    val totalAddons: Int,
    val activeAddons: Int,
    val totalCatalogs: Int,
)

internal fun List<ManagedAddon>.toOverview(): AddonOverview =
    AddonOverview(
        totalAddons = size,
        activeAddons = count { it.isActive },
        totalCatalogs = filter { it.enabled }.sumOf { it.manifest?.catalogs?.size ?: 0 },
    )

internal fun List<ManagedAddon>.enabledAddons(): List<ManagedAddon> =
    filter { it.enabled }

internal fun List<ManagedAddon>.hasPendingEnabledManifests(): Boolean =
    any { addon -> addon.enabled && addon.isRefreshing }

internal fun List<ManagedAddon>.isWaitingForFirstEnabledManifest(): Boolean {
    val enabledAddons = enabledAddons()
    return enabledAddons.isNotEmpty() &&
        enabledAddons.none { addon -> addon.manifest != null } &&
        enabledAddons.any { addon -> addon.isRefreshing }
}

internal fun List<ManagedAddon>.firstEnabledManifestError(): String? =
    asSequence()
        .filter { addon -> addon.enabled && addon.manifest == null }
        .mapNotNull { addon -> addon.errorMessage?.takeIf(String::isNotBlank) }
        .firstOrNull()

sealed interface AddAddonResult {
    data class Success(val manifest: AddonManifest) : AddAddonResult
    data class Error(val message: String) : AddAddonResult
}
