package com.nuvio.app.features.livetv.sync

import com.nuvio.app.features.livetv.data.LiveTvBackup
import com.nuvio.app.features.livetv.data.LiveTvBackupApply
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.exportForSync
import com.nuvio.app.features.livetv.data.liveTvJson
import com.nuvio.app.features.livetv.data.optJSONArray
import com.nuvio.app.features.livetv.data.optJSONObject
import com.nuvio.app.features.livetv.data.optLong
import com.nuvio.app.features.livetv.data.optString
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.data.syncHasSources
import com.nuvio.app.features.livetv.model.LiveTvClock
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What the settings screen shows. */
data class DriveSyncState(
    val available: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    val autoSync: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L,
    /** Set while signing in: show this code and address to the user. */
    val signIn: SignInPrompt? = null,
)

/** [qrUrl] is a link with the code filled in, when Google gives one. */
data class SignInPrompt(val userCode: String, val url: String, val qrUrl: String, val expiresAtMs: Long)

/**
 * Backs up and syncs the Live TV setup through the user's own Google Drive, the TV app's way:
 * the data goes in Drive's hidden app data folder, sign-in uses Google's device-code flow
 * (a code approved in a browser), changes are backed up shortly after they happen, and a newer
 * copy from another device is merged in at start-up or on Restore. Three-way merging keeps
 * what changed here since the last sync and takes everything else from the other device.
 */
internal object LiveTvDriveSync {
    private val prefs = LiveTvPreferences
    private val repository = LiveTvRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val store = LiveTvDriveHttp

    private val clientId = LiveTvDriveConfig.CLIENT_ID
    private val clientSecret = LiveTvDriveConfig.CLIENT_SECRET

    private val _state = MutableStateFlow(DriveSyncState())
    val state: StateFlow<DriveSyncState> = _state.asStateFlow()

    private var started = false
    private var signInJob: Job? = null
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private var applying = false
    private var appliedAtMs = 0L
    private var emptyRefused = false

    /** The copy in Google Drive: when it was saved, on which device, and whether that was this one. */
    data class BackupInfo(val savedAtMs: Long, val device: String, val fromThisDevice: Boolean)

    /** This device's id in backups it writes (Drive and exported files). */
    @OptIn(ExperimentalUuidApi::class)
    val deviceId: String
        get() = store.getString(KEY_DEVICE_ID) ?: Uuid.random().toString().also { store.putString(KEY_DEVICE_ID, it) }

    private fun loadState() = DriveSyncState(
        available = clientId.isNotBlank(),
        connected = store.getString(KEY_REFRESH) != null,
        email = store.getString(KEY_EMAIL),
        autoSync = store.getBoolean(KEY_AUTO, true),
        lastSyncMs = store.getLong(KEY_LAST_SYNC)
    )

    /** Starts automatic sync: check Drive for a newer setup, then back up local changes. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        _state.value = loadState()
        scope.launch {
            if (_state.value.connected && _state.value.autoSync) runCatching { pull(force = false) }
        }
        scope.launch {
            prefs.changes.drop(1).collect {
                val now = LiveTvClock.nowMs()
                if (!applying && now - appliedAtMs > 20_000) store.putLong(KEY_LOCAL_CHANGED_AT, now)
            }
        }
        scope.launch {
            prefs.changes.drop(1).debounce(8_000).collect {
                val s = _state.value
                if (s.connected && s.autoSync && !applying) runCatching { push(force = false) }
            }
        }
    }

    /** When this device's setup last changed here (0: not since this version). */
    fun localChangedAt(): Long = store.getLong(KEY_LOCAL_CHANGED_AT)

    /** About the saved copy, for the restore prompt (null: none, or Drive couldn't be reached). */
    suspend fun backupInfo(): BackupInfo? = runCatching {
        val token = token() ?: return@runCatching null
        val json = remoteCopy(token) ?: return@runCatching null
        BackupInfo(json.optLong("savedAt"), json.optString("device").ifBlank { "another device" }, json.optString("deviceId") == deviceId)
    }.getOrNull()

    // ------------------------------------------------------------------ sign-in

    fun beginSignIn() {
        if (!_state.value.available) return
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = _state.value.copy(busy = true, message = null, signIn = null)
            var codes = JsonObject(emptyMap())
            var mode = MODE_APPDATA
            for ((scopes, m) in SCOPE_ATTEMPTS) {
                codes = runCatching {
                    postJson("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to scopes))
                }.getOrElse {
                    _state.value = _state.value.copy(busy = false, message = "Couldn't reach Google. Try again.")
                    return@launch
                }
                mode = m
                if (codes.optString("error") != "invalid_scope") break
            }
            val deviceCode = codes.optString("device_code")
            val userCode = codes.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) {
                _state.value = _state.value.copy(busy = false, message = googleError(codes))
                return@launch
            }
            val url = codes.optString("verification_url").ifBlank { "https://www.google.com/device" }
            val qrUrl = codes.optString("verification_uri_complete").ifBlank { url }
            val expiresAt = LiveTvClock.nowMs() + codes.optLong("expires_in", 1800) * 1000
            var interval = codes.optLong("interval", 5).coerceAtLeast(5)
            _state.value = _state.value.copy(busy = false, signIn = SignInPrompt(userCode, url, qrUrl, expiresAt))

            while (LiveTvClock.nowMs() < expiresAt) {
                delay(interval * 1000)
                val token = runCatching {
                    postJson(
                        "https://oauth2.googleapis.com/token",
                        mapOf(
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                            "device_code" to deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                        )
                    )
                }.getOrNull() ?: continue
                when (token.optString("error")) {
                    "" -> {
                        store.putString(KEY_MODE, mode)
                        onSignedIn(token)
                        return@launch
                    }
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "access_denied" -> {
                        _state.value = _state.value.copy(signIn = null, message = "Sign-in was declined.")
                        return@launch
                    }
                    else -> {
                        _state.value = _state.value.copy(signIn = null, message = googleError(token))
                        return@launch
                    }
                }
            }
            _state.value = _state.value.copy(signIn = null, message = "The code expired. Try again.")
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.value = _state.value.copy(signIn = null, busy = false)
    }

    private suspend fun onSignedIn(token: JsonObject) {
        val refresh = token.optString("refresh_token")
        if (refresh.isBlank()) {
            _state.value = _state.value.copy(signIn = null, message = "Google didn't return a sign-in. Try again.")
            return
        }
        accessToken = token.optString("access_token")
        accessTokenExpiresAt = LiveTvClock.nowMs() + token.optLong("expires_in", 3600) * 1000
        val email = emailFromIdToken(token.optString("id_token"))
        store.putString(KEY_REFRESH, refresh)
        store.putString(KEY_EMAIL, email)
        _state.value = _state.value.copy(connected = true, email = email, signIn = null, message = null)
        // First connection: use the Drive copy if there is one, otherwise back this device up.
        if (!pull(force = true, onlyIfExists = true)) push(force = true)
    }

    fun disconnect() {
        val refresh = store.getString(KEY_REFRESH)
        listOf(KEY_REFRESH, KEY_EMAIL, KEY_FILE_ID, KEY_LAST_HASH, KEY_MODE).forEach { store.putString(it, null) }
        LiveTvFiles.delete(LiveTvDirs.STATE, BASE_FILE)
        accessToken = null
        _state.value = _state.value.copy(connected = false, email = null, message = "Signed out. Your backup stays in your Google Drive.")
        if (refresh != null) scope.launch {
            runCatching { store.postForm("https://oauth2.googleapis.com/revoke", mapOf("token" to refresh)) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        store.putBoolean(KEY_AUTO, enabled)
        _state.value = _state.value.copy(autoSync = enabled)
    }

    fun backUpNow() { scope.launch { runCatching { push(force = true) } } }
    fun restoreNow() { scope.launch { runCatching { pull(force = true) } } }

    /**
     * After a backup file was imported: when this device syncs automatically, send the new setup
     * to Drive right away so the saved copy matches it. Does nothing when signed out or sync is off.
     */
    fun backUpAfterImport() {
        val s = _state.value
        if (s.connected && s.autoSync) scope.launch { runCatching { push(force = true, replaceRemote = true) } }
    }

    // ------------------------------------------------------------------ backup / restore

    /** [replaceRemote]: the Drive copy is overwritten as is, without merging in what it has. */
    private suspend fun push(force: Boolean, replaceRemote: Boolean = false) = mutex.withLock {
        val token = token() ?: return@withLock
        emptyRefused = false
        _state.value = _state.value.copy(busy = true, message = if (force) "Backing up…" else null)
        val result = runCatching {
            var data = prefs.exportForSync()
            val removedHere = prefs.removedSourceIds()
            val hash0 = store.sha256(data.toString())
            if (!force && hash0 == store.getString(KEY_LAST_HASH)) return@runCatching false
            // Another device saved since we last synced: combine its changes with ours first.
            if (!replaceRemote) remoteCopy(token)?.let { remote ->
                val remoteData = remote.optJSONObject("data")
                val base = readBase()
                if (remoteData != null && remoteData.toString() != base?.toString()) {
                    val merged = merge(base, local = data, remote = remoteData, localWinsWithoutBase = true, removedHere = removedHere)
                    if (merged.toString() != data.toString()) applyLocally(merged)
                    data = merged
                }
            }
            // No playlists and no guides here: never write that over the backup.
            if (!prefs.syncHasSources(data)) {
                if (force) emptyRefused = true
                return@runCatching false
            }
            val hash = store.sha256(data.toString())
            val now = LiveTvClock.nowMs()
            val body = LiveTvBackup.envelope(data, now, deviceId, LiveTvFiles.deviceName).toString()
            val fileId = fileId(token)
            if (fileId == null) {
                val meta = buildJsonObject {
                    put("name", FILE_NAME)
                    if (isAppDataMode) put("parents", buildJsonArray { add(JsonPrimitive("appDataFolder")) })
                }
                val boundary = "nuvio_livetv_boundary"
                val multipart = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    append(meta.toString()).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    append(body).append("\r\n")
                    append("--").append(boundary).append("--")
                }
                val created = execute(
                    "POST",
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                    token,
                    contentType = "multipart/related; boundary=$boundary",
                    body = multipart
                )
                store.putString(KEY_FILE_ID, parseObject(created).optString("id"))
            } else {
                execute("PATCH", "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media", token, contentType = JSON, body = body)
            }
            store.putString(KEY_LAST_HASH, hash)
            store.putLong(KEY_LAST_SYNC, now)
            store.putLong(KEY_APPLIED_AT, now)
            writeBase(data)
            prefs.forgetRemovedSources(removedHere)
            true
        }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC),
            message = when {
                result.isFailure -> "Backup failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                emptyRefused -> "Nothing backed up: add a playlist or guide first. Your saved copy in Google Drive was left as it was."
                force -> "Backed up to Google Drive."
                else -> _state.value.message
            }
        )
    }

    /**
     * Applies the Drive copy if it's newer than what this device has (or always, when [force]).
     * Returns true if a copy was found and applied.
     */
    private suspend fun pull(force: Boolean, onlyIfExists: Boolean = false): Boolean = mutex.withLock {
        val token = token() ?: return@withLock false
        _state.value = _state.value.copy(busy = true, message = if (force && !onlyIfExists) "Restoring…" else null)
        val result = runCatching {
            val json = remoteCopy(token) ?: return@runCatching null
            val savedAt = json.optLong("savedAt")
            val fromThisDevice = json.optString("deviceId") == deviceId
            val remoteData = json.optJSONObject("data") ?: return@runCatching false
            val base = readBase()
            if (!force && (fromThisDevice || remoteData.toString() == base?.toString())) return@runCatching false
            // A saved copy with no playlists or guides: never let it empty this device.
            if (!prefs.syncHasSources(remoteData)) return@runCatching null
            val data = if (force) remoteData
            else merge(base, local = prefs.exportForSync(), remote = remoteData, localWinsWithoutBase = false, removedHere = prefs.removedSourceIds())
            applyLocally(data)
            store.putLong(KEY_APPLIED_AT, savedAt)
            store.putLong(KEY_LAST_SYNC, LiveTvClock.nowMs())
            store.putString(KEY_LAST_HASH, store.sha256(remoteData.toString()))
            writeBase(remoteData)
            // Our own changes were kept on top: send the combined setup back up.
            if (data.toString() != remoteData.toString()) scope.launch { delay(2_000); runCatching { push(force = false) } }
            true
        }
        val applied = result.getOrNull() == true
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC),
            message = when {
                result.isFailure -> "Restore failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                result.getOrNull() == null && force && !onlyIfExists -> "No backup found in Google Drive yet."
                applied && force -> "Restored from Google Drive."
                applied -> "Updated from your other device."
                else -> _state.value.message
            }
        )
        applied
    }

    // ------------------------------------------------------------------ merging

    internal suspend fun applyLocally(data: JsonObject) {
        applying = true
        appliedAtMs = LiveTvClock.nowMs()
        try {
            // The same import and reload as Import backup file (see LiveTvBackupApply).
            val applied = LiveTvBackupApply.apply(data, prefs, repository::reloadAfterSync)
            if (!applied.changedAnything) return
            delay(500)
        } finally {
            applying = false
            appliedAtMs = LiveTvClock.nowMs()
        }
    }

    private suspend fun remoteCopy(token: String): JsonObject? = runCatching {
        val fileId = fileId(token) ?: return null
        parseObject(execute("GET", "https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token))
    }.getOrNull()

    /**
     * Combines two setups setting by setting: whatever this device changed since the last sync
     * ([base]) is kept, everything else comes from the other device. Without a base,
     * [localWinsWithoutBase] decides, but playlists and guides from both are kept.
     */
    internal fun merge(
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        localWinsWithoutBase: Boolean,
        removedHere: Set<String>,
    ): JsonObject {
        if (base == null) {
            val (first, second) = if (localWinsWithoutBase) local to remote else remote to local
            val out = first.toMutableMap()
            for (k in SOURCE_LISTS) {
                val a = first.optJSONObject(k)
                val b = second.optJSONObject(k)
                if (b == null) continue
                if (a == null) { out[k] = b; continue }
                runCatching { unionSources(a.optString("v"), b.optString("v"), removedHere) }.getOrNull()
                    ?.let { out[k] = withValue(a, it) }
            }
            return JsonObject(out)
        }
        val out = remote.toMutableMap()
        val keys = (local.keys + base.keys).toSet()
        keys.forEach { k ->
            val l = local[k]?.toString()
            val b = base[k]?.toString()
            if (l != b) {
                val listMerged = if (k in SOURCE_LISTS && (local.containsKey(k) || remote.containsKey(k))) {
                    runCatching {
                        mergeSources(
                            base.optJSONObject(k)?.optString("v"),
                            local.optJSONObject(k)?.optString("v") ?: "[]",
                            remote.optJSONObject(k)?.optString("v") ?: "[]",
                            removedHere
                        )
                    }.getOrNull()
                } else null
                val shell = local.optJSONObject(k) ?: remote.optJSONObject(k)
                when {
                    listMerged != null && shell != null -> out[k] = withValue(shell, listMerged)
                    local.containsKey(k) -> out[k] = local.getValue(k)
                    else -> out.remove(k)
                }
            }
        }
        return JsonObject(out)
    }

    private fun withValue(shell: JsonObject, v: String): JsonObject = JsonObject(shell.toMutableMap().apply { this["v"] = JsonPrimitive(v) })

    /**
     * Three-way merge of two source lists (JSON arrays of objects with an "id"): sources added
     * or removed on either device are added or removed, and within a source each setting changed
     * here since the last sync is kept, everything else comes from the other device.
     */
    internal fun mergeSources(baseRaw: String?, localRaw: String, remoteRaw: String, removedHere: Set<String>): String {
        val base = readSources(baseRaw)
        val local = readSources(localRaw)
        val remote = readSources(remoteRaw)
        val result = ArrayList<JsonElement>()
        for ((id, r) in remote) {
            val l = local[id]
            val b = base[id]
            when {
                l == null && id in removedHere -> continue
                l == null -> result += r
                b == null -> result += r
                else -> {
                    val merged = r.toMutableMap()
                    l.forEach { (f, v) -> if (v.toString() != b[f]?.toString()) merged[f] = v }
                    result += JsonObject(merged)
                }
            }
        }
        for ((id, l) in local) {
            if (id !in remote && id !in base) result += l
        }
        return JsonArray(result).toString()
    }

    private fun readSources(raw: String?): LinkedHashMap<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        if (raw.isNullOrBlank()) return out
        val arr = liveTvJson.parseToJsonElement(raw) as? JsonArray ?: return out
        arr.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val id = o.optString("id")
            if (id.isNotBlank()) out[id] = o
        }
        return out
    }

    private fun unionSources(firstRaw: String, secondRaw: String, removedHere: Set<String>): String {
        val first = readSources(firstRaw)
        val result = ArrayList<JsonElement>(first.values)
        for ((id, o) in readSources(secondRaw)) {
            if (id !in first && id !in removedHere) result += o
        }
        return JsonArray(result).toString()
    }

    private fun readBase(): JsonObject? = LiveTvFiles.readSmall(LiveTvDirs.STATE, BASE_FILE)?.let { runCatching { parseObject(it) }.getOrNull() }

    private fun writeBase(data: JsonObject) {
        LiveTvFiles.writeSmall(LiveTvDirs.STATE, BASE_FILE, data.toString())
    }

    // ------------------------------------------------------------------ Google plumbing

    private suspend fun token(): String? {
        val refresh = store.getString(KEY_REFRESH) ?: return null
        accessToken?.let { if (LiveTvClock.nowMs() < accessTokenExpiresAt - 60_000) return it }
        val res = runCatching {
            postJson(
                "https://oauth2.googleapis.com/token",
                mapOf("client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refresh, "grant_type" to "refresh_token")
            )
        }.getOrElse { e ->
            _state.value = _state.value.copy(message = "Couldn't reach Google Drive (${e.message ?: "network error"}).")
            return null
        }
        if (res.optString("error") == "invalid_grant") {
            store.putString(KEY_REFRESH, null)
            _state.value = _state.value.copy(connected = false, message = "Google Drive access was removed. Sign in again to keep syncing.")
            return null
        }
        val access = res.optString("access_token").ifBlank { return null }
        accessToken = access
        accessTokenExpiresAt = LiveTvClock.nowMs() + res.optLong("expires_in", 3600) * 1000
        return access
    }

    private suspend fun fileId(token: String): String? {
        store.getString(KEY_FILE_ID)?.let { return it }
        val q = "name='$FILE_NAME' and trashed=false".encodeURLParameter()
        val text = execute(
            "GET",
            "https://www.googleapis.com/drive/v3/files?spaces=${if (isAppDataMode) "appDataFolder" else "drive"}&q=$q&fields=files(id)",
            token
        )
        val files = parseObject(text).optJSONArray("files")
        val id = (files?.firstOrNull() as? JsonObject)?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        store.putString(KEY_FILE_ID, id)
        return id
    }

    private suspend fun postJson(url: String, fields: Map<String, String>): JsonObject {
        val r = store.postForm(url, fields)
        return runCatching { parseObject(r.body) }.getOrElse { buildJsonObject { put("error", "http_${r.code}") } }
    }

    private suspend fun execute(method: String, url: String, token: String, contentType: String? = null, body: String? = null): String {
        val r = store.request(method, url, token, contentType, body)
        if (r.code == 404) store.putString(KEY_FILE_ID, null)
        if (!r.isSuccessful) error("HTTP ${r.code}")
        return r.body
    }

    private val isAppDataMode: Boolean get() = (store.getString(KEY_MODE) ?: MODE_APPDATA) == MODE_APPDATA

    private fun googleError(json: JsonObject): String {
        val e = json.optString("error").ifBlank { json.optString("error_code") }
        val detail = json.optString("error_description").takeIf { it.isNotBlank() }?.let { " Google says: $it" }.orEmpty()
        return when (e) {
            "invalid_client" -> "Google doesn't recognize this build's sign-in client. Check the client ID and secret, and that its type is \"TVs and Limited Input devices\".$detail"
            "unauthorized_client" -> "This sign-in client isn't allowed to use device sign-in. Create one of type \"TVs and Limited Input devices\".$detail"
            "invalid_scope" -> "Google refused the Drive permission for this app.$detail"
            "org_internal" -> "This app is limited to one organization. Set the audience to External in Google Cloud.$detail"
            "rate_limit_exceeded" -> "Too many attempts. Wait a minute and try again."
            else -> "Google sign-in failed ($e).$detail"
        }
    }

    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val json = store.decodeBase64Url(payload) ?: return null
        parseObject(json).optString("email").ifBlank { null }
    }.getOrNull()

    private const val MODE_APPDATA = "appdata"
    private const val MODE_FILE = "file"
    private val SOURCE_LISTS = setOf("playlists", "epg_sources")
    private val SCOPE_ATTEMPTS = listOf(
        "openid email https://www.googleapis.com/auth/drive.appdata" to MODE_APPDATA,
        "https://www.googleapis.com/auth/drive.appdata" to MODE_APPDATA,
        "https://www.googleapis.com/auth/drive.file" to MODE_FILE
    )
    private const val FILE_NAME = "Nuvio Live TV backup.json"
    private const val BASE_FILE = "drive_base.json"
    private const val JSON = "application/json; charset=UTF-8"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "email"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_FILE_ID = "file_id"
    private const val KEY_LAST_HASH = "last_hash"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_APPLIED_AT = "applied_at"
    private const val KEY_LOCAL_CHANGED_AT = "local_changed_at"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MODE = "mode"
}
