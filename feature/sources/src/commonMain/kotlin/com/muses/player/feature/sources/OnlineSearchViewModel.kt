package com.muses.player.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.model.Song
import com.muses.player.core.model.Album
import com.muses.player.core.model.Artist
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.repository.AlbumRepository
import com.muses.player.core.data.repository.ArtistRepository
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SearchHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.OnlineSearchService
import com.muses.player.core.search.PlatformSearchOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 单个平台在本次搜索中的状态（结果 + 分页 + 错误） */
data class PlatformSearchState(
    val platform: String,
    val displayName: String,
    val results: List<OnlineSearchResult> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    /** 已加载页数（用于加载更多） */
    val loadedPage: Int = 0,
)

/** 在线搜索页状态 */
data class OnlineSearchUiState(
    val category: SearchCategory = SearchCategory.ALL,
    val keyword: String = "",
    val searching: Boolean = false,
    /** 已发起过搜索（用于区分「未搜」与「无结果」） */
    val searched: Boolean = false,
    val searchedKeyword: String = "",
    val platforms: List<PlatformSearchState> = emptyList(),
    /** 当前平台筛选；null = 全部平台 */
    val filterPlatform: String? = null,
    val message: String? = null,
    val originalFirst: Boolean = true,
) {
    /** 按筛选过滤后的平台列表 */
    val visiblePlatforms: List<PlatformSearchState>
        get() = filterPlatform?.let { f -> platforms.filter { it.platform == f } } ?: platforms

    val totalResults: Int get() = platforms.sumOf { it.results.size }
}

data class LibrarySearchResults(
    val keyword: String = "",
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

/**
 * 曲库及在线搜索 ViewModel。
 *
 * 能力：
 * - 多平台并行搜索（部分平台失败不影响其它平台，失败原因按平台展示）；
 * - 按平台筛选；
 * - 分页加载更多（**注意咪咕固定每页 20 条**，见 MgSearchProvider）；
 * - 点结果直接播放：构造内存态 [Song]（不落库）→ 登记会话临时表 → 交给播放端口。
 *
 * 播放为什么登记 [OnlineTrackSession]：在线曲目不入库，而桌面播放链路按 songId 查库，
 * 登记后 songLookup 才有回退来源（详见该类注释）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSearchViewModel(
    private val searchService: OnlineSearchService,
    private val playback: PlaybackPort,
    /** 音源脚本仓库：播放前校验该平台是否有可用脚本，避免「点了没反应」 */
    private val scriptRepository: LxScriptRepository,
    private val settingsRepository: SettingsRepository,
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    /** 在线曲目归属的音源 id（脚本仓库按平台路由，此值仅作标识） */
    private val onlineSourceId: String = "online",
    private val historyStore: SearchHistoryStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnlineSearchUiState(
            platforms = searchService.platforms.map {
                PlatformSearchState(platform = it, displayName = searchService.platformNames.getValue(it))
            },
        ),
    )
    val state: StateFlow<OnlineSearchUiState> = _state.asStateFlow()
    val recentSearches = (historyStore?.entries ?: flowOf(emptyList()))
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var historyJob: Job? = null
    private var draftHistory: String? = null
    private var confirmedHistory: String? = null
    private var historyGeneration = 0L

    private fun rememberSearch(keyword: String, draft: Boolean = false) {
        if (draft && confirmedHistory == keyword) return
        historyJob?.cancel()
        if (keyword.isBlank() || historyStore == null) return
        if (!draft) confirmedHistory = keyword
        historyJob = viewModelScope.launch {
            if (draft) delay(1_200)
            if (keyword != _state.value.keyword.trim()) return@launch
            try {
                val editedDraft = draftHistory?.takeIf { keyword.startsWith(it) || it.startsWith(keyword) }
                historyStore.record(keyword, editedDraft)
                draftHistory = if (draft) keyword else null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value = _state.value.copy(message = "搜索记录保存失败，搜索仍可使用") }
        }
    }

    fun searchHistory(keyword: String) { updateKeyword(keyword); search() }
    fun removeHistory(keyword: String) {
        historyGeneration++
        historyJob?.cancel(); draftHistory = null; confirmedHistory = null
        viewModelScope.launch { try { historyStore?.remove(keyword) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value = _state.value.copy(message = "删除搜索记录失败，请重试") } }
    }
    fun clearHistory() {
        historyGeneration++
        historyJob?.cancel(); draftHistory = null; confirmedHistory = null
        viewModelScope.launch { try { historyStore?.clear() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value = _state.value.copy(message = "清空搜索记录失败，请重试") } }
    }

    private val submittedKeyword = MutableStateFlow("")
    private val lyricSearchRevision = MutableStateFlow(0L)
    val libraryResults: StateFlow<LibrarySearchResults> = submittedKeyword.flatMapLatest { keyword ->
        if (keyword.isBlank()) flowOf(LibrarySearchResults())
        else combine(
            songRepository.observeSongs(keyword),
            albumRepository.observeAlbums(),
            artistRepository.observeArtists(),
        ) { songs, albums, artists ->
            LibrarySearchResults(
                keyword = keyword,
                songs = songs,
                albums = albums.filter {
                    it.title.contains(keyword, ignoreCase = true) ||
                        it.artist.orEmpty().contains(keyword, ignoreCase = true)
                },
                artists = artists.filter { it.name.contains(keyword, ignoreCase = true) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySearchResults())

    val lyricResults: StateFlow<LibraryLyricsResults> = combine(
        submittedKeyword,
        state.map { it.category }.distinctUntilChanged(),
        lyricSearchRevision,
    ) { keyword, category, revision -> Triple(keyword, category, revision) }.flatMapLatest { (keyword, category, _) ->
        if (keyword.isBlank() || category != SearchCategory.ALL) flowOf(LibraryLyricsResults())
        else songRepository.observeSongs().mapLatest { songs ->
            withContext(Dispatchers.Default) {
                val matches = songs.mapNotNull { song ->
                    currentCoroutineContext().ensureActive()
                    matchLibraryLyrics(song, keyword)
                }
                LibraryLyricsResults(keyword = keyword, matches = matches,
                    songsWithLyrics = songs.count { !it.lyrics.isNullOrBlank() })
            }
        }.onStart { emit(LibraryLyricsResults(keyword = keyword, loading = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryLyricsResults())

    private var searchJob: Job? = null
    private var autoSearchJob: Job? = null
    private var searchSeq = 0L

    /**
     * 当前首选音质（DataStore 持久化；key 见 [LxQuality]）。
     * 仅作偏好：脚本未声明该档位时会就近回退。
     */
    val preferredQuality: StateFlow<String> = settingsRepository.onlinePreferredQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, LxQuality.DEFAULT.key)

    /**
     * 可选音质档位：取**已加载脚本声明的并集**（按档位升序）。
     *
     * 不从固定列表取：不同音源脚本支持档位差异大（如咪咕只到 flac，QQ 才有多档全景声），
     * 声明并集才能避免用户选到「脚本根本不支持」的档位。
     * 尚未加载任何脚本时为空 → UI 隐藏音质选择行。
     */
    private val _availableQualities = MutableStateFlow<List<LxQuality>>(emptyList())
    val availableQualities: StateFlow<List<LxQuality>> = _availableQualities.asStateFlow()

    fun setPreferredQuality(qualityKey: String) {
        viewModelScope.launch { settingsRepository.setOnlinePreferredQuality(qualityKey) }
    }

    /** 从已加载脚本汇总可选音质（供 UI 展示） */
    fun refreshAvailableQualities() {
        viewModelScope.launch {
            _availableQualities.value = runCatching {
                scriptRepository.loadAll()
                    .filter { it.loadError == null }
                    .flatMap { script -> script.sources.values.flatMap { it.qualitys } }
                    .mapNotNull(LxQuality::fromKey)
                    .distinct()
                    .sortedBy { it.rank }
            }.getOrDefault(emptyList())
        }
    }

    private fun currentQuality(): LxQuality? = LxQuality.fromKey(preferredQuality.value)

    fun updateKeyword(value: String) {
        val previous = _state.value.keyword.trim()
        _state.value = _state.value.copy(keyword = value)
        val keyword = value.trim()
        if (keyword == previous) return
        confirmedHistory = null
        historyJob?.cancel()
        cancelPendingSearch()
        // 输入变化时立即作废旧请求，包括正在返回的分页。
        searchSeq++
        searchJob?.cancel()
        searchJob = null
        _state.value = _state.value.copy(
            searching = false,
            message = null,
            platforms = _state.value.platforms.map { it.copy(loading = false, loadingMore = false) },
        )
        if (keyword.isEmpty()) {
            draftHistory = null
            confirmedHistory = null
            submittedKeyword.value = ""
            _state.value = _state.value.copy(
                searched = false, searchedKeyword = "",
                platforms = _state.value.platforms.map {
                    PlatformSearchState(it.platform, it.displayName)
                },
            )
            return
        }
        autoSearchJob = viewModelScope.launch {
            delay(AUTO_SEARCH_DELAY_MS)
            search(keyword)
        }
    }

    private fun cancelPendingSearch() {
        autoSearchJob?.cancel()
        autoSearchJob = null
    }

    /**
     * 预填关键词（首页搜索框带入）。
     * 沿用输入的自动搜索逻辑，调用方也可随后调 [search] 立即搜索。
     */
    fun prefillKeyword(value: String) {
        updateKeyword(value)
    }

    fun setFilter(platform: String?) {
        _state.value = _state.value.copy(filterPlatform = platform)
    }

    /** 发起搜索（覆盖式）：重置全部平台状态后并行搜索 */
    fun search() {
        cancelPendingSearch()
        val keyword = _state.value.keyword.trim()
        rememberSearch(keyword)
        if (_state.value.searching && _state.value.searchedKeyword == keyword) return
        search(keyword)
    }

    fun setCategory(category: SearchCategory) {
        if (_state.value.category == category) return
        _state.value = _state.value.copy(category = category)
        if (_state.value.keyword.isNotBlank() && _state.value.keyword.trim() != _state.value.searchedKeyword) {
            cancelPendingSearch()
            search(_state.value.keyword.trim())
        }
    }

    fun refreshLyrics() {
        if (_state.value.keyword.isNotBlank()) search()
    }

    /** 刷新当前输入的关键词，保留平台筛选。 */
    suspend fun refresh() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isBlank()) return
        cancelPendingSearch()
        search(keyword, forceRefresh = true)
        searchJob?.join()
    }

    private fun search(keyword: String, forceRefresh: Boolean = false) {
        if (keyword.isEmpty()) {
            _state.value = _state.value.copy(message = "请输入搜索关键词")
            return
        }
        submittedKeyword.value = keyword
        val requestSeq = ++searchSeq
        val historyVersion = historyGeneration
        searchJob?.cancel()
        searchJob = null
        lyricSearchRevision.value++
        refreshAvailableQualities()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                searching = true,
                searched = true,
                searchedKeyword = keyword,
                message = null,
                platforms = searchService.platforms.map { p ->
                    PlatformSearchState(
                        platform = p,
                        displayName = searchService.platformNames.getValue(p),
                        loading = true,
                    )
                },
            )
            val outcomes = searchService.searchAll(keyword, page = 1, pageSize = DEFAULT_PAGE_SIZE, forceRefresh = forceRefresh)
            if (requestSeq != searchSeq) return@launch
            applyOutcomes(outcomes, page = 1)
            _state.value = _state.value.copy(searching = false)
            if (historyVersion == historyGeneration && outcomes.any { it is PlatformSearchOutcome.Success }) rememberSearch(keyword, draft = true)
            // 普通搜索先展示；疑似歌词的查询再补充匹配结果，取消和旧请求保护沿用同一代次。
            fun normalized(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val query = normalized(keyword)
            val results = _state.value.platforms.flatMap { it.results }
            val directMatch = results.any { song -> normalized(song.name) == query ||
                song.artist.orEmpty().split(Regex("[/、,，&;；]+" )).any { normalized(it).isNotEmpty() && normalized(it) in query } }
            if (query.length >= 4 && !directMatch) {
                val lyrics = searchService.searchLyrics(keyword)
                if (requestSeq != searchSeq || historyVersion != historyGeneration) return@launch
                if (lyrics.isNotEmpty()) _state.value = _state.value.copy(platforms = _state.value.platforms.map { platform ->
                    val matched = lyrics.filter { it.platform == platform.platform }.associateBy { it.songId }
                    platform.copy(results = platform.results.map { matched[it.songId] ?: it } +
                        matched.values.filter { new -> platform.results.none { it.songId == new.songId } })
                }, message = "已匹配歌词，优先展示相关原唱")
            }
        }
    }

    /** 加载更多（仅对当前筛选的平台，或全部平台） */
    fun loadMore(platform: String) {
        val keyword = _state.value.searchedKeyword
        if (keyword.isEmpty() || keyword != _state.value.keyword.trim()) return
        val current = _state.value.platforms.firstOrNull { it.platform == platform } ?: return
        if (current.loading || current.loadingMore || !current.hasMore) return
        val requestSeq = searchSeq

        viewModelScope.launch {
            updatePlatform(platform) { it.copy(loadingMore = true) }
            val nextPage = current.loadedPage + 1
            val outcome = runCatching {
                searchService.search(platform, keyword, page = nextPage, pageSize = DEFAULT_PAGE_SIZE)
            }
            // 刷新或切换关键词后，不让旧分页结果拼进新的首屏。
            if (requestSeq != searchSeq) return@launch
            applyOutcomes(
                listOf(
                    outcome.fold(
                        onSuccess = { PlatformSearchOutcome.Success(it) },
                        onFailure = { e ->
                            PlatformSearchOutcome.Failure(
                                platform = platform,
                                displayName = current.displayName,
                                message = e.message ?: "加载失败",
                                cause = e,
                            )
                        },
                    ),
                ),
                page = nextPage,
                append = true,
            )
        }
    }

    /**
     * 播放某平台结果中的第 [index] 首：该平台的当前结果集即播放队列。
     * 在线直链会过期，故每次播放都重新解析（由播放链路负责）。
     */
    fun playResult(platform: String, index: Int) {
        if (_state.value.keyword.trim() != _state.value.searchedKeyword) return
        val platformState = _state.value.platforms.firstOrNull { it.platform == platform } ?: return
        val target = platformState.results.getOrNull(index) ?: return
        rememberSearch(_state.value.searchedKeyword)
        val quality = currentQuality()?.key
        val songs: List<Song> = platformState.results.map { it.toSong(onlineSourceId, quality) }
        // 立即登记并切换所选歌曲；脚本加载和失败提示由播放解析链路处理。
        OnlineTrackSession.remember(songs)
        playback.play(target.toSong(onlineSourceId, quality).id, songs)
    }

    /** 合并结果按屏幕顺序入队；手动选择其他来源只替换当前歌曲。 */
    fun playGroupedResult(target: OnlineSearchResult) {
        if (_state.value.keyword.trim() != _state.value.searchedKeyword) return
        val groups = groupSearchResults(_state.value.platforms, _state.value.searchedKeyword, _state.value.originalFirst)
        val selected = groups.indexOfFirst { target in it }
        if (selected < 0) return
        rememberSearch(_state.value.searchedKeyword)
        val quality = currentQuality()?.key
        val songs = groups.mapIndexed { index, group ->
            (if (index == selected) target else group.first()).toSong(onlineSourceId, quality)
        }
        OnlineTrackSession.remember(songs)
        playback.play(songs[selected].id, songs)
    }

    fun playLibrarySong(songId: String, songs: List<Song>) {
        if (_state.value.keyword.trim() != _state.value.searchedKeyword) return
        rememberSearch(_state.value.searchedKeyword)
        playback.play(songId, songs)
    }

    fun toggleOriginalFirst() {
        _state.value = _state.value.copy(originalFirst = !_state.value.originalFirst)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ── 内部 ──

    private fun applyOutcomes(
        outcomes: List<PlatformSearchOutcome>,
        page: Int,
        append: Boolean = false,
    ) {
        var next = _state.value
        outcomes.forEach { outcome ->
            val existing = next.platforms.firstOrNull { it.platform == outcome.platform }
            val updated = when (outcome) {
                is PlatformSearchOutcome.Success -> {
                    val merged = if (append) {
                        // 去重追加：平台内可能出现重复条目
                        val seen = existing?.results?.map { it.songId }?.toSet().orEmpty()
                        existing?.results.orEmpty() + outcome.page.results.filter { it.songId !in seen }.map { it.copy(searchPage = page) }
                    } else {
                        outcome.page.results.map { it.copy(searchPage = page) }
                    }
                    (existing ?: PlatformSearchState(outcome.platform, outcome.platform)).copy(
                        results = merged,
                        loading = false,
                        loadingMore = false,
                        error = null,
                        hasMore = outcome.page.hasMore ?: (outcome.page.results.size >= DEFAULT_PAGE_SIZE),
                        loadedPage = page,
                    )
                }
                is PlatformSearchOutcome.Failure -> {
                    (existing ?: PlatformSearchState(outcome.platform, outcome.displayName)).copy(
                        loading = false,
                        loadingMore = false,
                        error = outcome.message,
                    )
                }
            }
            next = next.copy(
                platforms = next.platforms.map { if (it.platform == updated.platform) updated else it },
            )
        }
        _state.value = next
    }

    private fun updatePlatform(platform: String, transform: (PlatformSearchState) -> PlatformSearchState) {
        _state.value = _state.value.copy(
            platforms = _state.value.platforms.map { if (it.platform == platform) transform(it) else it },
        )
    }

    private companion object {
        const val AUTO_SEARCH_DELAY_MS = 350L
        /** 默认每页条数；咪咕会固定返回 20（其 provider 内部处理） */
        const val DEFAULT_PAGE_SIZE = 20

        fun platformLabel(platform: String): String = when (platform) {
            "kw" -> "酷我音乐"
            "kg" -> "酷狗音乐"
            "tx" -> "QQ音乐"
            "wy" -> "网易云音乐"
            "mg" -> "咪咕音乐"
            else -> platform
        }
    }
}
