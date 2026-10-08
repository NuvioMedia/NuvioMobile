package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerSyncSnapshot
import com.nuvio.app.features.servers.SyncedServer
import com.nuvio.app.features.servers.mergeSyncedServers
import com.nuvio.app.features.servers.toSyncPayload
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MEDIA_SERVER_PUSH_DEBOUNCE_MS = 500L

@Serializable
private data class SupabaseMediaServers(
    @SerialName("servers_json") val servers: List<SyncedServer> = emptyList(),
)

object MediaServerSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("MediaServerSync")
    private val syncMutex = Mutex()
    private var observeJob: Job? = null

    @OptIn(FlowPreview::class)
    fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            ServerRepository.localChanges
                .debounce(MEDIA_SERVER_PUSH_DEBOUNCE_MS)
                .collect { profileId ->
                    try {
                        syncMutex.withLock { sync(profileId, pushOnly = true) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        AuthRepository.signOutIfSessionInvalid(error, "Media server push")
                        log.e(error) { "Failed to push media servers for profile $profileId" }
                    }
                }
        }
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
    }

    suspend fun syncFromRemote(profileId: Int): Boolean = syncMutex.withLock {
        try {
            sync(profileId, pushOnly = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AuthRepository.signOutIfSessionInvalid(error, "Media server sync")
            log.e(error) { "Media server sync failed for profile $profileId" }
            throw error
        }
    }

    private suspend fun sync(profileId: Int, pushOnly: Boolean): Boolean {
        if (!canSync(profileId)) return false
        val local = ServerRepository.syncSnapshot(profileId) ?: return false
        if (local.syncedKeys != null && (pushOnly || local.pendingPush)) {
            if (local.pendingPush) push(local)
            return false
        }
        val remote = pull(profileId)
        if (!canSync(profileId)) return false
        if (remote == null) {
            if (local.servers.isNotEmpty() || local.pendingPush) push(local)
            return false
        }
        val merged = mergeSyncedServers(local.servers, remote, local.syncedKeys)
        val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
        if (!ServerRepository.applySync(local, merged, remoteKeys)) return false
        if (merged.any { it.key !in remoteKeys }) {
            ServerRepository.syncSnapshot(profileId)?.let { push(it) }
        }
        log.d { "Synchronized ${merged.size} media servers for profile $profileId" }
        return merged != local.servers
    }

    private suspend fun pull(profileId: Int): List<SyncedServer>? {
        val params = buildJsonObject { put("p_profile_id", profileId) }
        return SupabaseProvider.client.postgrest
            .rpc("sync_pull_media_servers", params)
            .decodeList<SupabaseMediaServers>()
            .firstOrNull()
            ?.servers
    }

    private suspend fun push(snapshot: ServerSyncSnapshot) {
        SupabaseProvider.client.postgrest.rpc(
            function = "sync_push_media_servers",
            parameters = buildJsonObject {
                put("p_profile_id", snapshot.profileId)
                put("p_servers", snapshot.servers.toSyncPayload())
                putSyncOriginClientId()
            },
        )
        ServerRepository.markPushed(snapshot)
        log.d { "Pushed ${snapshot.servers.size} media servers for profile ${snapshot.profileId}" }
    }

    private fun canSync(profileId: Int): Boolean {
        val state = AuthRepository.state.value as? AuthState.Authenticated ?: return false
        return !state.isAnonymous && ProfileRepository.activeProfileId == profileId
    }
}
