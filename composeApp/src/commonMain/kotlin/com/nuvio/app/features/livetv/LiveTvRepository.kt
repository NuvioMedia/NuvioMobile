package com.nuvio.app.features.livetv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LiveTvRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    fun ensureLoaded() {
        LiveTvSettingsRepository.ensureLoaded()
        if (_uiState.value.channels.isEmpty() && !_uiState.value.isLoading) {
            refresh()
        }
    }

    fun refresh() {
        scope.launch {
            refreshMutex.withLock {
                val nowMs = LiveTvTime.nowEpochMs()
                val (windowStartMs, windowEndMs) = LiveTvTime.defaultGuideWindow(nowMs)
                val settings = LiveTvSettingsRepository
                settings.ensureLoaded()
                val plutoEnabled = settings.plutoEnabled.value
                val xtreamConfig = settings.xtreamConfig.value
                val favorites = settings.favoriteChannelIds.value
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    errorMessage = null,
                    nowMs = nowMs,
                    guideWindowStartMs = windowStartMs,
                    guideWindowEndMs = windowEndMs,
                    plutoEnabled = plutoEnabled,
                    xtreamEnabled = xtreamConfig.enabled,
                    favoriteChannelIds = favorites,
                )
                try {
                    val channels = buildList {
                        if (plutoEnabled) {
                            addAll(PlutoPlaylist.channels(nowMs))
                        }
                        if (xtreamConfig.enabled) {
                            addAll(XtreamCodesClient.fetchChannels(xtreamConfig))
                        }
                    }.distinctBy { it.id }

                    val epg = mutableMapOf<String, List<EpgProgram>>()
                    if (plutoEnabled) {
                        val plutoChannels = channels.filter { it.source == LiveTvSource.Pluto }
                        epg.putAll(
                            PlutoPlaylist.placeholderEpg(
                                channels = plutoChannels,
                                windowStartMs = windowStartMs,
                                windowEndMs = windowEndMs,
                                nowMs = nowMs,
                            ),
                        )
                    }
                    if (xtreamConfig.enabled) {
                        channels
                            .filter { it.source == LiveTvSource.Xtream }
                            .take(40)
                            .forEach { channel ->
                                val programs = XtreamCodesClient.fetchEpgForChannel(
                                    config = xtreamConfig,
                                    channel = channel,
                                ).filter { program ->
                                    program.endMs > windowStartMs && program.startMs < windowEndMs
                                }
                                if (programs.isNotEmpty()) {
                                    epg[channel.id] = programs
                                }
                            }
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        channels = channels,
                        epgByChannelId = epg,
                        favoriteChannelIds = favorites,
                        errorMessage = if (channels.isEmpty()) {
                            "No channels available. Enable Pluto TV or configure Xtream Codes in settings."
                        } else {
                            null
                        },
                    )
                } catch (error: Throwable) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load live TV guide.",
                    )
                }
            }
        }
    }

    fun publishFavorites(favorites: Set<String>) {
        _uiState.value = _uiState.value.copy(favoriteChannelIds = favorites)
    }
}
