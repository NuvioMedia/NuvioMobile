package com.nuvio.app.features.servers

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
    @SerialName("import_watch_state") val importWatchState: Boolean? = null,
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

/**
 * Server types the account backend has refused. One refused entry fails the whole upload, so these are left out for a
 * while and the other servers keep syncing; they join in by themselves once the backend accepts them.
 */
internal class RefusedServerTypes(private val now: () -> Long) {
    private val lock = SynchronizedObject()
    private val refusedAt = mutableMapOf<String, Long>()

    fun isRefused(providerId: String): Boolean = synchronized(lock) {
        val at = refusedAt[providerId]
        when {
            at == null -> false
            now() - at < RETRY_AFTER_MS -> true
            else -> {
                refusedAt.remove(providerId)
                false
            }
        }
    }

    /** Sends [snapshot] without any refused type and returns what the backend accepted. */
    suspend fun upload(snapshot: ServerSyncSnapshot, send: suspend (ServerSyncSnapshot) -> Unit): ServerSyncSnapshot {
        var allowed = accepted(snapshot)
        while (true) {
            try {
                send(allowed)
                return allowed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val provider = refusedProvider(error)?.takeIf { id -> allowed.servers.any { it.providerId == id } }
                    ?: throw error
                synchronized(lock) { refusedAt[provider] = now() }
                allowed = accepted(allowed)
            }
        }
    }

    private fun accepted(snapshot: ServerSyncSnapshot) = ServerSyncSnapshot(
        profileId = snapshot.profileId,
        version = snapshot.version,
        servers = snapshot.servers.filterNot { isRefused(it.providerId) },
        pendingPush = snapshot.pendingPush,
        syncedKeys = snapshot.syncedKeys,
    )

    private fun refusedProvider(error: Throwable): String? =
        generateSequence(error) { it.cause }
            .firstNotNullOfOrNull { failure -> failure.message?.let(REFUSED_PROVIDER::find)?.groupValues?.get(1) }

    private companion object {
        const val RETRY_AFTER_MS = 24 * 60 * 60_000L
        val REFUSED_PROVIDER = Regex("Unsupported media server provider: ([A-Za-z0-9_-]+)")
    }
}

private val syncJson = Json { encodeDefaults = true }

fun List<SyncedServer>.toSyncPayload(): JsonElement = syncJson.encodeToJsonElement(this)

fun serverKey(providerId: String, remoteServerId: String, remoteUserId: String): String =
    "$providerId|$remoteServerId|$remoteUserId"

internal fun mergeSyncedServers(
    local: List<SyncedServer>,
    remote: List<SyncedServer>,
    syncedKeys: Set<String>?,
): List<SyncedServer> {
    val localByKey = local.associateBy { it.key }
    val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
    val kept = remote.map { server ->
        localByKey[server.key]?.let { local ->
            server.copy(id = local.id, importWatchState = server.importWatchState ?: local.importWatchState)
        } ?: server
    }
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
    importWatchState = importWatchState,
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
    importWatchState = importWatchState ?: false,
)
