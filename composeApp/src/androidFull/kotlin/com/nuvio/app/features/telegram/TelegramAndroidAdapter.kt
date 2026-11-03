package com.nuvio.app.features.telegram

import android.content.Context
import android.util.Log
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal class TelegramAndroidAdapter(context: Context) : TelegramPlatformAdapter {
    companion object {
        private const val TAG = "NuvioTelegram"
        private const val PREFS = "telegram_private_settings"
        private const val API_ID = "api_id"
        private const val API_HASH = "api_hash"
        private const val SELECTED_CHAT_IDS = "selected_chat_ids"
        private const val CHAT_LIMIT = 200
        private const val SEARCH_LIMIT = 40
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val CACHE_MAX_ENTRIES = 96
    }

    private data class CachedLookup(val expiresAt: Long, val streams: List<StreamItem>)

    private data class ChatSearchResult(
        val candidates: List<VideoCandidate>,
        val complete: Boolean,
    )

    private data class VideoCandidate(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val caption: String,
        val durationSeconds: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val hasAudioTag: Boolean = false,
        val hasSubtitleTag: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDirectory = File(appContext.filesDir, "telegram/files")
    private val tdClient = TelegramTdClient(
        context = appContext,
        apiIdProvider = { preferences.getInt(API_ID, 0) },
        apiHashProvider = { preferences.getString(API_HASH, "").orEmpty() },
    )
    private val proxy = TelegramStreamingProxy(appContext, tdClient)
    private val lookupCache = ConcurrentHashMap<String, CachedLookup>()
    private val _snapshot = MutableStateFlow(buildInitialSnapshot())
    override val snapshot: StateFlow<TelegramIntegrationSnapshot> = _snapshot.asStateFlow()

    init {
        // The loopback server starts lazily when a Telegram stream URL is registered.
        // TDLib is initialized once by the bootstrap after the adapter is registered.
        tdClient.authState.collectInBackground()
    }

    private fun buildInitialSnapshot(): TelegramIntegrationSnapshot = TelegramIntegrationSnapshot(
        configured = preferences.getInt(API_ID, 0) > 0 &&
            preferences.getString(API_HASH, "").orEmpty().matches(Regex("[a-fA-F0-9]{32}")),
        authenticated = false,
        authState = "Idle",
        selectedChatIds = readSelectedChatIds(),
        cacheSizeBytes = calculateCacheSize(),
    )

    private fun kotlinx.coroutines.flow.StateFlow<TelegramTdAuthState>.collectInBackground() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            collect { state -> publishAuthState(state) }
        }
    }

    private fun publishAuthState(state: TelegramTdAuthState) {
        val current = _snapshot.value
        val next = when (state) {
            TelegramTdAuthState.Idle -> current.copy(authState = "Idle", authenticated = false, accountName = null, qrLink = null)
            TelegramTdAuthState.Initializing -> current.copy(authState = "Connecting", error = null, isBusy = true)
            TelegramTdAuthState.WaitPhone -> current.copy(authState = "WaitPhone", authenticated = false, qrLink = null, error = null, isBusy = false)
            is TelegramTdAuthState.WaitCode -> current.copy(authState = "WaitCode", authenticated = false, qrLink = null, error = null, isBusy = false)
            TelegramTdAuthState.WaitPassword -> current.copy(authState = "WaitPassword", authenticated = false, qrLink = null, error = null, isBusy = false)
            is TelegramTdAuthState.WaitQr -> current.copy(authState = "WaitQr", authenticated = false, qrLink = state.link, error = null, isBusy = false)
            is TelegramTdAuthState.Ready -> current.copy(authState = "Ready", authenticated = true, accountName = state.firstName, qrLink = null, error = null, isBusy = false)
            is TelegramTdAuthState.Failed -> current.copy(authState = "Error", authenticated = false, qrLink = null, error = state.message, isBusy = false)
        }
        _snapshot.value = next.copy(
            configured = preferences.getInt(API_ID, 0) > 0 &&
                preferences.getString(API_HASH, "").orEmpty().matches(Regex("[a-fA-F0-9]{32}")),
            selectedChatIds = readSelectedChatIds(),
            cacheSizeBytes = calculateCacheSize(),
        )
        if (state is TelegramTdAuthState.Ready) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
                refreshChats()
            }
        }
    }

    override fun initialize() {
        val configured = preferences.getInt(API_ID, 0) > 0 &&
            preferences.getString(API_HASH, "").orEmpty().matches(Regex("[a-fA-F0-9]{32}"))
        _snapshot.value = _snapshot.value.copy(
            configured = configured,
            authState = if (configured) _snapshot.value.authState else "Idle",
            authenticated = if (configured) _snapshot.value.authenticated else false,
            error = if (configured) _snapshot.value.error else null,
            isBusy = if (configured) _snapshot.value.isBusy else false,
        )
        if (configured) tdClient.initialize()
    }

    override suspend fun configureAndConnect(apiId: String, apiHash: String): Boolean {
        val parsedId = apiId.trim().toIntOrNull()?.takeIf { it > 0 } ?: return fail("API ID must be a positive integer.")
        val cleanHash = apiHash.trim()
        if (!cleanHash.matches(Regex("[a-fA-F0-9]{32}"))) {
            return fail("API hash must contain exactly 32 hexadecimal characters.")
        }
        preferences.edit().putInt(API_ID, parsedId).putString(API_HASH, cleanHash).apply()
        lookupCache.clear()
        tdClient.resetAndWait()
        _snapshot.value = _snapshot.value.copy(
            configured = true,
            authState = "Connecting",
            authenticated = false,
            error = null,
            isBusy = true,
        )
        tdClient.initialize()
        return true
    }

    override suspend fun signInWithPhone(phone: String) {
        val value = phone.trim()
        if (value.isBlank()) {
            fail("Enter your phone number with country code.")
            return
        }
        runAuthAction { tdClient.submitPhone(value) }
    }

    override suspend fun signInWithCode(code: String) {
        val value = code.trim()
        if (value.isBlank()) {
            fail("Enter the Telegram login code.")
            return
        }
        runAuthAction { tdClient.submitCode(value) }
    }

    override suspend fun signInWithPassword(password: String) {
        if (password.isBlank()) {
            fail("Enter your Telegram two-step verification password.")
            return
        }
        runAuthAction { tdClient.submitPassword(password) }
    }

    override suspend fun signInWithBotToken(token: String) {
        val value = token.trim()
        if (value.isBlank()) {
            fail("Enter a bot token.")
            return
        }
        runAuthAction { tdClient.submitBotToken(value) }
    }

    override suspend fun requestQrLogin() {
        if (!snapshot.value.configured) {
            fail("Configure your Telegram API ID and hash first.")
            return
        }
        runAuthAction { tdClient.requestQrCode() }
    }

    private suspend fun runAuthAction(action: suspend () -> Unit) {
        try {
            action()
            _snapshot.value = _snapshot.value.copy(error = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Telegram authentication action failed", error)
            _snapshot.value = _snapshot.value.copy(
                error = error.message ?: "Telegram authentication failed. Try again.",
                isBusy = false,
            )
        }
    }

    override suspend fun refreshChats() {
        if (!tdClient.isAuthenticated()) return
        _snapshot.value = _snapshot.value.copy(isBusy = true, error = null)
        try {
            val response = tdClient.sendRequest(TdApi.GetChats().also { it.limit = CHAT_LIMIT }) as? TdApi.Chats
            val selected = readSelectedChatIds()
            // Preserve explicitly selected sources even when they are outside the first chat-list page.
            val listedChatIds = (response?.chatIds?.toList().orEmpty() + selected).distinct()
            val chatList = listedChatIds.mapNotNull { chatId ->
                val chat = tdClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat ?: return@mapNotNull null
                val isChannel = when (val kind = chat.type) {
                    is TdApi.ChatTypeSupergroup -> kind.isChannel
                    is TdApi.ChatTypeBasicGroup -> false
                    else -> return@mapNotNull null
                }
                TelegramChatSource(
                    id = chat.id,
                    title = chat.title,
                    username = null,
                    isChannel = isChannel,
                    isSelected = chat.id in selected,
                )
            }.sortedBy { it.title.lowercase(Locale.ROOT) }
            _snapshot.value = _snapshot.value.copy(
                chats = chatList,
                selectedChatIds = selected,
                isBusy = false,
                cacheSizeBytes = calculateCacheSize(),
                error = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not load Telegram chats", error)
            _snapshot.value = _snapshot.value.copy(isBusy = false, error = error.message ?: "Could not load Telegram chats.")
        }
    }

    override suspend fun addChatSource(identifier: String): Boolean {
        if (!tdClient.isAuthenticated()) return fail("Connect Telegram before adding a channel.")
        val raw = identifier.trim()
        if (raw.isBlank()) return fail("Enter a Telegram channel username or chat ID.")
        val chat = try {
            val cleanLink = when {
                raw.startsWith("https://t.me/", ignoreCase = true) -> raw
                raw.startsWith("http://t.me/", ignoreCase = true) -> raw.replaceFirst("http://", "https://")
                raw.startsWith("t.me/", ignoreCase = true) -> "https://$raw"
                raw.startsWith("+") -> "https://t.me/$raw"
                else -> null
            }
            val inviteLink = cleanLink?.takeIf {
                it.contains("/+") || it.contains("/joinchat/")
            }
            if (inviteLink != null) {
                // Invite links are joined only after the user explicitly adds them in this screen.
                tdClient.sendRequest(TdApi.JoinChatByInviteLink(inviteLink)) as? TdApi.Chat
            } else {
                val cleanPath = cleanLink?.removePrefix("https://t.me/") ?: raw
                val isPrivateLink = cleanPath.startsWith("c/")
                val normalized = cleanPath
                    .removePrefix("c/")
                    .removePrefix("@")
                    .substringBefore('/')
                    .trim()
                val numericId = if (isPrivateLink) {
                    normalized.toLongOrNull()?.let { "-100$it".toLongOrNull() }
                } else {
                    normalized.toLongOrNull()
                }
                if (numericId != null) {
                    tdClient.sendRequest(TdApi.GetChat(numericId)) as? TdApi.Chat
                } else {
                    tdClient.sendRequest(TdApi.SearchPublicChat(normalized)) as? TdApi.Chat
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(error.message ?: "Telegram could not find that chat.")
        } ?: return fail("Telegram could not resolve that channel. Join it in Telegram first, then retry.")

        when (chat.type) {
            is TdApi.ChatTypeSupergroup, is TdApi.ChatTypeBasicGroup -> Unit
            else -> return fail("Only Telegram groups and channels can be added as stream sources.")
        }
        val selected = readSelectedChatIds() + chat.id
        saveSelectedChatIds(selected)
        refreshChats()
        return true
    }

    override suspend fun toggleChatSource(chatId: Long, selected: Boolean) {
        val ids = readSelectedChatIds().toMutableSet()
        if (selected) ids += chatId else ids -= chatId
        saveSelectedChatIds(ids)
        _snapshot.value = _snapshot.value.copy(
            selectedChatIds = ids,
            chats = _snapshot.value.chats.map { it.copy(isSelected = it.id in ids) },
        )
        lookupCache.clear()
    }

    override suspend fun clearCache() {
        // Evict only TDLib file IDs known to this proxy; never touch Nuvio downloads or account data.
        proxy.clearCache()
        lookupCache.clear()
        _snapshot.value = _snapshot.value.copy(cacheSizeBytes = calculateCacheSize())
    }

    override suspend fun disconnect() {
        tdClient.resetAndWait(logOut = true)
        proxy.stop()
        proxy.forgetRegisteredFiles()
        lookupCache.clear()
        _snapshot.value = _snapshot.value.copy(
            authenticated = false,
            authState = "Idle",
            accountName = null,
            qrLink = null,
            chats = emptyList(),
            selectedChatIds = emptySet(),
            error = null,
            isBusy = false,
        )
        withContext(Dispatchers.IO) {
            File(appContext.filesDir, "telegram/session_ready").delete()
            File(appContext.filesDir, "telegram/tdlib").deleteRecursively()
            cacheDirectory.deleteRecursively()
        }
        saveSelectedChatIds(emptySet())
    }

    override suspend fun resolveStreams(
        type: String,
        videoId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String?,
    ): List<StreamItem> {
        if (!tdClient.isAuthenticated() || title.isBlank()) return emptyList()
        val selectedChatIds = readSelectedChatIds()
        if (selectedChatIds.isEmpty()) return emptyList()

        val normalizedType = normalizeMediaType(type)
        val key = listOf(
            normalizedType,
            videoId,
            imdbId.orEmpty(),
            title.lowercase(Locale.ROOT),
            year?.toString().orEmpty(),
            season?.toString().orEmpty(),
            episode?.toString().orEmpty(),
            selectedChatIds.sorted().joinToString(","),
        ).joinToString("|")
        lookupCache[key]?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { return it.streams }

        val queries = buildQueries(title, year, season, episode, normalizedType)
        val semaphore = Semaphore(permits = 3)
        val chatResults = coroutineScope {
            selectedChatIds.map { chatId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { searchChat(chatId, queries) }
                }
            }.map { deferred -> deferred.await() }
        }
        val searchComplete = chatResults.all(ChatSearchResult::complete)
        val candidates = chatResults.flatMap(ChatSearchResult::candidates)

        val matched = candidates
            .distinctBy { "${it.chatId}:${it.messageId}:${it.fileId}" }
            .mapNotNull { candidate ->
                val score = matchCandidate(candidate, title, year, season, episode, normalizedType)
                if (score <= 0) return@mapNotNull null
                candidate to score
            }
            .sortedWith(
                compareByDescending<Pair<VideoCandidate, Int>> { it.second }
                    .thenByDescending { it.first.height }
                    .thenByDescending { it.first.fileSize },
            )
            .map { (candidate, _) -> candidate.toStreamItem() }

        if (searchComplete) {
            lookupCache[key] = CachedLookup(System.currentTimeMillis() + CACHE_TTL_MS, matched)
            if (lookupCache.size > CACHE_MAX_ENTRIES) {
                val oldestKeys = lookupCache.entries.sortedBy { it.value.expiresAt }
                    .take(lookupCache.size - CACHE_MAX_ENTRIES).map { it.key }
                oldestKeys.forEach(lookupCache::remove)
            }
        }
        return matched
    }

    private suspend fun searchChat(chatId: Long, queries: List<String>): ChatSearchResult {
        val found = linkedMapOf<String, VideoCandidate>()
        // SearchChatMessages is deliberately bounded to one page per phrase. If Telegram reports
        // more matches than it returned, preserve the streams found but do not cache the lookup as
        // complete; otherwise a truncated page could be mistaken for an exhaustive empty result.
        var complete = true
        for (query in queries) {
            for (filter in listOf(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterDocument())) {
                val response = try {
                    tdClient.sendRequest(TdApi.SearchChatMessages().also { request ->
                        request.chatId = chatId
                        request.query = query
                        request.fromMessageId = 0L
                        request.offset = 0
                        request.limit = SEARCH_LIMIT
                        request.filter = filter
                    }, timeoutMs = 20_000L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Telegram search failed for chat $chatId and query '$query'", error)
                    return ChatSearchResult(found.values.toList(), complete = false)
                } ?: run {
                    Log.w(TAG, "Telegram search timed out for chat $chatId and query '$query'")
                    return ChatSearchResult(found.values.toList(), complete = false)
                }

                val result = response as? TdApi.FoundChatMessages
                    ?: return ChatSearchResult(found.values.toList(), complete = false)
                if (result.totalCount > result.messages.size) {
                    complete = false
                    Log.w(
                        TAG,
                        "Telegram search page is truncated for chat " + chatId + " and query '" + query +
                            "' (" + result.messages.size + "/" + result.totalCount +
                            "); results will not be cached as complete",
                    )
                }
                result.messages.forEach { message ->
                    extractCandidate(message)?.let { candidate ->
                        found["${candidate.chatId}:${candidate.messageId}:${candidate.fileId}"] = candidate
                    }
                }
            }
        }
        return ChatSearchResult(found.values.toList(), complete = complete)
    }

    private fun extractCandidate(message: TdApi.Message): VideoCandidate? {
        val caption: String
        val fileName: String
        val fileId: Int
        val fileSize: Long
        val mimeType: String
        var duration = 0
        var width = 0
        var height = 0

        when (val content = message.content) {
            is TdApi.MessageVideo -> {
                val video = content.video
                fileName = video.fileName
                fileId = video.video.id
                fileSize = video.video.size
                mimeType = video.mimeType
                duration = video.duration
                width = video.width
                height = video.height
                caption = content.caption.text
            }
            is TdApi.MessageDocument -> {
                val document = content.document
                fileName = document.fileName
                fileId = document.document.id
                fileSize = document.document.size
                mimeType = document.mimeType
                caption = content.caption.text
            }
            else -> return null
        }

        if (fileId <= 0 || fileSize <= 0L || fileName.isBlank()) return null
        val videoExtension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg", "3gp")
        if (!mimeType.startsWith("video/") && mimeType != "application/x-matroska" && !videoExtension) return null
        return VideoCandidate(
            chatId = message.chatId,
            messageId = message.id,
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            caption = caption,
            durationSeconds = duration,
            width = width,
            height = height,
            hasAudioTag = Regex("(?i)\\b(dual[ ._-]?audio|multi[ ._-]?audio|dubbed|aac|ac3|eac3|dts|truehd|atmos)\\b")
                .containsMatchIn("$fileName $caption"),
            hasSubtitleTag = Regex("(?i)\\b(subs?|subbed|subtitle|softsubs?|hardsubs?|engsub|multisub)\\b")
                .containsMatchIn("$fileName $caption"),
        )
    }

    private fun VideoCandidate.toStreamItem(): StreamItem {
        val heightValue = max(height, parseHeight(fileName))
        val quality = when {
            heightValue >= 2160 -> "2160p"
            heightValue >= 1440 -> "1440p"
            heightValue >= 1080 -> "1080p"
            heightValue >= 720 -> "720p"
            heightValue >= 480 -> "480p"
            heightValue > 0 -> "${heightValue}p"
            else -> "Unknown quality"
        }
        val codec = Regex("(?i)\\b(x265|h\\.?265|hevc|x264|h\\.?264|av1)\\b")
            .find(fileName)?.value?.uppercase(Locale.ROOT)
        val details = buildList {
            add(quality)
            if (codec != null) add(codec)
            add(humanBytes(fileSize))
            if (durationSeconds > 0) add("${durationSeconds / 60} min")
            if (hasAudioTag) add("Audio tagged")
            if (hasSubtitleTag) add("Subs tagged")
        }.joinToString(" · ")
        return StreamItem(
            name = "[Telegram] $details",
            title = fileName,
            description = details,
            url = proxy.streamUrl(fileId, mimeType),
            sourceName = "Telegram",
            addonName = "Telegram",
            addonId = "telegram_native",
            streamType = "direct",
            behaviorHints = StreamBehaviorHints(
                filename = fileName,
                videoSize = fileSize,
            ),
        )
    }

    private fun matchCandidate(
        candidate: VideoCandidate,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        type: String,
    ): Int {
        val searchable = normalize("${candidate.fileName} ${candidate.caption}")
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isBlank()) return 0
        val tokens = normalizedTitle.split(' ').filter { it.length > 1 }
        if (tokens.isEmpty()) return 0
        val matchedTokens = tokens.count { token -> searchable.contains(token) }
        if (matchedTokens < tokens.size) return 0

        val seasonValue = season
        val episodeValue = episode
        if (type == "tv" && seasonValue != null && episodeValue != null) {
            val episodePatterns = listOf(
                Regex("(?i)s0*${seasonValue}\\s*e0*${episodeValue}(?!\\d)"),
                Regex("(?i)0*${seasonValue}x0*${episodeValue}(?!\\d)"),
            )
            if (episodePatterns.none { it.containsMatchIn(searchable) }) return 0
        }

        if (type == "movie" && year != null) {
            val years = Regex("\\b(19\\d{2}|20\\d{2})\\b").findAll(searchable).mapNotNull { it.value.toIntOrNull() }.toList()
            if (years.isNotEmpty() && year !in years) return 0
        }

        var score = 60 + matchedTokens * 5
        if (year != null && searchable.contains(year.toString())) score += 5
        if (seasonValue != null && episodeValue != null && type == "tv") score += 20
        if (candidate.height > 0) score += 2
        return score
    }

    private fun buildQueries(title: String, year: Int?, season: Int?, episode: Int?, type: String): List<String> {
        val cleanTitle = title
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanTitle.isBlank()) return emptyList()
        val episodeQueries = if (type == "tv" && season != null && episode != null) {
            listOf(
                "$cleanTitle S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}",
                "$cleanTitle ${season}x${episode}",
                cleanTitle,
            )
        } else {
            listOfNotNull("$cleanTitle ${year ?: ""}".trim(), cleanTitle)
        }
        return episodeQueries.distinct().take(3)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeMediaType(type: String): String =
        if (type.equals("tv", true) || type.equals("series", true)) "tv" else "movie"

    private fun parseHeight(fileName: String): Int =
        Regex("(?i)(2160|1440|1080|720|576|480|360)\\s*p")
            .find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024L} KB"
    }

    private fun readSelectedChatIds(): Set<Long> =
        preferences.getString(SELECTED_CHAT_IDS, "")
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .toSet()

    private fun saveSelectedChatIds(ids: Set<Long>) {
        preferences.edit().putString(SELECTED_CHAT_IDS, ids.sorted().joinToString(",")).apply()
    }

    private fun calculateCacheSize(): Long =
        if (cacheDirectory.exists()) cacheDirectory.walkTopDown().filter(File::isFile).sumOf(File::length) else 0L

    private fun fail(message: String): Boolean {
        _snapshot.value = _snapshot.value.copy(error = message, isBusy = false)
        return false
    }
}
