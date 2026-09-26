package com.nuvio.app.features.servers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncedServer(
    val id: String,
    @SerialName("provider_id") val providerId: String,
    val name: String,
    val address: String,
    @SerialName("remote_server_id") val remoteServerId: String,
    @SerialName("remote_user_id") val remoteUserId: String,
    @SerialName("user_name") val userName: String,
    val token: String,
    val libraries: List<SyncedLibrary> = emptyList(),
    val enabled: Boolean = true,
    @SerialName("use_catalog_metadata") val useCatalogMetadata: Boolean = false,
) {
    val key: String
        get() = serverKey(providerId, remoteServerId, remoteUserId)

    override fun toString(): String = "SyncedServer(id=$id, provider=$providerId)"
}

@Serializable
data class SyncedLibrary(
    val id: String,
    val name: String,
    val kind: String,
    val selected: Boolean,
)

@Serializable
internal data class ServerSyncState(
    val pendingPush: Boolean = false,
    val syncedKeys: List<String>? = null,
)

class ServerSyncSnapshot(
    val profileId: Int,
    val version: Long,
    val servers: List<SyncedServer>,
    val pendingPush: Boolean,
    val syncedKeys: Set<String>?,
)

fun serverKey(providerId: String, remoteServerId: String, remoteUserId: String): String =
    "$providerId|$remoteServerId|$remoteUserId"

internal fun mergeSyncedServers(
    local: List<SyncedServer>,
    remote: List<SyncedServer>,
    syncedKeys: Set<String>?,
): List<SyncedServer> {
    val localByKey = local.associateBy { it.key }
    val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
    val kept = remote.map { server -> localByKey[server.key]?.let { server.copy(id = it.id) } ?: server }
    val added = local.filter { it.key !in remoteKeys && (syncedKeys == null || it.key !in syncedKeys) }
    return kept + added
}

internal fun ServerConnection.toSynced(token: String) = SyncedServer(
    id = id,
    providerId = providerId,
    name = name,
    address = address,
    remoteServerId = remoteServerId,
    remoteUserId = remoteUserId,
    userName = userName,
    token = token,
    libraries = libraries.map { SyncedLibrary(it.id, it.name, it.kind.contentType, it.selected) },
    enabled = enabled,
    useCatalogMetadata = useCatalogMetadata,
)

internal fun SyncedServer.toConnection(id: String, credentialRef: String) = ServerConnection(
    id = id,
    providerId = providerId,
    name = name,
    address = address,
    remoteServerId = remoteServerId,
    remoteUserId = remoteUserId,
    userName = userName,
    credentialRef = credentialRef,
    libraries = libraries.mapNotNull { library ->
        ServerMediaKind.entries.firstOrNull { it.contentType == library.kind }?.let { kind ->
            ServerLibrary(library.id, library.name, kind, library.selected)
        }
    },
    enabled = enabled,
    useCatalogMetadata = useCatalogMetadata,
)
