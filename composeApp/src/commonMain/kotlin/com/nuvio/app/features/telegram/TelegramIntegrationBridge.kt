package com.nuvio.app.features.telegram

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral contract. Android Full installs the TDLib-backed adapter;
 * platforms without that native runtime remain safely unavailable.
 */
data class TelegramChatSource(
    val id: Long,
    val title: String,
    val username: String? = null,
    val isChannel: Boolean = false,
    val isSelected: Boolean = false,
)

data class TelegramIntegrationSnapshot(
    val configured: Boolean = false,
    val authenticated: Boolean = false,
    val authState: String = "Unavailable",
    val accountName: String? = null,
    val qrLink: String? = null,
    val error: String? = null,
    val isBusy: Boolean = false,
    val chats: List<TelegramChatSource> = emptyList(),
    val selectedChatIds: Set<Long> = emptySet(),
    val cacheSizeBytes: Long = 0L,
)

interface TelegramPlatformAdapter {
    val snapshot: StateFlow<TelegramIntegrationSnapshot>
    fun initialize()
    suspend fun configureAndConnect(apiId: String, apiHash: String): Boolean
    suspend fun signInWithPhone(phone: String)
    suspend fun signInWithCode(code: String)
    suspend fun signInWithPassword(password: String)
    suspend fun signInWithBotToken(token: String)
    suspend fun requestQrLogin()
    suspend fun refreshChats()
    suspend fun addChatSource(identifier: String): Boolean
    suspend fun toggleChatSource(chatId: Long, selected: Boolean)
    suspend fun clearCache()
    suspend fun disconnect()
    suspend fun resolveStreams(
        type: String,
        videoId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String?,
    ): List<StreamItem>
}

object TelegramIntegrationBridge {
    @Volatile
    private var adapter: TelegramPlatformAdapter? = null

    private val unavailableState = kotlinx.coroutines.flow.MutableStateFlow(
        TelegramIntegrationSnapshot()
    )

    val snapshot: StateFlow<TelegramIntegrationSnapshot>
        get() = adapter?.snapshot ?: unavailableState

    fun register(platformAdapter: TelegramPlatformAdapter) {
        adapter = platformAdapter
    }

    fun isAvailable(): Boolean = adapter != null

    fun isAuthenticated(): Boolean = adapter?.snapshot?.value?.authenticated == true

    fun initialize() {
        adapter?.initialize()
    }

    suspend fun configureAndConnect(apiId: String, apiHash: String): Boolean =
        adapter?.configureAndConnect(apiId, apiHash) ?: false

    suspend fun signInWithPhone(phone: String) {
        adapter?.signInWithPhone(phone)
    }

    suspend fun signInWithCode(code: String) {
        adapter?.signInWithCode(code)
    }

    suspend fun signInWithPassword(password: String) {
        adapter?.signInWithPassword(password)
    }

    suspend fun signInWithBotToken(token: String) {
        adapter?.signInWithBotToken(token)
    }

    suspend fun requestQrLogin() {
        adapter?.requestQrLogin()
    }

    suspend fun refreshChats() {
        adapter?.refreshChats()
    }

    suspend fun addChatSource(identifier: String): Boolean =
        adapter?.addChatSource(identifier) ?: false

    suspend fun toggleChatSource(chatId: Long, selected: Boolean) {
        adapter?.toggleChatSource(chatId, selected)
    }

    suspend fun clearCache() {
        adapter?.clearCache()
    }

    suspend fun disconnect() {
        adapter?.disconnect()
    }

    suspend fun resolveStreams(
        type: String,
        videoId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String?,
    ): List<StreamItem> = adapter?.resolveStreams(
        type = type,
        videoId = videoId,
        title = title,
        year = year,
        season = season,
        episode = episode,
        imdbId = imdbId,
    ).orEmpty()
}
