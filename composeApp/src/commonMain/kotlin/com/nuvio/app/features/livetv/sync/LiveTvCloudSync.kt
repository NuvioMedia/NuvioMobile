package com.nuvio.app.features.livetv.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.exportForSync
import com.nuvio.app.features.livetv.data.optInt
import com.nuvio.app.features.livetv.data.optJSONObject
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.data.syncHasSources
import com.nuvio.app.features.livetv.model.LiveTvClock
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the settings card and the first-time questions show for Setup Sync. */
data class CloudSyncState(
    /** Only on the official Nuvio server: that is the sign-in the sync service checks. */
    val available: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L,
    /** A question waiting for the person (shown by [LiveTvCloudSyncPrompts]). */
    val prompt: CloudPrompt? = null,
)

enum class CloudPrompt {
    /** Nothing saved for this account yet, and this device has a Live TV setup. */
    BackUp,

    /** This account already has a saved setup, and this device has a different one. */
    Choose,
}

enum class CloudChoice { UseSaved, Combine, Later }

/**
 * Keeps the Live TV setup the same on every device signed in to the same Nuvio account (the TV app
 * does the same, with the same saved copy).
 *
 * It uses the Nuvio sign-in this app already has: the access token is sent to our own small service
 * (the livetv-cloud Worker), which asks Nuvio whose token it is and keeps one saved copy per
 * account. Nothing is written to Nuvio's servers, and the token is never stored here.
 *
 * What is synced is exactly what the Google Drive backup syncs ([exportForSync]), combined the same
 * way ([LiveTvDriveSync.merge]): setting by setting against what both sides agreed on last time,
 * playlists and guides source by source. A device never overwrites a copy it hasn't seen: the
 * service refuses a save made from an out-of-date copy, and the next round combines.
 *
 * When it asks, by situation:
 *  - signed in on a device with no setup, and a saved copy exists: restored, no question;
 *  - an account with no saved copy and a device with a setup: asks once whether to back it up;
 *  - an account with a saved copy and a device with a different setup: asks once what to do;
 *  - after that: syncs by itself (when Live TV settings change, every 15 minutes, and at start).
 */
internal object LiveTvCloudSync {
    private val prefs = LiveTvPreferences
    private val drive = LiveTvDriveSync
    private val store = LiveTvDriveHttp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(CloudSyncState())
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private var started = false
    private var userId: String? = null

    /** True while a saved setup is being written here, so it isn't saved straight back. */
    private var applying = false

    /** Only the official Nuvio server: that is the sign-in the sync service checks. */
    private fun isOfficialServer(): Boolean {
        val config = ServerConfigurationRepository.active.value
        if (config.isCustom) return false
        val host = config.backendUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        return host.equals("api.nuvio.tv", ignoreCase = true)
    }

    // ------------------------------------------------------------------ start

    /** Starts watching the Nuvio sign-in. Called once when the app opens. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        val available = isOfficialServer()
        _state.value = CloudSyncState(available = available, lastSyncMs = store.getLong(KEY_LAST_SYNC))
        if (!available) return
        // A sign-in during this session, or an account that was already signed in when the app opened.
        scope.launch { AuthRepository.state.collect { onAuthState(it) } }
        // Live TV settings changed: sync shortly after (not for per-device things like the last
        // channel watched, which aren't part of the setup).
        scope.launch {
            prefs.changes.drop(1).debounce(8_000).collect {
                if (!applying && isOn()) syncIfChanged()
            }
        }
        scope.launch {
            while (isActive) {
                delay(15 * 60_000L)
                if (isOn()) syncNow()
            }
        }
    }

    private suspend fun onAuthState(authState: AuthState) {
        when (authState) {
            is AuthState.Authenticated -> {
                // A guest (anonymous) session has no Nuvio account to save under.
                if (authState.isAnonymous) {
                    userId = null
                    _state.value = _state.value.copy(signedIn = false, email = null, enabled = false, prompt = null)
                    return
                }
                // Let the app finish opening before the first round.
                delay(5_000)
                userId = authState.userId
                val on = isEnabled(authState.userId)
                _state.value = _state.value.copy(signedIn = true, email = authState.email, enabled = on)
                mutex.withLock {
                    if (on) reconcileAndReport(authState.userId) else decideFirstTime(authState.userId, explicit = false)
                }
            }
            AuthState.Unauthenticated -> {
                userId = null
                // This device keeps its setup; it just stops syncing until someone signs in again.
                _state.value = _state.value.copy(signedIn = false, email = null, enabled = false, prompt = null)
            }
            AuthState.Loading -> Unit
        }
    }

    // ------------------------------------------------------------------ what the settings card does

    fun syncNow() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                if (isEnabled(uid)) reconcileAndReport(uid, doneMessage = "Up to date.")
            }
        }
    }

    /** "Turn On" in settings: the same as accepting the question. */
    fun turnOn() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                store.putBoolean(KEY_DECLINED + uid, false)
                _state.value = _state.value.copy(prompt = null, busy = true, message = null)
                decideFirstTime(uid, explicit = true)
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Stops syncing on this device. The saved copy stays in the account. */
    fun turnOff() {
        val uid = userId ?: return
        setEnabled(uid, false)
        store.putBoolean(KEY_DECLINED + uid, true)
        _state.value = _state.value.copy(enabled = false, prompt = null, message = "Off. Your saved copy stays saved.")
    }

    /** Removes the saved copy from the cloud and stops syncing here. This device keeps its setup. */
    fun deleteCloudCopy() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val reply = call("DELETE", "/v1/setup")
                    if (reply.code == 200) {
                        setEnabled(uid, false)
                        store.putBoolean(KEY_DECLINED + uid, true)
                        LiveTvFiles.delete(LiveTvDirs.STATE, BASE_FILE)
                        "Deleted your saved Live TV setup from the cloud. This device keeps its own setup."
                    } else failText(reply.code)
                }.getOrElse { NETWORK_TEXT }
                _state.value = _state.value.copy(busy = false, enabled = isEnabled(uid), message = message)
            }
        }
    }

    /** Brings back the copy from before the last save (a safety net if a sync went wrong). */
    fun restorePrevious() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val reply = call("GET", "/v1/setup/previous")
                    val data = reply.json?.optJSONObject("data")
                    when {
                        reply.code == 404 -> "There is no earlier copy yet."
                        reply.code != 200 || data == null -> failText(reply.code)
                        else -> {
                            applyShared(data)
                            when (val outcome = putSetup(reply.json?.optInt("currentRevision") ?: 0, data)) {
                                is Put.Saved -> { saved(uid, data, emptySet()); "Restored the earlier copy." }
                                else -> putText(outcome)
                            }
                        }
                    }
                }.getOrElse { NETWORK_TEXT }
                _state.value = _state.value.copy(busy = false, lastSyncMs = store.getLong(KEY_LAST_SYNC), message = message)
            }
        }
    }

    // ------------------------------------------------------------------ answers to the questions

    fun answerBackUp(yes: Boolean) {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(prompt = null)
                if (!yes) {
                    store.putBoolean(KEY_DECLINED + uid, true)
                    return@withLock
                }
                _state.value = _state.value.copy(busy = true, message = null)
                decideFirstTime(uid, explicit = true)
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun answerChoose(choice: CloudChoice) {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(prompt = null)
                if (choice == CloudChoice.Later) {
                    store.putBoolean(KEY_DECLINED + uid, true)
                    return@withLock
                }
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val remote = fetch() as? Remote.Found ?: return@runCatching "Couldn't reach your saved setup. Try again."
                    val removedHere = prefs.removedSourceIds()
                    if (choice == CloudChoice.UseSaved) {
                        applyShared(remote.data)
                        saved(uid, remote.data, emptySet())
                        setEnabled(uid, true)
                        "Using your saved Live TV setup."
                    } else {
                        val local = localView()
                        val remoteView = SyncParts.strip(remote.data)
                        val merged = drive.merge(null, local, remoteView, false, removedHere)
                        if (merged.toString() != local.toString()) applyShared(merged)
                        if (merged.toString() == remoteView.toString()) {
                            saved(uid, remote.data, emptySet())
                            setEnabled(uid, true)
                            "Combined with your saved setup."
                        } else when (val result = putSetup(remote.revision, SyncParts.keep(merged, remote.data))) {
                            is Put.Saved -> {
                                saved(uid, merged, removedHere)
                                setEnabled(uid, true)
                                "Combined with your saved setup."
                            }
                            else -> putText(result)
                        }
                    }
                }.getOrElse { NETWORK_TEXT }
                _state.value = _state.value.copy(
                    busy = false,
                    enabled = isEnabled(uid),
                    lastSyncMs = store.getLong(KEY_LAST_SYNC),
                    message = message,
                )
            }
        }
    }

    // ------------------------------------------------------------------ deciding and syncing

    private fun isEnabled(uid: String) = store.getBoolean(KEY_ENABLED + uid, false)

    private fun isOn(): Boolean = userId?.let { isEnabled(it) } == true

    private fun setEnabled(uid: String, on: Boolean) {
        store.putBoolean(KEY_ENABLED + uid, on)
        if (userId == uid) _state.value = _state.value.copy(enabled = on)
    }

    /**
     * An account this device hasn't synced with yet. Looks at the saved copy and this device's setup
     * and either does the obvious thing, or sets the question to ask. With [explicit] (the person
     * said yes, or turned it on in settings) it goes ahead instead of asking again.
     * Must be called with the lock held.
     */
    private suspend fun decideFirstTime(uid: String, explicit: Boolean) {
        if (!explicit && store.getBoolean(KEY_DECLINED + uid, false)) return
        runCatching {
            val local = prefs.exportForSync()
            val hasLocal = prefs.syncHasSources(local)
            val remote = fetch()
            when {
                remote is Remote.Failed -> {
                    if (explicit) _state.value = _state.value.copy(message = failText(remote.code))
                }
                remote is Remote.Found && usable(remote.data) -> {
                    if (!hasLocal) {
                        // A device with nothing set up, signing in to an account that has a setup.
                        applyShared(remote.data)
                        saved(uid, remote.data, emptySet())
                        setEnabled(uid, true)
                        _state.value = _state.value.copy(
                            lastSyncMs = store.getLong(KEY_LAST_SYNC),
                            message = "Restored your Live TV setup.",
                        )
                    } else {
                        _state.value = _state.value.copy(prompt = CloudPrompt.Choose)
                    }
                }
                else -> {
                    // Nothing usable saved for this account (none, or one with no playlists or guides).
                    val savedRevision = (remote as? Remote.Found)?.revision ?: 0
                    when {
                        !hasLocal -> if (explicit) setEnabled(uid, true)
                        explicit -> backUpFirst(uid, savedRevision, SyncParts.strip(local))
                        else -> _state.value = _state.value.copy(prompt = CloudPrompt.BackUp)
                    }
                }
            }
            Unit
        }.onFailure {
            if (explicit) _state.value = _state.value.copy(message = NETWORK_TEXT)
        }
    }

    private suspend fun backUpFirst(uid: String, baseRevision: Int, local: JsonObject) {
        val removedHere = prefs.removedSourceIds()
        when (val result = putSetup(baseRevision, local)) {
            is Put.Saved -> {
                saved(uid, local, removedHere)
                setEnabled(uid, true)
                _state.value = _state.value.copy(
                    lastSyncMs = store.getLong(KEY_LAST_SYNC),
                    message = "Your Live TV setup is saved.",
                )
            }
            else -> _state.value = _state.value.copy(message = putText(result))
        }
    }

    private suspend fun syncIfChanged() {
        val hash = store.sha256(localView().toString())
        if (hash == store.getString(KEY_LOCAL_HASH)) return
        mutex.withLock {
            val uid = userId ?: return@withLock
            if (isEnabled(uid)) reconcileAndReport(uid)
        }
    }

    private suspend fun reconcileAndReport(uid: String, doneMessage: String? = null) {
        _state.value = _state.value.copy(busy = true)
        val result = runCatching { reconcile(uid) }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC),
            message = result.fold(onSuccess = { it ?: doneMessage }, onFailure = { NETWORK_TEXT }),
        )
    }

    /**
     * One round: take the saved copy, combine it with this device's changes, apply what's new here
     * and save what's new there. Tries again if another device saved in between. Returns a message
     * to show, or null when there is nothing to say.
     */
    private suspend fun reconcile(uid: String): String? {
        repeat(3) {
            val remote = fetch()
            val localFull = prefs.exportForSync()
            val local = SyncParts.strip(localFull)
            val removedHere = prefs.removedSourceIds()
            when {
                remote is Remote.Failed -> return failText(remote.code)
                remote is Remote.Found && usable(remote.data) -> {
                    val remoteView = SyncParts.strip(remote.data)
                    val merged = drive.merge(readBase(uid), SyncParts.freshen(local, remoteView), remoteView, false, removedHere)
                    val changedHere = merged.toString() != local.toString()
                    if (changedHere) apply(SyncParts.keep(merged, localFull))
                    val news = if (changedHere) UPDATED_TEXT else null
                    val mergedFull = SyncParts.keep(merged, remote.data)
                    if (merged.toString() == remoteView.toString() || !usable(mergedFull)) {
                        saved(uid, remote.data, emptySet())
                        return news
                    }
                    when (val result = putSetup(remote.revision, mergedFull)) {
                        is Put.Saved -> { saved(uid, merged, removedHere); return news }
                        Put.Conflict -> Unit // another device saved first: look again
                        else -> return putText(result)
                    }
                }
                else -> {
                    // Nothing usable saved: never let that empty this device; save its setup if it has one.
                    if (!prefs.syncHasSources(localFull)) return null
                    val savedRevision = (remote as? Remote.Found)?.revision ?: 0
                    when (val result = putSetup(savedRevision, local)) {
                        is Put.Saved -> { saved(uid, local, removedHere); return null }
                        Put.Conflict -> Unit
                        else -> return putText(result)
                    }
                }
            }
        }
        return "Couldn't finish syncing. Will try again."
    }

    /** This device's setup without the parts Setup Sync is switched off for. */
    private fun localView(): JsonObject = SyncParts.strip(prefs.exportForSync())

    /**
     * A saved setup counts when it has a playlist or guide. When this device leaves playlists out,
     * any saved setup counts: another device's copy may be the only one that has them.
     */
    private fun usable(data: JsonObject): Boolean =
        prefs.syncHasSources(data) || (SyncParts.isOff(SyncPart.LOGINS) && data.isNotEmpty())

    /** Writes a saved setup into this device, leaving the parts switched off as they are here. */
    private suspend fun applyShared(data: JsonObject) = apply(SyncParts.keep(SyncParts.strip(data), prefs.exportForSync()))

    /** A part of the setup was switched on or off: sync soon so the saved copy catches up. */
    fun partsChanged() {
        scope.launch { if (isOn()) syncIfChanged() }
    }

    /** Writes a saved setup into this device, without it being saved straight back. */
    private suspend fun apply(data: JsonObject) {
        applying = true
        try {
            drive.applyLocally(data)
        } finally {
            applying = false
        }
    }

    /** Remembers what both sides now agree on: the starting point for the next combine. */
    private fun saved(uid: String, data: JsonObject, removedHere: Set<String>) {
        runCatching { LiveTvFiles.writeSmall(LiveTvDirs.STATE, BASE_FILE, SyncParts.strip(data).toString()) }
        store.putString(KEY_BASE_USER, uid)
        store.putLong(KEY_LAST_SYNC, LiveTvClock.nowMs())
        store.putString(KEY_LOCAL_HASH, store.sha256(localView().toString()))
        SyncParts.clearFresh()
        if (removedHere.isNotEmpty()) prefs.forgetRemovedSources(removedHere)
    }

    /** What both sides last agreed on, but only if it was for this account. */
    private fun readBase(uid: String): JsonObject? {
        if (store.getString(KEY_BASE_USER) != uid) return null
        return LiveTvFiles.readSmall(LiveTvDirs.STATE, BASE_FILE)?.let { runCatching { parseObject(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ the service

    private sealed interface Remote {
        data object None : Remote
        data class Found(val revision: Int, val data: JsonObject) : Remote
        data class Failed(val code: Int) : Remote
    }

    private sealed interface Put {
        data class Saved(val revision: Int) : Put
        data object Conflict : Put
        data object TooBig : Put
        data object Limit : Put
        data class Failed(val code: Int) : Put
    }

    private class Reply(val code: Int, val json: JsonObject?)

    private suspend fun fetch(): Remote {
        val reply = call("GET", "/v1/setup")
        val data = reply.json?.optJSONObject("data")
        return when {
            reply.code == 200 && data != null -> Remote.Found(reply.json?.optInt("revision") ?: 0, data)
            reply.code == 404 -> Remote.None
            else -> Remote.Failed(reply.code)
        }
    }

    private suspend fun putSetup(baseRevision: Int, data: JsonObject): Put {
        val body = buildJsonObject {
            put("baseRevision", baseRevision)
            put("device", LiveTvFiles.deviceName)
            put("deviceId", drive.deviceId)
            put("data", data)
        }
        val reply = call("PUT", "/v1/setup", body)
        return when (reply.code) {
            200 -> Put.Saved(reply.json?.optInt("revision", baseRevision + 1) ?: (baseRevision + 1))
            409 -> Put.Conflict
            413 -> Put.TooBig
            429 -> Put.Limit
            else -> Put.Failed(reply.code)
        }
    }

    /** Calls the service with the Nuvio sign-in; if the sign-in has just expired, refreshes it once and retries. */
    private suspend fun call(method: String, path: String, body: JsonObject? = null): Reply {
        var reply = callOnce(method, path, body)
        if (reply.code == 401 && runCatching { SupabaseProvider.client.auth.refreshCurrentSession() }.isSuccess) {
            reply = callOnce(method, path, body)
        }
        return reply
    }

    private suspend fun callOnce(method: String, path: String, body: JsonObject?): Reply {
        val token = SupabaseProvider.client.auth.currentAccessTokenOrNull()?.takeIf { it.isNotBlank() }
            ?: return Reply(401, null)
        val headers = buildMap<String, String> {
            put("Authorization", "Bearer $token")
            put("apikey", ServerConfigurationRepository.active.value.publishableKey)
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        }
        val response = httpRequestRaw(
            method = method,
            url = CLOUD_URL + path,
            headers = headers,
            body = body?.toString().orEmpty(),
            followRedirects = false,
        )
        return Reply(response.status, runCatching { parseObject(response.body) }.getOrNull())
    }

    private fun failText(code: Int) = when (code) {
        401 -> "Sign in to Nuvio again to sync."
        else -> NETWORK_TEXT
    }

    private fun putText(result: Put) = when (result) {
        Put.Conflict -> "Your saved setup changed on another device. Try again."
        Put.TooBig -> "Your Live TV setup is too big to save to the cloud."
        Put.Limit -> "Too many saves today. Syncing continues tomorrow."
        is Put.Failed -> failText(result.code)
        is Put.Saved -> ""
    }

    /** Our own small service (the livetv-cloud Worker), the same one the TV app uses. */
    const val CLOUD_URL = "https://livetv-cloud.nuvioplusiptv.workers.dev"
    private const val UPDATED_TEXT = "Updated from your other device."
    private const val NETWORK_TEXT = "Couldn't reach sync. Will try again."
    private const val BASE_FILE = "cloud_base.json"
    private const val KEY_ENABLED = "cloud_enabled_"
    private const val KEY_DECLINED = "cloud_declined_"
    private const val KEY_BASE_USER = "cloud_base_user"
    private const val KEY_LAST_SYNC = "cloud_last_sync"
    private const val KEY_LOCAL_HASH = "cloud_local_hash"
}
