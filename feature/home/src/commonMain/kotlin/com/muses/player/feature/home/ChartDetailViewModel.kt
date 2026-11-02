package com.muses.player.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.search.OnlineChartService
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class ChartDetailUiState(
    val songs: List<OnlineSearchResult> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/** 单个排行榜歌曲页状态与播放逻辑。 */
class ChartDetailViewModel(
    private val chartService: OnlineChartService,
    private val chartCacheStore: OnlineChartCacheStore,
    private val scriptRepository: LxScriptRepository,
    private val settingsRepository: SettingsRepository,
    private val playback: PlaybackPort,
    private val platform: String,
    private val chartId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChartDetailUiState())
    val state: StateFlow<ChartDetailUiState> = _state.asStateFlow()
    private val preferredQuality = settingsRepository.onlinePreferredQuality.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LxQuality.DEFAULT.key,
    )
    private var loadJob: Job? = null
    private var loadSeq = 0L

    init { loadSongs() }

    fun retry() = loadSongs(forceRefresh = true)

    fun refresh() = loadSongs(forceRefresh = true)

    private fun loadSongs(forceRefresh: Boolean = false) {
        val requestSeq = ++loadSeq
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = _state.value.songs.isEmpty(),
            refreshing = _state.value.songs.isNotEmpty(),
            error = null,
        )
        loadJob = viewModelScope.launch {
            var cached: CachedChartSongs? = null
            var updateInfo: String? = null
            run {
                cached = runCatching { chartCacheStore.loadSongs(platform, chartId) }.getOrNull()
                updateInfo = runCatching {
                    chartCacheStore.loadCatalogs()[platform]?.charts?.firstOrNull { it.chartId == chartId }?.updateInfo
                }.getOrNull()
                if (requestSeq != loadSeq) return@launch
                if (cached != null) {
                    _state.value = _state.value.copy(
                        songs = cached.songs,
                        loading = false,
                        refreshing = forceRefresh || cached.hasMore || !chartCacheStore.isSongsFresh(cached.updatedAt, updateInfo),
                        error = null,
                    )
                    if (!forceRefresh && !cached.hasMore && chartCacheStore.isSongsFresh(cached.updatedAt, updateInfo) &&
                        cached.songs.none { it.platform == "wy" && !it.musicInfoJson.contains("\"catalogQuality\"") }) return@launch
                }
            }
            _state.value = _state.value.copy(
                loading = _state.value.songs.isEmpty(),
                refreshing = _state.value.songs.isNotEmpty(),
                error = null,
            )
            runCatching {
                chartService.allChartSongs(platform, chartId)
            }.onSuccess { songs ->
                if (requestSeq != loadSeq) return@launch
                val updatedAt = Clock.System.now().toEpochMilliseconds()
                runCatching {
                    chartCacheStore.saveSongs(
                        platform,
                        chartId,
                        CachedChartSongs(songs, page = 1, hasMore = false, updatedAt = updatedAt),
                    )
                }
                if (requestSeq != loadSeq) return@launch
                _state.value = _state.value.copy(
                    songs = songs,
                    loading = false,
                    refreshing = false,
                    error = null,
                )
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (requestSeq != loadSeq) return@launch
                val message = failure.message ?: "排行榜歌曲加载失败，请稍后重试"
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = if (_state.value.songs.isNotEmpty()) null else message,
                )
                if (_state.value.songs.isNotEmpty()) MusesSnackbar.show(message)
            }
        }
    }

    fun play(index: Int) = playAt(index, shuffle = false)

    // 使用用户点击时看到的结果快照，刷新或筛选后不会把旧索引指向另一首歌。
    fun playResults(results: List<OnlineSearchResult>, start: Int = 0) = playAt(start, shuffle = false, results = results.toList())

    fun shuffle() {
        val songs = _state.value.songs
        if (songs.isNotEmpty()) playAt(songs.indices.random(), shuffle = true)
    }

    private fun playAt(index: Int, shuffle: Boolean, results: List<OnlineSearchResult> = _state.value.songs) {
        val target = results.getOrNull(index) ?: return
        val quality = LxQuality.fromKey(preferredQuality.value)?.key
        val songs: List<Song> = results.map { it.toSong(ONLINE_SOURCE_ID, quality) }
        OnlineTrackSession.remember(songs)
        playback.play(target.toSong(ONLINE_SOURCE_ID, quality).id, songs)
        if (shuffle) playback.setShuffleEnabled(true)
    }

    private companion object {
        const val ONLINE_SOURCE_ID = "online"
    }
}
