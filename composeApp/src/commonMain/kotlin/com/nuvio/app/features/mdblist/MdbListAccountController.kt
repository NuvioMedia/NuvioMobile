package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MdbListAccountStatus(
    val scope: MdbListAuthScope? = null,
    val isBusy: Boolean = false,
    val error: MdbListSyncError? = null,
    val authError: MdbListAuthError? = null,
    val revokeFailed: Boolean = false,
)

class MdbListAccountController(
    private val auth: MdbListAuthRepository,
    private val store: MdbListAuthStore,
    private val coroutineScope: CoroutineScope,
    private val onConnected: suspend (MdbListAuthScope) -> Unit,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val actions = Mutex()
    private var pollJob: Job? = null
    private val mutableStatus = MutableStateFlow(MdbListAccountStatus())
    val status = mutableStatus.asStateFlow()

    suspend fun connect(requestedScope: MdbListAuthScope = store.scope()): String? {
        return actions.withLock {
            store.checkScope(requestedScope)
            if (auth.state.value.isAuthenticated) return@withLock null
            mutableStatus.value = MdbListAccountStatus(requestedScope, isBusy = true)
            try {
                val pending = auth.state.value.session?.takeIf { it.expiresAtEpochMs > now() }
                    ?: auth.startDeviceAuthorization(requestedScope)
                mutableStatus.value = MdbListAccountStatus(store.scope())
                resumePolling()
                pending.verificationUriComplete
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (store.isCurrent(requestedScope)) mutableStatus.value = MdbListAccountStatus(
                    requestedScope, error = error.toMdbListSyncError(), authError = (error as? MdbListAuthException)?.error)
                null
            } finally {
                if (mutableStatus.value.scope == requestedScope) mutableStatus.value = mutableStatus.value.copy(isBusy = false)
            }
        }
    }

    fun resumePolling(scope: MdbListAuthScope = store.scope()): String? {
        store.checkScope(scope)
        val session = auth.state.value.session ?: return null
        if (pollJob?.isActive != true) pollJob = coroutineScope.launch {
            try {
                while (store.isCurrent(scope) && auth.state.value.session != null) {
                    val current = auth.state.value.session ?: break
                    delay((current.nextPollAtEpochMs - now()).coerceAtLeast(250L))
                    when (auth.pollDeviceAuthorization(scope)) {
                        MdbListDevicePollResult.Pending -> Unit
                        MdbListDevicePollResult.Authorized -> {
                            onConnected(store.scope())
                            return@launch
                        }
                        MdbListDevicePollResult.Denied, MdbListDevicePollResult.Expired -> return@launch
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (store.scope().profileId == scope.profileId) {
                    mutableStatus.value = MdbListAccountStatus(store.scope(), error = error.toMdbListSyncError(),
                        authError = (error as? MdbListAuthException)?.error)
                }
            }
        }
        return session.verificationUriComplete
    }

    fun cancel(scope: MdbListAuthScope = store.scope()) {
        store.checkScope(scope)
        stopPolling()
        auth.cancelDeviceAuthorization()
        mutableStatus.value = MdbListAccountStatus(store.scope())
    }

    suspend fun disconnect(scope: MdbListAuthScope = store.scope()) = actions.withLock {
        store.checkScope(scope)
        stopPolling()
        mutableStatus.value = MdbListAccountStatus(scope, isBusy = true)
        val disconnectedScope = scope.copy(generation = scope.generation + 1)
        try {
            val revoked = auth.disconnect(scope)
            if (store.isCurrent(disconnectedScope)) {
                mutableStatus.value = MdbListAccountStatus(disconnectedScope, revokeFailed = !revoked)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when {
                store.isCurrent(disconnectedScope) -> mutableStatus.value = MdbListAccountStatus(disconnectedScope, revokeFailed = true)
                store.isCurrent(scope) -> mutableStatus.value = MdbListAccountStatus(scope, error = error.toMdbListSyncError())
            }
        } finally {
            if (mutableStatus.value.scope == scope) mutableStatus.value = mutableStatus.value.copy(isBusy = false)
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
