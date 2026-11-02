package com.muses.player.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.ai.AI_API_KEY_SOURCE_ID
import com.muses.player.core.ai.AiException
import com.muses.player.core.ai.AiServiceUrlException
import com.muses.player.core.ai.AiRecommendConfig
import com.muses.player.core.ai.AiRecommendResult
import com.muses.player.core.ai.AiRecommendService
import com.muses.player.core.ai.RecommendPool
import com.muses.player.core.ai.RecommendPoolSnapshot
import com.muses.player.core.ai.LibraryProfileBuilder
import com.muses.player.core.ai.localRecommendDay
import com.muses.player.core.data.repository.CredentialsRepository
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineChartService
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.PlatformChartsOutcome
import com.muses.player.core.search.OnlinePlaylist
import com.muses.player.core.search.OnlinePlaylistService
import com.muses.player.core.search.OnlineSongVersionService
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.muses.player.core.download.DownloadQueueStore
import com.muses.player.core.model.download.DownloadStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** 排行榜区块状态 */
data class ChartSectionState(
    val platforms: List<String> = emptyList(),
    val platformNames: Map<String, String> = emptyMap(),
    val selectedPlatform: String? = null,
    /** 当前平台的榜单列表（切平台时从缓存取，不重复请求） */
    val charts: List<OnlineChart> = emptyList(),
    val loadingCharts: Boolean = false,
    val refreshingCharts: Boolean = false,
    val error: String? = null,
)

/** 「猜你喜欢」区块状态 */
data class RecommendSectionState(
    /** 地址/模型/Key 是否齐备（未配置时显示「去配置」） */
    val configured: Boolean = false,
    val loading: Boolean = false,
    val result: AiRecommendResult? = null,
    val error: String? = null,
) {
    /** 首次进入且尚未取过结果 */
    val idle: Boolean get() = result == null && !loading
}

/** 首页整体状态 */
data class HomeUiState(
    val featured: FeaturedPlaylistState = FeaturedPlaylistState(),
    val chart: ChartSectionState = ChartSectionState(),
    val recommend: RecommendSectionState = RecommendSectionState(),
    /** 一次性提示（如「该平台没有可用音源脚本」），展示后可清除 */
    val message: String? = null,
)

data class FeaturedPlaylistState(
    val items: List<OnlinePlaylist> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 首页 ViewModel：排行榜 + 猜你喜欢。
 *
 * 职责边界：
 * - 榜单与推荐都产出 [OnlineSearchResult]，经同一 `play` 链路入队（脚本校验 → 会话登记 → 播放端口），
 *   与在线搜索页行为一致；
 * - AI Key 现取现用，不在 VM 里长持（见 [readAiConfig]）。
 */
class HomeViewModel(
    private val chartService: OnlineChartService,
    private val recommendService: AiRecommendService,
    private val profileBuilder: LibraryProfileBuilder,
    private val settingsRepository: SettingsRepository,
    private val credentialsRepository: CredentialsRepository,
    private val scriptRepository: LxScriptRepository,
    private val playback: PlaybackPort,
    private val songRepository: SongRepository,
    private val chartCacheStore: OnlineChartCacheStore,
    private val playlistService: OnlinePlaylistService,
    private val playlistCacheStore: FeaturedPlaylistCacheStore,
    private val songVersionService: OnlineSongVersionService,
    /** 在线曲目归属的音源标识（与在线搜索页同值，仅作标识） */
    private val onlineSourceId: String = "online",
    private val downloadQueueStore: DownloadQueueStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val _chartCovers = MutableStateFlow<Map<String, String>>(emptyMap())
    val chartCovers: StateFlow<Map<String, String>> = _chartCovers.asStateFlow()
    private val coverAttempts = mutableMapOf<String, Long>()
    private val coverPermits = kotlinx.coroutines.sync.Semaphore(2)

    /** 可见榜单统一使用首首歌曲封面，按需读取，最多两个任务并发。 */
    fun ensureChartCover(chart: OnlineChart) {
        val key = "${chart.platform}:${chart.chartId}"
        val now = Clock.System.now().toEpochMilliseconds()
        if (key in _chartCovers.value || (coverAttempts[key]?.let { now - it in 0..300_000 } == true)) return
        coverAttempts[key] = now
        if (coverAttempts.size > 256) coverAttempts.remove(coverAttempts.keys.first())
        viewModelScope.launch {
            var acquired = false
            try {
                coverPermits.acquire(); acquired = true
                val url = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    loadChartSongCover(chart, chartCacheStore) {
                        chartService.chartSongs(chart.platform, chart.chartId, pageSize = 1).results
                    }
                }
                if (url != null) {
                    _chartCovers.value = _chartCovers.value + (key to url)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { coverAttempts.remove(key); throw e }
            catch (_: Exception) { }
            finally { if (acquired) coverPermits.release() }
        }
    }

    /** 首页只读取已有推荐预览，不触发生成、换曲或补齐请求。 */
    val recommendationPreview: StateFlow<List<OnlineSearchResult>> = settingsRepository.aiDailyRecommend
        .map { raw -> RecommendPoolSnapshot.decode(raw).result.tracks.map { it.result }.take(6) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 各平台榜单缓存：目录有独立更新时间，切平台无需重复请求。 */
    private val chartsByPlatform = mutableMapOf<String, List<OnlineChart>>()
    private val catalogUpdatedAt = mutableMapOf<String, Long>()
    private var recommendJob: Job? = null
    private val poolMutex = Mutex()
    private var pool = RecommendPool()
    private var poolLoaded = false
    private var pendingRefill = false

    /** 首选音质（与在线搜索页共用同一设置项） */
    private val preferredQuality: StateFlow<String> = settingsRepository.onlinePreferredQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, LxQuality.DEFAULT.key)

    init {
        viewModelScope.launch {
            songRepository.observeSongs().drop(1).collect {
                if (!poolLoaded) return@collect
                try {
                    val profile = profileBuilder.build()
                    val removed = poolMutex.withLock {
                        val updated = pool.merge(RecommendPool().result, profile)
                        val changed = updated.result.tracks.size < pool.result.tracks.size
                        if (updated != pool) savePool(updated)
                        changed
                    }
                    if (removed && recommendJob?.isActive == true) pendingRefill = true
                    refreshRecommend(forceRefresh = removed)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _state.value = _state.value.copy(message = "更新推荐列表失败，请重试") }
            }
        }
        viewModelScope.launch {
            downloadQueueStore?.tasks?.map { tasks -> tasks.filter { it.status == DownloadStatus.COMPLETED } }
                ?.distinctUntilChanged()?.collect {
                if (!poolLoaded) return@collect
                try {
                    val removed = applyCompletedDownloads()
                    if (removed && recommendJob?.isActive == true) pendingRefill = true
                    refreshRecommend(forceRefresh = removed)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _state.value = _state.value.copy(message = "保存下载喜好失败，请重试") }
            }
        }
    }

    private suspend fun savePool(updated: RecommendPool) {
        settingsRepository.setAiDailyRecommend(RecommendPoolSnapshot.encode(updated))
        pool = updated
        _state.value = _state.value.copy(recommend = _state.value.recommend.copy(result = pool.result))
    }

    private suspend fun applyCompletedDownloads(): Boolean {
        val completed = downloadQueueStore?.load().orEmpty().filter { it.status == DownloadStatus.COMPLETED }
        return poolMutex.withLock {
            var updated = pool
            completed.forEach { task ->
                if (updated.feedback.none { it.liked && it.name == task.track.title && it.artist == task.track.artist }) {
                    updated = updated.respond(task.track.title, task.track.artist, liked = true)
                }
            }
            val removed = updated.result.tracks.size < pool.result.tracks.size
            if (updated != pool) savePool(updated)
            removed
        }
    }

    /** 旧缓存缺少封面时补元数据，按当前池合并，避免放回已处理歌曲。 */
    private suspend fun enrichRecommendCovers() {
        val tracks = poolMutex.withLock { pool.result.tracks }
        if (tracks.none { it.result.platform == "wy" && it.result.coverUrl.isNullOrBlank() }) return
        val songs = try {
            songVersionService.enrich(tracks.map { it.result })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return }
        val byId = songs.associateBy { it.platform to it.songId }
        poolMutex.withLock {
            val updated = pool.copy(result = pool.result.copy(tracks = pool.result.tracks.map { track ->
                byId[track.result.platform to track.result.songId]?.let { track.copy(result = it) } ?: track
            }))
            if (updated != pool) savePool(updated)
        }
    }

    fun dismissRecommend(track: com.muses.player.core.ai.AiRecommendedTrack) {
        viewModelScope.launch {
            try {
                poolMutex.withLock { savePool(pool.respond(track.result.name, track.result.artist, liked = false)) }
                if (recommendJob?.isActive == true) pendingRefill = true
                refreshRecommend(forceRefresh = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(message = "保存无感反馈失败，请重试") }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun loadRecommendedPlaylists(forceRefresh: Boolean = false) {
        if (_state.value.featured.loading) return
        _state.value = _state.value.copy(featured = _state.value.featured.copy(loading = true, error = null))
        viewModelScope.launch {
            try {
                val cached = try {
                    playlistCacheStore.load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (cached != null) {
                    _state.value = _state.value.copy(featured = _state.value.featured.copy(items = cached.items))
                    if (!forceRefresh && cached.isFresh()) return@launch
                }
                val items = playlistService.recommended()
                check(items.isNotEmpty()) { "暂时没有可用歌单" }
                _state.value = _state.value.copy(featured = FeaturedPlaylistState(items))
                try {
                    playlistCacheStore.save(items)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 缓存写入失败不影响本次已加载的歌单。
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(featured = _state.value.featured.copy(error = e.message ?: "歌单加载失败，请重试"))
            } finally {
                _state.value = _state.value.copy(featured = _state.value.featured.copy(loading = false))
            }
        }
    }

    // ── 排行榜 ──

    /** 拉取各平台榜单列表；榜单歌曲由对应的详情页按需加载。 */
    fun loadCharts(forceRefresh: Boolean = false) {
        if (_state.value.chart.loadingCharts || _state.value.chart.refreshingCharts) return
        _state.value = _state.value.copy(
            chart = _state.value.chart.copy(
                loadingCharts = _state.value.chart.charts.isEmpty(),
                refreshingCharts = _state.value.chart.charts.isNotEmpty(),
                error = null,
            ),
        )
        viewModelScope.launch {
            val cached = runCatching { chartCacheStore.loadCatalogs() }.getOrDefault(emptyMap())
            cached.forEach { (platform, catalog) ->
                chartsByPlatform[platform] = catalog.charts
                catalogUpdatedAt[platform] = catalog.updatedAt
            }

            val availablePlatforms = chartService.platforms.filter { it == "wy" }
            if (availablePlatforms.isEmpty()) {
                _state.value = _state.value.copy(
                    chart = ChartSectionState(
                        platformNames = chartService.platformNames,
                        error = "网易云榜单暂时不可用，请稍后重试",
                    ),
                )
                return@launch
            }
            val cachedPlatforms = availablePlatforms.filter { it in chartsByPlatform }
            if (cachedPlatforms.isNotEmpty()) {
                val selected = _state.value.chart.selectedPlatform?.takeIf { it in cachedPlatforms } ?: cachedPlatforms.first()
                _state.value = _state.value.copy(
                    chart = ChartSectionState(
                        platforms = cachedPlatforms,
                        platformNames = chartService.platformNames,
                        selectedPlatform = selected,
                        charts = chartsByPlatform[selected].orEmpty(),
                        refreshingCharts = forceRefresh,
                    ),
                )
            } else {
                _state.value = _state.value.copy(chart = _state.value.chart.copy(loadingCharts = true, error = null))
            }
            val now = Clock.System.now().toEpochMilliseconds()
            val needsRefresh = availablePlatforms.filter { platform ->
                forceRefresh || !chartCacheStore.isCatalogFresh(catalogUpdatedAt[platform] ?: 0L, now)
            }
            if (needsRefresh.isEmpty()) {
                val platform = _state.value.chart.selectedPlatform?.takeIf { it in availablePlatforms } ?: availablePlatforms.first()
                _state.value = _state.value.copy(
                    chart = _state.value.chart.copy(
                        platforms = availablePlatforms.filter { it in chartsByPlatform },
                        platformNames = chartService.platformNames,
                        selectedPlatform = platform,
                        charts = chartsByPlatform[platform].orEmpty(),
                        loadingCharts = false,
                        refreshingCharts = false,
                        error = null,
                    ),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                chart = _state.value.chart.copy(
                    loadingCharts = _state.value.chart.charts.isEmpty(),
                    refreshingCharts = true,
                ),
            )
            val outcomes = runCatching { chartService.charts(needsRefresh) }.getOrDefault(emptyList())
            val success = outcomes.filterIsInstance<PlatformChartsOutcome.Success>()
            if (success.isEmpty() && cachedPlatforms.isEmpty()) {
                val failure = outcomes.filterIsInstance<PlatformChartsOutcome.Failure>().firstOrNull()
                _state.value = _state.value.copy(
                    chart = _state.value.chart.copy(
                        loadingCharts = false,
                        refreshingCharts = false,
                        error = failure?.message ?: "排行榜加载失败，请检查网络",
                    ),
                )
                return@launch
            }
            success.forEach {
                chartsByPlatform[it.platform] = it.charts
                catalogUpdatedAt[it.platform] = now
            }
            if (success.isNotEmpty()) runCatching {
                chartCacheStore.saveCatalogs(
                    chartsByPlatform.mapValues { (platform, charts) ->
                        CachedChartCatalog(charts, catalogUpdatedAt[platform] ?: now)
                    },
                )
            }

            // 保持用户已选平台（刷新场景），否则取第一个可用平台
            val platforms = availablePlatforms.filter { it in chartsByPlatform }
            val platform = _state.value.chart.selectedPlatform?.takeIf { it in platforms } ?: platforms.first()
            val charts = chartsByPlatform[platform].orEmpty()
            _state.value = _state.value.copy(
                chart = _state.value.chart.copy(
                    platforms = platforms,
                    platformNames = chartService.platformNames,
                    selectedPlatform = platform,
                    charts = charts,
                    loadingCharts = false,
                    refreshingCharts = false,
                    error = null,
                ),
            )
        }
    }

    fun selectPlatform(platform: String) {
        if (platform !in _state.value.chart.platforms) return
        if (_state.value.chart.selectedPlatform == platform) return
        val charts = chartsByPlatform[platform].orEmpty()
        _state.value = _state.value.copy(
            chart = _state.value.chart.copy(
                selectedPlatform = platform,
                charts = charts,
                error = null,
            ),
        )
    }

    // ── 猜你喜欢 ──

    /** 保留推荐池，只补足空位；重试也不会替换尚未处理的歌曲。 */
    fun refreshRecommend(forceRefresh: Boolean = false) {
        if (recommendJob?.isActive == true) return
        if (poolLoaded && pool.result.tracks.size >= AiRecommendService.DEFAULT_COUNT) return
        if (!forceRefresh && _state.value.recommend.error != null) return
        recommendJob = viewModelScope.launch {
            _state.value = _state.value.copy(recommend = _state.value.recommend.copy(loading = true, error = null))
            try {
                withTimeout(180_000) {
                    if (!poolLoaded) poolMutex.withLock {
                        pool = RecommendPoolSnapshot.decode(settingsRepository.aiDailyRecommend.first())
                        poolLoaded = true
                        _state.value = _state.value.copy(recommend = _state.value.recommend.copy(result = pool.result, configured = true))
                    }
                    applyCompletedDownloads()
                    val base = profileBuilder.build()
                    poolMutex.withLock { savePool(pool.merge(RecommendPool().result, base)) }
                    enrichRecommendCovers()
                    if (pool.result.tracks.size >= AiRecommendService.DEFAULT_COUNT) return@withTimeout
                    val config = readAiConfig()
                    if (!config.isUsable) {
                        _state.value = _state.value.copy(recommend = _state.value.recommend.copy(configured = false))
                        return@withTimeout
                    }
                    _state.value = _state.value.copy(recommend = _state.value.recommend.copy(configured = true))
                    if (base.isEmpty) throw AiException("曲库还没有歌曲：先扫描本地/WebDAV 音源后再试")
                    // 有限轮次补齐，防止重复候选或接口故障导致无限请求。
                    repeat(3) {
                        val missing = AiRecommendService.DEFAULT_COUNT - pool.result.tracks.size
                        if (missing <= 0) return@withTimeout
                        val profile = poolMutex.withLock { pool.profile(profileBuilder.build()) }
                        val batch = recommendService.recommend(profile, config, missing)
                        val latest = profileBuilder.build()
                        poolMutex.withLock { savePool(pool.merge(batch, latest)) }
                    }
                    if (pool.result.tracks.size < AiRecommendService.DEFAULT_COUNT && !pendingRefill) {
                        throw AiException("新歌曲暂时不足，已保留现有推荐，可重试补齐")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _state.value = _state.value.copy(recommend = _state.value.recommend.copy(error = "补充推荐超时，已保留现有歌曲，请重试"))
            } catch (e: CancellationException) { throw e }
            catch (e: AiServiceUrlException) {
                _state.value = _state.value.copy(recommend = _state.value.recommend.copy(configured = false, error = aiErrorMessage(e)))
            }
            catch (e: Exception) {
                _state.value = _state.value.copy(recommend = _state.value.recommend.copy(error = aiErrorMessage(e)))
            } finally {
                _state.value = _state.value.copy(recommend = _state.value.recommend.copy(loading = false))
                recommendJob = null
                val refill = pendingRefill
                pendingRefill = false
                if (refill && _state.value.recommend.error == null) refreshRecommend()
            }
        }
    }

    fun retryRecommend() = refreshRecommend(forceRefresh = true)

    fun playRecommend(index: Int) {
        val tracks = _state.value.recommend.result?.tracks.orEmpty()
        if (index !in tracks.indices) return
        val results = tracks.map { it.result }
        playResults(results, index)
    }

    /** 随机播放当前推荐列表，与歌曲详情页工具条行为一致。 */
    fun shuffleRecommend() {
        val results = _state.value.recommend.result?.tracks.orEmpty().map { it.result }
        if (results.isEmpty()) return
        val index = results.indices.random()
        playResults(results, index, shuffle = true)
    }

    // ── 内部 ──

    /** 立即更新待播歌曲；脚本校验及解析错误统一交给播放链路。 */
    private fun playResults(results: List<OnlineSearchResult>, index: Int, shuffle: Boolean = false) {
        val target = results.getOrNull(index) ?: return
        val quality = LxQuality.fromKey(preferredQuality.value)?.key
        val songs: List<Song> = results.map { it.toSong(onlineSourceId, quality) }
        OnlineTrackSession.remember(songs)
        playback.play(target.toSong(onlineSourceId, quality).id, songs)
        playback.setShuffleEnabled(shuffle)
    }

    /** 现取现用 AI 配置：Key 从加密凭据库读，不常驻 VM 字段 */
    private suspend fun readAiConfig(): AiRecommendConfig {
        val baseUrl = runCatching { settingsRepository.aiBaseUrl.first() }.getOrDefault("")
        val model = runCatching { settingsRepository.aiModel.first() }.getOrDefault("")
        val apiKey = runCatching { credentialsRepository.getPassword(AI_API_KEY_SOURCE_ID) }
            .getOrNull()
            .orEmpty()
        return AiRecommendConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
        )
    }

    private fun aiErrorMessage(e: Throwable): String = when (e) {
        is AiException -> e.message ?: "AI 推荐失败"
        else -> "AI 推荐失败：${e.message ?: "未知错误"}"
    }

}
