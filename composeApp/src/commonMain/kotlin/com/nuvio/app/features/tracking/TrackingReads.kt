package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.watching.sync.WatchedSyncAdapter
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import kotlinx.coroutines.flow.Flow

enum class TrackingLibraryTabKind {
    WATCHLIST,
    PERSONAL,
    STATUS,
}

data class TrackingLibraryTab(
    val key: String,
    val title: String,
    val providerId: TrackingProviderId?,
    val kind: TrackingLibraryTabKind,
    val selectionGroup: String? = null,
    val supportedContentTypes: Set<String>? = null,
    val isMembershipDestination: Boolean = true,
    val description: String? = null,
    val privacy: LibraryListPrivacy? = null,
)

fun TrackingLibraryTab.supportsContentType(contentType: String): Boolean =
    supportedContentTypes == null || supportedContentTypes.any { supported ->
        supported.equals(contentType, ignoreCase = true)
    }

fun TrackingLibraryTab.membershipTitle(): String =
    providerId?.let { "${it.displayName} · $title" } ?: title

internal fun trackingMembershipDestinations(
    tabs: List<TrackingLibraryTab>,
): List<TrackingLibraryTab> = tabs.filter(TrackingLibraryTab::isMembershipDestination)

fun toggleTrackingLibraryMembership(
    tabs: List<TrackingLibraryTab>,
    membership: Map<String, Boolean>,
    key: String,
): Map<String, Boolean> {
    val target = tabs.firstOrNull { tab -> tab.key == key } ?: return membership
    val selecting = membership[key] != true
    return membership.toMutableMap().apply {
        if (selecting && target.selectionGroup != null) {
            tabs.filter { tab ->
                tab.providerId == target.providerId && tab.selectionGroup == target.selectionGroup
            }.forEach { tab -> this[tab.key] = false }
        }
        this[key] = selecting
    }
}

data class TrackingLibrarySnapshot(
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val tabs: List<TrackingLibraryTab> = emptyList(),
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/** Why a provider refresh was requested. Providers own the matching cache policy. */
enum class TrackingRefreshIntent {
    /** Lifecycle, startup, reconnect, or other application-driven refresh. */
    AUTOMATIC,

    /** An explicit user action, such as Sync now or pull-to-refresh. */
    USER_INITIATED,

    /** A successful write or authentication change made the local snapshot stale. */
    INVALIDATED,
}

/**
 * Provider-owned projection of remote library state into application models.
 *
 * Application repositories consume this port through [TrackingProviderRegistry]; provider DTOs,
 * list semantics, cache policy, and mutation details stay inside the provider package.
 */
interface TrackingLibraryProvider {
    val providerId: TrackingProviderId
    val listManager: TrackingListManager?
        get() = null
    val changes: Flow<Unit>
    val connectionRefreshIntent: TrackingRefreshIntent

    fun ensureLoaded()
    fun prepare() = Unit
    fun onProfileChanged() = Unit
    fun clearLocalState() = Unit
    suspend fun refresh(intent: TrackingRefreshIntent)
    fun snapshot(): TrackingLibrarySnapshot
    fun contains(contentId: String, contentType: String? = null): Boolean
    fun find(contentId: String): LibraryItem?
    suspend fun membership(item: LibraryItem): Map<String, Boolean>
    fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean>
    fun membershipRemovalConfirmation(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
    ): TrackingMembershipRemovalConfirmation? = null
    suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean = false,
    ): TrackingMembershipResolution?
}

/** Provider adapter for watched-history projection and explicit history mutations. */
interface TrackingWatchedProvider : WatchedSyncAdapter {
    val providerId: TrackingProviderId
}

data class TrackingProgressSnapshot(
    val entries: List<WatchProgressEntry> = emptyList(),
    val hiddenContentIds: Set<String> = emptySet(),
    val hasLoadedRemoteProgress: Boolean = false,
    val errorMessage: String? = null,
)

/** Provider-owned projection and policy for remote playback progress. */
interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val changes: Flow<Unit>
    fun showIdSiblings(): Map<String, Set<String>> = emptyMap()

    /** True when the provider projection already contains display-ready metadata. */
    val providesCompleteMetadata: Boolean
        get() = false

    /** True when completed progress is already reconciled into watched history by the provider. */
    val ownsCompletedHistoryProjection: Boolean
        get() = false

    /** Optional age cutoff applied to continue-watching entries and completed next-up seeds. */
    fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? = null

    /** Provider policy for deciding whether a progress row is a valid completed next-up seed. */
    fun shouldUseAsNextUpSeed(entry: WatchProgressEntry, nowEpochMs: Long): Boolean =
        entry.shouldUseAsCompletedSeedForContinueWatching()

    /** Maps provider episode numbering into the application's metadata numbering when needed. */
    suspend fun prepareNextUpProgressEntries(
        entries: List<WatchProgressEntry>,
        contentId: String,
    ): List<WatchProgressEntry> = entries

    fun ensureLoaded()
    fun onProfileChanged() = Unit
    fun clearLocalState() = Unit
    fun onActivated() = Unit
    suspend fun refresh(force: Boolean, sourceChanged: Boolean)
    fun snapshot(): TrackingProgressSnapshot
    suspend fun removeProgress(entries: Collection<WatchProgressEntry>)
    fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) = Unit
    fun applyOptimisticProgress(entry: WatchProgressEntry) = Unit
    fun normalizeParentContentId(parentContentId: String, videoId: String?): String = parentContentId
    suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) = Unit
    fun isHiddenFromProgress(contentId: String): Boolean = false
}
