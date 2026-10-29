package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibraryDeltaEvent
import com.nuvio.app.features.library.sync.LibrarySyncKey
import com.nuvio.app.features.library.sync.toLibrarySyncKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job

internal data class LibraryProfileToken(
    val profileId: Int,
    val generation: Long,
)

internal data class LibraryLocalSnapshot(
    val token: LibraryProfileToken,
    val revision: Long,
    val contentRevision: Long,
    val hasLoaded: Boolean,
    val isLoading: Boolean,
    val items: List<LibraryItem>,
    val deltaCursorEventId: Long,
    val deltaInitialized: Boolean,
    val pendingUpsertKeys: List<LibrarySyncKey>,
    val pendingDeleteKeys: List<LibrarySyncKey>,
) {
    val hasPendingPush: Boolean
        get() = pendingUpsertKeys.isNotEmpty() || pendingDeleteKeys.isNotEmpty()
}

internal data class LibraryStateTransition(
    val snapshot: LibraryLocalSnapshot,
    val detachedPushJob: Job?,
)

internal data class LibraryLocalMutation(
    val snapshot: LibraryLocalSnapshot,
    val affectedCount: Int,
)

internal data class LibraryLocalToggleResult(
    val snapshot: LibraryLocalSnapshot,
    val isSaved: Boolean,
)

internal data class LibraryServerItemsApplyResult(
    val snapshot: LibraryLocalSnapshot,
    val preservedLocalItems: Boolean,
)

internal data class LibraryPushJobInstallResult(
    val installed: Boolean,
    val detachedPushJob: Job?,
)

/**
 * Owns the profile-scoped local-library state behind one lock.
 *
 * Callers only receive copied item lists, so sorting or serializing a snapshot never traverses
 * the live mutable map while another thread replaces or edits it.
 */
internal class LibraryLocalState {
    private val lock = SynchronizedObject()

    private var hasLoaded = false
    private var currentProfileId = 1
    private var profileGeneration = 0L
    private var revision = 0L
    private var contentRevision = 0L
    private var isLoading = false
    private var itemsById: MutableMap<String, LibraryItem> = mutableMapOf()
    private var deltaCursorEventId = 0L
    private var deltaInitialized = false
    private var pendingUpsertKeysByKey: MutableMap<String, LibrarySyncKey> = mutableMapOf()
    private var pendingDeleteKeysByKey: MutableMap<String, LibrarySyncKey> = mutableMapOf()
    private var pushJob: Job? = null

    fun snapshot(): LibraryLocalSnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun currentTokenIfLoaded(profileId: Int): LibraryProfileToken? = synchronized(lock) {
        if (!hasLoaded || currentProfileId != profileId) {
            null
        } else {
            tokenLocked()
        }
    }

    fun isCurrent(token: LibraryProfileToken): Boolean = synchronized(lock) {
        isCurrentLocked(token)
    }

    fun isCurrent(snapshot: LibraryLocalSnapshot): Boolean = synchronized(lock) {
        isCurrentLocked(snapshot)
    }

    fun isContentCurrent(snapshot: LibraryLocalSnapshot): Boolean = synchronized(lock) {
        isContentCurrentLocked(snapshot)
    }

    fun runIfCurrent(snapshot: LibraryLocalSnapshot, block: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrentLocked(snapshot)) {
            false
        } else {
            block()
            true
        }
    }

    fun runIfContentCurrent(snapshot: LibraryLocalSnapshot, block: () -> Unit): Boolean = synchronized(lock) {
        if (!isContentCurrentLocked(snapshot)) {
            false
        } else {
            block()
            true
        }
    }

    fun runIfTokenCurrent(token: LibraryProfileToken, block: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrentLocked(token)) {
            false
        } else {
            block()
            true
        }
    }

    fun beginProfileLoad(profileId: Int): LibraryStateTransition = synchronized(lock) {
        val detachedPushJob = pushJob
        pushJob = null
        currentProfileId = profileId
        profileGeneration += 1L
        revision += 1L
        contentRevision += 1L
        hasLoaded = false
        isLoading = true
        itemsById = mutableMapOf()
        deltaCursorEventId = 0L
        deltaInitialized = false
        pendingUpsertKeysByKey = mutableMapOf()
        pendingDeleteKeysByKey = mutableMapOf()
        LibraryStateTransition(
            snapshot = snapshotLocked(),
            detachedPushJob = detachedPushJob,
        )
    }

    fun completeProfileLoad(
        token: LibraryProfileToken,
        activeProfileId: Int,
        items: Collection<LibraryItem>,
        deltaCursorEventId: Long = 0L,
        deltaInitialized: Boolean = false,
        pendingUpsertKeys: Collection<LibrarySyncKey> = emptyList(),
        pendingDeleteKeys: Collection<LibrarySyncKey> = emptyList(),
    ): LibraryLocalSnapshot? = synchronized(lock) {
        if (activeProfileId != token.profileId || !isCurrentLocked(token)) {
            return@synchronized null
        }
        itemsById = items.associateByTo(mutableMapOf()) { libraryItemKey(it.id, it.type) }
        this.deltaCursorEventId = deltaCursorEventId.coerceAtLeast(0L)
        this.deltaInitialized = deltaInitialized
        pendingUpsertKeysByKey = pendingUpsertKeys
            .associateByTo(mutableMapOf()) { libraryItemKey(it.contentId, it.contentType) }
            .filterToExistingItems(itemsById)
        pendingDeleteKeysByKey = pendingDeleteKeys
            .associateByTo(mutableMapOf()) { libraryItemKey(it.contentId, it.contentType) }
            .apply { pendingUpsertKeysByKey.keys.forEach(::remove) }
        pendingDeleteKeysByKey.keys.forEach(itemsById::remove)
        hasLoaded = true
        isLoading = false
        revision += 1L
        contentRevision += 1L
        snapshotLocked()
    }

    fun reset(): LibraryStateTransition = synchronized(lock) {
        val detachedPushJob = pushJob
        pushJob = null
        currentProfileId = 1
        profileGeneration += 1L
        revision += 1L
        contentRevision += 1L
        hasLoaded = false
        isLoading = false
        itemsById = mutableMapOf()
        deltaCursorEventId = 0L
        deltaInitialized = false
        pendingUpsertKeysByKey = mutableMapOf()
        pendingDeleteKeysByKey = mutableMapOf()
        LibraryStateTransition(
            snapshot = snapshotLocked(),
            detachedPushJob = detachedPushJob,
        )
    }

    fun markPullStarted(token: LibraryProfileToken): LibraryLocalSnapshot? = synchronized(lock) {
        if (!isCurrentLocked(token)) return@synchronized null
        snapshotLocked()
    }

    fun applyServerItems(
        pullSnapshot: LibraryLocalSnapshot,
        serverItems: Collection<LibraryItem>,
        cursorEventId: Long = 0L,
    ): LibraryServerItemsApplyResult? = synchronized(lock) {
        if (!isCurrentLocked(pullSnapshot.token)) return@synchronized null

        val reconciliation = reconcileLibrarySnapshot(
            serverItems = serverItems,
            localItemsByKey = itemsById,
            pendingUpsertKeysByKey = pendingUpsertKeysByKey,
            pendingDeleteKeysByKey = pendingDeleteKeysByKey,
        )
        if (itemsById != reconciliation.itemsByKey) {
            contentRevision += 1L
        }
        itemsById = reconciliation.itemsByKey
        pendingUpsertKeysByKey = reconciliation.pendingUpsertKeysByKey
        pendingDeleteKeysByKey = reconciliation.pendingDeleteKeysByKey
        deltaCursorEventId = cursorEventId.coerceAtLeast(0L)
        deltaInitialized = true
        hasLoaded = true
        isLoading = false
        revision += 1L
        LibraryServerItemsApplyResult(
            snapshot = snapshotLocked(),
            preservedLocalItems = reconciliation.preservedLocalItems,
        )
    }

    fun applyDeltaEvents(
        token: LibraryProfileToken,
        events: Collection<LibraryDeltaEvent>,
    ): LibraryLocalSnapshot? = synchronized(lock) {
        if (!isCurrentLocked(token)) return@synchronized null

        val reconciliation = reconcileLibraryDelta(
            events = events,
            currentItemsByKey = itemsById,
            pendingUpsertKeysByKey = pendingUpsertKeysByKey,
            pendingDeleteKeysByKey = pendingDeleteKeysByKey,
            currentCursorEventId = deltaCursorEventId,
        )
        if (reconciliation.changed) {
            itemsById = reconciliation.itemsByKey
            contentRevision += 1L
        }
        deltaCursorEventId = reconciliation.cursorEventId
        deltaInitialized = true
        revision += 1L
        snapshotLocked()
    }

    fun upsert(item: LibraryItem): LibraryLocalSnapshot = synchronized(lock) {
        val key = libraryItemKey(item.id, item.type)
        itemsById[key] = item
        pendingUpsertKeysByKey[key] = item.toLibrarySyncKey()
        pendingDeleteKeysByKey.remove(key)
        revision += 1L
        contentRevision += 1L
        snapshotLocked()
    }

    fun toggle(item: LibraryItem): LibraryLocalToggleResult = synchronized(lock) {
        val key = libraryItemKey(item.id, item.type)
        val removedItem = itemsById.remove(key)
        val isSaved = if (removedItem != null) {
            pendingUpsertKeysByKey.remove(key)
            pendingDeleteKeysByKey[key] = removedItem.toLibrarySyncKey()
            false
        } else {
            itemsById[key] = item
            pendingUpsertKeysByKey[key] = item.toLibrarySyncKey()
            pendingDeleteKeysByKey.remove(key)
            true
        }
        revision += 1L
        contentRevision += 1L
        LibraryLocalToggleResult(
            snapshot = snapshotLocked(),
            isSaved = isSaved,
        )
    }

    fun removeById(id: String): LibraryLocalMutation = synchronized(lock) {
        val removedEntries = itemsById
            .filterValues { item -> item.id == id }
        removedEntries.forEach { (key, item) ->
            itemsById.remove(key)
            pendingUpsertKeysByKey.remove(key)
            pendingDeleteKeysByKey[key] = item.toLibrarySyncKey()
        }
        val affectedCount = removedEntries.size
        if (affectedCount > 0) {
            revision += 1L
            contentRevision += 1L
        }
        LibraryLocalMutation(
            snapshot = snapshotLocked(),
            affectedCount = affectedCount,
        )
    }

    fun remove(id: String, type: String): LibraryLocalMutation = synchronized(lock) {
        val key = libraryItemKey(id, type)
        val removedItem = itemsById.remove(key)
        val affectedCount = if (removedItem != null) 1 else 0
        if (removedItem != null) {
            pendingUpsertKeysByKey.remove(key)
            pendingDeleteKeysByKey[key] = removedItem.toLibrarySyncKey()
            revision += 1L
            contentRevision += 1L
        }
        LibraryLocalMutation(
            snapshot = snapshotLocked(),
            affectedCount = affectedCount,
        )
    }

    fun contains(id: String, type: String): Boolean = synchronized(lock) {
        itemsById.containsKey(libraryItemKey(id, type))
    }

    fun containsId(id: String): Boolean = synchronized(lock) {
        itemsById.values.any { it.id == id }
    }

    fun findById(id: String): LibraryItem? = synchronized(lock) {
        itemsById.values.firstOrNull { it.id == id }
    }

    fun installPushJob(
        snapshot: LibraryLocalSnapshot,
        job: Job,
    ): LibraryPushJobInstallResult = synchronized(lock) {
        if (!isCurrentLocked(snapshot)) {
            LibraryPushJobInstallResult(installed = false, detachedPushJob = null)
        } else {
            val detachedPushJob = pushJob
            pushJob = job
            LibraryPushJobInstallResult(installed = true, detachedPushJob = detachedPushJob)
        }
    }

    fun clearPushJob(job: Job) {
        synchronized(lock) {
            if (pushJob === job) pushJob = null
        }
    }

    fun markPushCompleted(snapshot: LibraryLocalSnapshot): LibraryLocalSnapshot? = synchronized(lock) {
        if (!isCurrentLocked(snapshot)) {
            null
        } else {
            pendingUpsertKeysByKey.clear()
            pendingDeleteKeysByKey.clear()
            revision += 1L
            snapshotLocked()
        }
    }

    private fun tokenLocked(): LibraryProfileToken =
        LibraryProfileToken(
            profileId = currentProfileId,
            generation = profileGeneration,
        )

    private fun snapshotLocked(): LibraryLocalSnapshot =
        LibraryLocalSnapshot(
            token = tokenLocked(),
            revision = revision,
            contentRevision = contentRevision,
            hasLoaded = hasLoaded,
            isLoading = isLoading,
            items = itemsById.values.toList(),
            deltaCursorEventId = deltaCursorEventId,
            deltaInitialized = deltaInitialized,
            pendingUpsertKeys = pendingUpsertKeysByKey.values.toList(),
            pendingDeleteKeys = pendingDeleteKeysByKey.values.toList(),
        )

    private fun isCurrentLocked(token: LibraryProfileToken): Boolean =
        currentProfileId == token.profileId && profileGeneration == token.generation

    private fun isCurrentLocked(snapshot: LibraryLocalSnapshot): Boolean =
        isCurrentLocked(snapshot.token) && revision == snapshot.revision

    private fun isContentCurrentLocked(snapshot: LibraryLocalSnapshot): Boolean =
        isCurrentLocked(snapshot.token) && contentRevision == snapshot.contentRevision
}

private fun MutableMap<String, LibrarySyncKey>.filterToExistingItems(
    itemsById: Map<String, LibraryItem>,
): MutableMap<String, LibrarySyncKey> =
    apply {
        keys.retainAll(itemsById.keys)
    }

internal fun libraryItemKey(id: String, type: String): String =
    "${type.trim().lowercase()}:${id.trim()}"
