package com.nuvio.app.features.usenet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where [NewznabIndexerStates] persists; the app uses private SharedPreferences. */
internal interface NewznabStateStorage {
    fun load(): String?
    fun save(value: String)
    /** Changes when the stored state is cleared from outside, so a loaded copy is dropped. */
    val generation: Int get() = 0
}

/** Holds no credentials and is shared by profiles; only an account reset clears it. */
internal class SharedPreferencesNewznabStateStorage(context: Context) : NewznabStateStorage {
    private val preferences = context.getSharedPreferences("usenet_indexer_state", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    @Volatile override var generation = 0
        private set

    fun clear() {
        preferences.edit().clear().apply()
        generation += 1
    }

    private companion object { const val KEY = "indexers" }
}

@Serializable
internal data class NewznabIndexerState(
    val caps: NewznabCapabilities? = null,
    val capsFetchedAt: Long = 0,
    val blockedUntil: Long = 0,
    val touchedAt: Long = 0,
    val problem: UsenetIndexerProblem? = null,
    val apiHitsLeft: Int? = null,
    val grabsLeft: Int? = null,
    /** When the indexer last reported what is left. */
    val leftAt: Long = 0
)

/**
 * Capabilities and limit cooldowns per indexer, kept across restarts so neither
 * costs API hits again. Keyed by a hash of the endpoint and key: no secret is
 * stored, and an edited indexer starts fresh.
 */
internal class NewznabIndexerStates(private val storage: NewznabStateStorage, private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded: MutableMap<String, NewznabIndexerState>? = null
    private var loadedGeneration = 0

    /** Loaded on first use, and again after an account reset cleared the storage. */
    private val states: MutableMap<String, NewznabIndexerState>
        get() {
            loaded?.takeIf { loadedGeneration == storage.generation }?.let { return it }
            loadedGeneration = storage.generation
            return runCatching { json.decodeFromString<Map<String, NewznabIndexerState>>(storage.load() ?: "{}") }
                .getOrDefault(emptyMap()).toMutableMap().also { loaded = it }
        }
    private val changes = MutableStateFlow(0)
    /** Increments on every change, so settings can show statuses that searches update. */
    val revision: StateFlow<Int> = changes.asStateFlow()

    @Synchronized
    fun get(indexer: UsenetIndexer): NewznabIndexerState? = states[key(indexer)]

    @Synchronized
    fun update(indexer: UsenetIndexer, change: (NewznabIndexerState) -> NewznabIndexerState) {
        val key = key(indexer)
        states[key] = change(states[key] ?: NewznabIndexerState()).copy(touchedAt = now())
        // Deleted or edited indexers stop being touched; forget them and bound the size.
        states.entries.removeAll { now() - it.value.touchedAt > RETENTION_MS }
        while (states.size > MAX_ENTRIES) states.remove(states.minBy { it.value.touchedAt }.key)
        storage.save(json.encodeToString(states.toMap()))
        changes.value += 1
    }

    private fun key(indexer: UsenetIndexer) = indexer.stateKey()

    private companion object {
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
        const val MAX_ENTRIES = 100
    }
}
