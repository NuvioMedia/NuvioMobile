package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class UsenetProviderTestResult { SUCCESS, UNREACHABLE, TLS, AUTH, REFUSED, PRIVATE_NETWORK, INVALID }

/** Why the last request to an indexer failed. Categories only: raw errors could echo keys. */
@kotlinx.serialization.Serializable
enum class UsenetIndexerProblem { AUTH, UNREACHABLE, RESPONSE }

/**
 * What settings show for an indexer: a limit pause, the last failure, and the API hits and
 * grabs it reported left for today, when it reports them.
 */
data class UsenetIndexerStatus(
    val pausedUntil: Long = 0,
    val problem: UsenetIndexerProblem? = null,
    val apiHitsLeft: Int? = null,
    val grabsLeft: Int? = null,
    /** When the indexer reported what is left. */
    val leftAt: Long = 0,
)

/** One delivery of a built-in search: the group as it stands now, or an indexer's failure. */
data class UsenetSearchUpdate(
    val group: AddonStreamGroup? = null,
    val failure: String? = null,
)

/** Why preparing a Usenet stream failed, as the engine and the app classify it. */
class UsenetPlaybackException(
    message: String,
    /** The engine's failure kind, such as "missing-article", when it sent one. */
    val failure: String? = null,
) : Exception(message)

/**
 * What runs only where the Usenet engine exists: credential storage, indexer searches, provider
 * tests and the engine itself. Android launches the engine as a child process; iOS has none.
 */
internal expect object UsenetPlatform {
    val isSupported: Boolean

    /** Decrypts the profile's sources; throws when the device keystore cannot read them. */
    fun loadSources(profileId: Int): UsenetSourceConfiguration

    /** Validates and stores the profile's sources; throws [IllegalArgumentException] with the reason. */
    fun saveSources(profileId: Int, value: UsenetSourceConfiguration)

    fun removeProfile(profileId: Int)

    /** Removes every profile's sources and the device-local search state, for an account reset. */
    fun clearAll()

    fun loadEngineSettings(): UsenetConfiguration

    fun saveEngineSettings(value: UsenetConfiguration)

    suspend fun testProvider(provider: UsenetProvider, allowPrivateNetwork: Boolean): UsenetProviderTestResult

    /** Live capabilities request and a one-result search; false on any failure. */
    suspend fun testIndexer(indexer: UsenetIndexer): Boolean

    /** Increments whenever an indexer status may have changed. */
    val indexerStatusChanges: StateFlow<Int>

    fun indexerStatus(indexer: UsenetIndexer): UsenetIndexerStatus

    /** Searches every enabled indexer; nothing when [config] is not ready or the type is not searched. */
    fun search(
        config: UsenetSourceConfiguration,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        groupName: String,
    ): Flow<UsenetSearchUpdate>

    /** Increments when a release's outcome is remembered, so shown results can be arranged again. */
    val outcomeChanges: StateFlow<Int>

    /** Labels and orders the built-in group by how its releases last went; other groups are unchanged. */
    fun arrangeOutcomes(groups: List<AddonStreamGroup>, availableFirst: Boolean): List<AddonStreamGroup>

    /** Opens [stream] in the engine; the returned stream plays from the engine's local URL. */
    suspend fun prepare(stream: StreamItem, season: Int?, episode: Int?, profileId: Int): StreamItem

    /** Ends the engine session that plays [url], if it still owns it. */
    fun release(url: String?)

    fun isEngineUrl(url: String?): Boolean

    /** The release behind the engine URL [url] showed its first frame. */
    fun onFirstFrame(url: String?)

    /** Playback of the engine URL [url] failed with the engine's [failure] kind. */
    fun onPlaybackFailure(url: String?, failure: String?)

    fun onAppForegrounded()

    fun onAppBackgrounded()

    /** [epochMs] as a short local time of day, such as 18:30. */
    fun clockTime(epochMs: Long): String
}
