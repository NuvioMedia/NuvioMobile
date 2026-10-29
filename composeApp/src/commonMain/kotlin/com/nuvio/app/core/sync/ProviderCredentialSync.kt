package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS = 500L

private data class ProviderCredentialScope(
    val userId: String,
    val profileId: Int,
)

object ProviderCredentialSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProviderCredentialSync")
    private val syncMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private val observedSnapshots = mutableMapOf<Int, ProviderCredentialSnapshot>()
    private val baselineSnapshots = mutableMapOf<ProviderCredentialScope, ProviderCredentialSnapshot>()
    private var observeJob: Job? = null
    private var isApplyingRemote = false

    @OptIn(FlowPreview::class)
    fun startObserving() {
        if (observeJob?.isActive == true) return
        ensureRepositoriesLoaded()
        observeJob = scope.launch {
            observeCredentialSnapshots()
                .distinctUntilChanged()
                .debounce(PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS)
                .collect(::handleLocalSnapshot)
        }
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
        synchronized(stateLock) {
            observedSnapshots.clear()
            baselineSnapshots.clear()
        }
    }

    internal fun onProfileChanged() {
        if (observeJob?.isActive != true) return
        ensureRepositoriesLoaded()
        val profileId = ProfileRepository.activeProfileId
        val snapshot = currentSnapshot(profileId)
        val credentialScope = currentScope(profileId)
        synchronized(stateLock) {
            observedSnapshots[profileId] = snapshot
            if (credentialScope != null) {
                baselineSnapshots[credentialScope] = snapshot
            }
        }
    }

    suspend fun syncFromRemote(profileId: Int): Boolean = syncMutex.withLock {
        ensureRepositoriesLoaded()
        val credentialScope = currentScope(profileId) ?: return@withLock false
        try {
            val localSnapshot = currentSnapshot(profileId)
            val rows = pullRows(profileId)
            requireCurrentScope(credentialScope)
            val remoteSnapshot = localSnapshot.mergeRemote(rows)
            val applied = remoteSnapshot != localSnapshot
            if (applied) {
                isApplyingRemote = true
                try {
                    applySnapshot(remoteSnapshot, credentialScope)
                } finally {
                    isApplyingRemote = false
                }
            }
            requireCurrentScope(credentialScope)
            synchronized(stateLock) {
                observedSnapshots[profileId] = remoteSnapshot
                baselineSnapshots[credentialScope] = remoteSnapshot
            }
            log.d { "Synchronized ${remoteSnapshot.values.size} credentials for profile $profileId applied=$applied" }
            applied
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AuthRepository.signOutIfSessionInvalid(error, "Provider credential sync")
            log.e(error) { "Provider credential sync failed for profile $profileId" }
            throw error
        }
    }

    private suspend fun pushSnapshot(snapshot: ProviderCredentialSnapshot) {
        SupabaseProvider.client.postgrest.rpc(
            function = "sync_push_provider_credentials",
            parameters = credentialParams(snapshot),
        )
        log.d { "Pushed ${snapshot.values.size} credentials for profile ${snapshot.profileId}" }
    }

    private suspend fun pullRows(profileId: Int): List<SupabaseProviderCredential> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        return SupabaseProvider.client.postgrest
            .rpc("sync_pull_provider_credentials", params)
            .decodeList()
    }

    private fun credentialParams(snapshot: ProviderCredentialSnapshot) = buildJsonObject {
        put("p_profile_id", snapshot.profileId)
        put("p_credentials", buildJsonArray {
            snapshot.values.forEach { credential ->
                add(buildJsonObject {
                    put("provider", credential.provider)
                    put("credential_json", credential.credentialJson())
                })
            }
        })
        putSyncOriginClientId()
    }

    private fun observeCredentialSnapshots() = combine(
        ProfileRepository.state,
        DebridSettingsRepository.uiState,
        TmdbSettingsRepository.uiState,
        MdbListSettingsRepository.uiState,
        PlayerSettingsRepository.uiState,
    ) { _, debrid, tmdb, mdbList, player ->
        buildSnapshot(
            profileId = ProfileRepository.activeProfileId,
            debrid = debrid,
            tmdb = tmdb,
            mdbList = mdbList,
            player = player,
        )
    }

    private fun currentSnapshot(profileId: Int): ProviderCredentialSnapshot {
        check(ProfileRepository.activeProfileId == profileId)
        val snapshot = buildSnapshot(
            profileId = profileId,
            debrid = DebridSettingsRepository.snapshot(),
            tmdb = TmdbSettingsRepository.snapshot(),
            mdbList = MdbListSettingsRepository.snapshot(),
            player = PlayerSettingsRepository.uiState.value,
        )
        check(ProfileRepository.activeProfileId == profileId)
        return snapshot
    }

    internal fun buildSnapshot(
        profileId: Int,
        debrid: DebridSettings,
        tmdb: TmdbSettings,
        mdbList: MdbListSettings,
        player: PlayerSettingsUiState,
    ): ProviderCredentialSnapshot = ProviderCredentialSnapshot(
        profileId = profileId,
        values = buildList {
            DebridProviders.all().forEach { provider ->
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.debrid(provider.id),
                        field = PROVIDER_API_KEY_FIELD,
                        value = debrid.apiKeyFor(provider.id).trim(),
                    ),
                )
            }
            add(ProviderCredentialValue(ProviderCredentialIds.TMDB, PROVIDER_API_KEY_FIELD, tmdb.apiKey.trim()))
            add(ProviderCredentialValue(ProviderCredentialIds.MDBLIST, PROVIDER_API_KEY_FIELD, mdbList.apiKey.trim()))
            add(
                ProviderCredentialValue(
                    ProviderCredentialIds.ANIMESKIP,
                    PROVIDER_CLIENT_ID_FIELD,
                    player.animeSkipClientId.trim(),
                ),
            )
            add(
                ProviderCredentialValue(
                    ProviderCredentialIds.INTRODB,
                    PROVIDER_API_KEY_FIELD,
                    player.introDbApiKey.trim(),
                ),
            )
        },
    )

    private suspend fun applySnapshot(
        snapshot: ProviderCredentialSnapshot,
        expectedScope: ProviderCredentialScope,
    ) {
        snapshot.values.forEach { credential ->
            requireCurrentScope(expectedScope)
            when {
                credential.provider.startsWith("debrid:") -> {
                    DebridSettingsRepository.setProviderApiKey(
                        credential.provider.substringAfter("debrid:"),
                        credential.value,
                    )
                }
                credential.provider == ProviderCredentialIds.TMDB -> {
                    TmdbSettingsRepository.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.MDBLIST -> {
                    MdbListSettingsRepository.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.ANIMESKIP -> {
                    PlayerSettingsRepository.setAnimeSkipClientId(credential.value)
                }
                credential.provider == ProviderCredentialIds.INTRODB -> {
                    PlayerSettingsRepository.setIntroDbApiKey(credential.value)
                }
            }
        }
    }

    private suspend fun handleLocalSnapshot(snapshot: ProviderCredentialSnapshot) {
        if (isApplyingRemote) return
        syncMutex.withLock {
            if (ProfileRepository.activeProfileId != snapshot.profileId) return@withLock
            if (snapshot != currentSnapshot(snapshot.profileId)) return@withLock
            val previous = synchronized(stateLock) {
                observedSnapshots.put(snapshot.profileId, snapshot)
            }
            val credentialScope = currentScope(snapshot.profileId) ?: return@withLock
            if (previous == null) {
                synchronized(stateLock) {
                    if (credentialScope !in baselineSnapshots) {
                        baselineSnapshots[credentialScope] = snapshot
                    }
                }
                return@withLock
            }
            if (previous == snapshot) return@withLock
            val baseline = synchronized(stateLock) {
                baselineSnapshots.getOrPut(credentialScope) { previous }
            }
            if (baseline == snapshot) return@withLock

            try {
                pushSnapshot(snapshot)
                synchronized(stateLock) {
                    baselineSnapshots[credentialScope] = snapshot
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AuthRepository.signOutIfSessionInvalid(error, "Provider credential push")
                log.e(error) { "Failed to push provider credentials for profile ${snapshot.profileId}" }
            }
        }
    }

    private fun currentScope(profileId: Int): ProviderCredentialScope? {
        val state = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        if (state.isAnonymous || ProfileRepository.activeProfileId != profileId) return null
        return ProviderCredentialScope(state.userId, profileId)
    }

    private fun requireCurrentScope(expected: ProviderCredentialScope) {
        if (currentScope(expected.profileId) != expected) {
            throw CancellationException("Provider credential sync target changed")
        }
    }

    private fun ensureRepositoriesLoaded() {
        DebridSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
    }
}
