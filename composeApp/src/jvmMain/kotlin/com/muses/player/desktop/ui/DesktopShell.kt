package com.muses.player.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.repository.FavoriteSongsStore
import com.muses.player.core.download.DownloadQueueStore
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.data.repository.LyricSupplementMode
import com.muses.player.core.search.OnlineSearchService
import com.muses.player.core.search.PlatformSearchOutcome
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SearchHistoryStore
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.download.DownloadStatus
import com.muses.player.core.model.download.DownloadTask
import com.muses.player.core.lxsdk.musicQualitySelections
import com.muses.player.core.lxsdk.qualitySelectionKey
import com.muses.player.core.lxsdk.qualitySelectionLabel
import com.muses.player.core.lxsdk.LxSourcePreference
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineChartService
import com.muses.player.core.search.OnlinePlaylist
import com.muses.player.core.search.provider.ImportedPlaylist
import com.muses.player.core.search.provider.PlaylistAddress
import com.muses.player.core.search.provider.PlaylistImportService
import com.muses.player.core.search.provider.parsePlaylistAddress
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.components.MusesCoverRadius
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.desktop.DesktopRuntime
import com.muses.player.desktop.library.DesktopLocalLibraryScanner
import com.muses.player.desktop.update.DesktopUpdater
import com.muses.player.desktop.update.DesktopUpdateInfo
import com.muses.player.feature.home.HomeViewModel
import com.muses.player.feature.player.PlayerViewModel
import com.muses.player.download.DownloadManager
import com.muses.player.feature.sources.LxImportValidation
import com.muses.player.feature.sources.LxScriptItem
import com.muses.player.feature.sources.LxScriptsViewModel
import com.muses.player.feature.sources.LxUrlImportStatus
import com.muses.player.feature.sources.rememberLxScriptFilePicker
import com.muses.player.feature.sources.rememberLxScriptUrlImporter
import com.muses.player.feature.sources.rememberLocalFolderPicker
import com.muses.player.navigation.PlaylistMetadataManager
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class DesktopSection { DISCOVER, PLAYLISTS, LOCAL, ONLINE, FAVORITES, DOWNLOADED, SEARCH, SOURCES, SETTINGS }

private enum class DesktopQualityPicker { PLAY, DOWNLOAD }

private data class DesktopSongActions(
    val isLiked: (Song) -> Boolean = { false },
    val onToggleLike: (Song) -> Unit = {},
    val onAddToQueue: (Song) -> Unit = {},
    val onDownload: (Song) -> Unit = {},
)

private val LocalDesktopSongActions = staticCompositionLocalOf { DesktopSongActions() }

@Composable
fun DesktopShell(onExit: () -> Unit = {}) {
    val scheme = MiuixTheme.colorScheme
    val playlistStore = koinInject<UserPlaylistStore>()
    val songRepository = koinInject<SongRepository>()
    val favoritesStore = koinInject<FavoriteSongsStore>()
    val downloadStore = koinInject<DownloadQueueStore>()
    val settingsRepository = koinInject<SettingsRepository>()
    val searchHistoryStore = koinInject<SearchHistoryStore>()
    val onlineSearch = koinInject<OnlineSearchService>()
    val onlineCharts = koinInject<OnlineChartService>()
    val downloadManager = koinInject<DownloadManager>()
    val homeViewModel: HomeViewModel = koinViewModel()
    val playerViewModel: PlayerViewModel = koinViewModel()
    val playlists by playlistStore.playlists.collectAsState(initial = emptyList())
    val localSongs by songRepository.observeSongs().collectAsState(initial = emptyList())
    val favoriteState by favoritesStore.state.collectAsState()
    val downloaded by downloadStore.downloadedSongs.collectAsState(initial = emptyList())
    val recentSearches by searchHistoryStore.entries.collectAsState(initial = emptyList())
    val downloadTasks by downloadManager.tasks.collectAsState(initial = emptyList())
    val homeState by homeViewModel.state.collectAsState()
    val lyricsDocument by playerViewModel.lyricsDocument.collectAsState()
    val lyricsLoading by playerViewModel.lyricsLoading.collectAsState()
    val stickyCover by playerViewModel.stickyCover.collectAsState()

    var section by remember { mutableStateOf(DesktopSection.DISCOVER) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showImportPlaylist by remember { mutableStateOf(false) }
    var onlineTitle by remember { mutableStateOf<String?>(null) }
    var onlineSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var searchSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var playerPanel by remember { mutableStateOf(DesktopPlayerPanel.NONE) }
    var localScanning by remember { mutableStateOf(false) }
    var localScanProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var updateOpen by remember { mutableStateOf(false) }
    var updateLoading by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadQualitySong by remember { mutableStateOf<Song?>(null) }
    val chooseDownloadQuality by settingsRepository.chooseDownloadQuality.collectAsState(true)
    val downloadPreferredQuality by settingsRepository.downloadPreferredQuality.collectAsState("320k")
    fun importLocalFolder(path: String) {
        if (path.isBlank() || localScanning) return
        localScanning = true
        localScanProgress = 0f
        scope.launch {
            runCatching {
                val songs = DesktopLocalLibraryScanner.scan(File(path)) { done, total ->
                    if (total > 0) localScanProgress = done.toFloat() / total
                }
                songRepository.upsertAll(songs)
                songs.size
            }.onSuccess { count ->
                toast = if (count > 0) "已导入 $count 首本地音乐" else "这个文件夹里没有找到音频文件"
            }.onFailure { error ->
                toast = error.message ?: "本地音乐扫描失败，请稍后重试"
            }
            localScanning = false
            localScanProgress = 0f
        }
    }
    val pickLocalFolder = rememberLocalFolderPicker { path -> importLocalFolder(path) }
    fun checkUpdates() {
        updateOpen = true
        updateLoading = true
        updateChecked = false
        updateInfo = null
        updateError = null
        scope.launch {
            DesktopUpdater.check(DesktopRuntime.appVersion()).fold(
                onSuccess = { info ->
                    updateInfo = info
                    updateChecked = true
                    updateLoading = false
                },
                onFailure = {
                    updateError = it.message ?: "检查更新失败，请稍后重试"
                    updateChecked = true
                    updateLoading = false
                },
            )
        }
    }
    val searchHttp = koinInject<SearchHttp>()
    val playlistImport = remember(searchHttp) { PlaylistImportService(searchHttp) }

    LaunchedEffect(Unit) {
        homeViewModel.loadRecommendedPlaylists()
        homeViewModel.loadCharts()
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
    val selectedTracks = selectedPlaylist?.tracks?.map { it.toSong() }.orEmpty()
    val favoriteSongs = favoriteState.likedSongs
    val downloadedSongs = downloaded.map { it.toSong() }
    val knownSongs = remember(playlists, localSongs, favoriteSongs, downloadedSongs, onlineSongs, searchSongs) {
        (playlists.flatMap { playlist -> playlist.tracks.map { it.toSong() } } + localSongs + favoriteSongs + downloadedSongs + onlineSongs + searchSongs)
            .distinctBy { it.id }
    }
    val visibleSongs = when {
        section == DesktopSection.PLAYLISTS && selectedPlaylist != null -> selectedTracks.filterByQuery(query)
        section == DesktopSection.LOCAL -> localSongs.filterByQuery(query)
        section == DesktopSection.ONLINE -> onlineSongs.filterByQuery(query)
        section == DesktopSection.FAVORITES -> favoriteSongs.filterByQuery(query)
        section == DesktopSection.DOWNLOADED -> downloadedSongs.filterByQuery(query)
        section == DesktopSection.SEARCH -> searchSongs.filterByQuery(query)
        else -> emptyList()
    }

    fun openPlaylist(id: String) {
        selectedPlaylistId = id
        section = DesktopSection.PLAYLISTS
        query = ""
    }
    fun goDiscover() {
        section = DesktopSection.DISCOVER
        selectedPlaylistId = null
        query = ""
    }
    fun toastMessage(message: String) { toast = message }
    fun toggleFavorite(song: Song) {
        val next = !favoriteState.isLiked(song)
        scope.launch {
            runCatching { favoritesStore.setLiked(song, next) }
                .onSuccess { toastMessage(if (next) "已加入我喜欢" else "已取消喜欢") }
                .onFailure { toastMessage(it.message ?: "收藏失败，请稍后重试") }
        }
    }
    fun addToQueue(song: Song) {
        DesktopRuntime.playerHook().addToQueue(listOf(song))
        toastMessage("已添加到播放队列")
    }
    fun downloadSong(song: Song) {
        if (song.sourceType != SourceType.ONLINE) {
            toastMessage("本地歌曲不需要下载")
            return
        }
        if (chooseDownloadQuality) {
            downloadQualitySong = song
        } else {
            downloadManager.enqueue(song, startImmediately = true)
            toastMessage("已加入下载队列")
        }
    }
    val songActions = DesktopSongActions(
        isLiked = { song -> favoriteState.isLiked(song) },
        onToggleLike = ::toggleFavorite,
        onAddToQueue = ::addToQueue,
        onDownload = ::downloadSong,
    )
    fun openOnline(item: OnlinePlaylist) {
        if (onlineLoading) return
        onlineLoading = true
        toastMessage("正在读取歌单…")
        scope.launch {
            runCatching { playlistImport.load(PlaylistAddress(item.platform, item.id)) }
                .onSuccess { imported ->
                    onlineTitle = imported.name
                    onlineSongs = imported.songs.map { it.toSong("online") }
                    section = DesktopSection.ONLINE
                    query = ""
                }
                .onFailure { toastMessage(it.message ?: "歌单读取失败，请稍后重试") }
            onlineLoading = false
        }
    }
    fun runSearch(keyword: String) {
        val value = keyword.trim()
        if (value.isBlank()) return
        query = value
        section = DesktopSection.SEARCH
        searchLoading = true
        searchError = null
        searchSongs = emptyList()
        scope.launch { searchHistoryStore.record(value) }
        scope.launch {
            val outcomes = runCatching { onlineSearch.searchAll(value) }.getOrElse { error ->
                searchError = error.message ?: "在线搜索失败，请稍后重试"
                searchLoading = false
                return@launch
            }
            val success = outcomes.filterIsInstance<PlatformSearchOutcome.Success>()
            val songs = success.flatMap { it.page.results }
                .distinctBy { it.platform to it.songId }
                .map { it.toSong("online") }
            searchSongs = songs
            searchError = if (songs.isEmpty()) {
                outcomes.filterIsInstance<PlatformSearchOutcome.Failure>().firstOrNull()?.message
                    ?: "没有找到相关歌曲"
            } else null
            searchLoading = false
        }
    }
    fun openChart(chart: OnlineChart) {
        if (onlineLoading) return
        onlineLoading = true
        onlineTitle = chart.name
        toastMessage("正在读取榜单…")
        scope.launch {
            runCatching { onlineCharts.allChartSongs(chart.platform, chart.chartId) }
                .onSuccess { songs ->
                    onlineSongs = songs.map { it.toSong("online") }
                    section = DesktopSection.ONLINE
                    query = ""
                }
                .onFailure { toastMessage(it.message ?: "榜单读取失败，请稍后重试") }
            onlineLoading = false
        }
    }

    CompositionLocalProvider(LocalDesktopSongActions provides songActions) {
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Row(Modifier.fillMaxSize()) {
            DesktopSidebar(
                section = section,
                playlists = playlists,
                selectedPlaylistId = selectedPlaylistId,
                localCount = localSongs.size,
                favoriteCount = favoriteSongs.size,
                downloadedCount = downloadedSongs.size,
                onDiscover = ::goDiscover,
                onFavorites = { section = DesktopSection.FAVORITES; selectedPlaylistId = null; query = "" },
                onDownloaded = { section = DesktopSection.DOWNLOADED; selectedPlaylistId = null; query = "" },
                onPlaylists = { section = DesktopSection.PLAYLISTS; selectedPlaylistId = null; query = "" },
                onLocal = { section = DesktopSection.LOCAL; selectedPlaylistId = null },
                onSources = { section = DesktopSection.SOURCES; selectedPlaylistId = null; query = "" },
                onSettings = { section = DesktopSection.SETTINGS; selectedPlaylistId = null; query = "" },
                onPlaylist = ::openPlaylist,
                onCreatePlaylist = { showCreatePlaylist = true },
                onCheckUpdate = ::checkUpdates,
                version = DesktopRuntime.appVersion(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.onBackground.copy(alpha = 0.07f)))
            Column(Modifier.weight(1f)) {
                DesktopTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    canGoBack = section != DesktopSection.DISCOVER || selectedPlaylistId != null,
                    onBack = ::goDiscover,
                    playlistCount = playlists.size,
                    onSubmit = { if (query.isNotBlank()) runSearch(query) },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                    Crossfade(
                        targetState = section to selectedPlaylistId,
                        modifier = Modifier.fillMaxSize(),
                    ) { (targetSection, targetPlaylist) ->
                        when (targetSection) {
                            DesktopSection.DISCOVER -> DesktopDiscover(
                                playlists = playlists,
                                localSongs = localSongs,
                                featured = homeState.featured.items,
                                charts = homeState.chart.charts,
                                onOpenPlaylist = ::openPlaylist,
                                onCreatePlaylist = { showCreatePlaylist = true },
                                onImportPlaylist = { showImportPlaylist = true },
                                onOnline = ::openOnline,
                                onChart = ::openChart,
                            )
                            DesktopSection.PLAYLISTS -> {
                                val playlist = playlists.firstOrNull { it.id == targetPlaylist }
                                if (playlist == null) {
                                    DesktopPlaylistGrid(
                                        playlists = playlists,
                                        onOpen = ::openPlaylist,
                                        onCreate = { showCreatePlaylist = true },
                                        onImport = { showImportPlaylist = true },
                                    )
                                } else {
                                    DesktopPlaylistDetail(
                                        playlistName = playlist.name,
                                        tracks = playlist.tracks.map { it.toSong() },
                                        query = query,
                                        onPlay = { song -> playSong(song, visibleSongs) },
                                        onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                                        onBack = { section = DesktopSection.PLAYLISTS; selectedPlaylistId = null },
                                    )
                                }
                            }
                            DesktopSection.ONLINE -> DesktopOnlineDetail(
                                title = onlineTitle ?: "在线歌单",
                                tracks = visibleSongs,
                                loading = onlineLoading,
                                onPlay = { song -> playSong(song, visibleSongs) },
                                onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                                onBack = { section = DesktopSection.DISCOVER },
                            )
                            DesktopSection.LOCAL -> DesktopSongPage(
                                title = "本地音乐",
                                subtitle = "${localSongs.size} 首 · 来自已添加的音乐文件夹",
                                songs = visibleSongs,
                                emptyText = if (query.isBlank()) "还没有本地音乐，点击右上角添加文件夹" else "没有找到匹配歌曲",
                                onPlay = { song -> playSong(song, visibleSongs) },
                                onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                                onImportFolder = pickLocalFolder,
                                importLabel = if (localScanning) "扫描中 ${(localScanProgress * 100).toInt()}%" else "添加文件夹",
                            )
                            DesktopSection.FAVORITES -> DesktopSongPage(
                                title = "我喜欢",
                                subtitle = "${favoriteSongs.size} 首 · 收藏的歌曲",
                                songs = visibleSongs,
                                emptyText = if (query.isBlank()) "还没有收藏歌曲，点击歌曲右侧的心形即可收藏" else "没有找到匹配歌曲",
                                onPlay = { song -> playSong(song, visibleSongs) },
                                onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                            )
                            DesktopSection.DOWNLOADED -> DesktopSongPage(
                                title = "已下载",
                                subtitle = "${downloadedSongs.size} 首 · 已保存到本机",
                                songs = visibleSongs,
                                emptyText = if (query.isBlank()) "还没有已下载的歌曲" else "没有找到匹配歌曲",
                                onPlay = { song -> playSong(song, visibleSongs) },
                                onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                                downloadTasks = downloadTasks,
                                onRemoveDownload = { id -> downloadManager.remove(id) },
                            )
                            DesktopSection.SEARCH -> DesktopSearchPage(
                                keyword = query,
                                songs = visibleSongs,
                                loading = searchLoading,
                                error = searchError,
                                recentSearches = recentSearches.filterNot { it.equals(query, true) },
                                onRecent = ::runSearch,
                                onClearHistory = { scope.launch { searchHistoryStore.clear() } },
                                onPlay = { song -> playSong(song, visibleSongs) },
                            )
                            DesktopSection.SOURCES -> DesktopSourcesPage(
                                onOpenLocal = { section = DesktopSection.LOCAL; selectedPlaylistId = null },
                                onToast = ::toastMessage,
                            )
                            DesktopSection.SETTINGS -> DesktopSettingsPage(
                                version = DesktopRuntime.appVersion(),
                                onCheckUpdate = ::checkUpdates,
                                onToast = ::toastMessage,
                            )
                        }
                    }
                    }
                }
                DesktopPlayerBar(
                    knownSongs = knownSongs,
                    coverOverride = stickyCover,
                    isLiked = { song -> favoriteState.isLiked(song) },
                    onToggleLike = ::toggleFavorite,
                    panel = playerPanel,
                    onPanelChange = { next -> playerPanel = if (playerPanel == next) DesktopPlayerPanel.NONE else next },
                )
            }
        }
        if (playerPanel != DesktopPlayerPanel.NONE) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 96.dp),
            ) {
                DesktopSidePanelView(
                    panel = playerPanel,
                    knownSongs = knownSongs,
                    lyricsDocument = lyricsDocument,
                    lyricsLoading = lyricsLoading,
                    onRetryLyrics = { DesktopRuntime.playerHook().searchOnlineLyrics() },
                    onClose = { playerPanel = DesktopPlayerPanel.NONE },
                )
            }
        }
        toast?.let {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 106.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.onBackground.copy(alpha = 0.88f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(it, color = scheme.background, fontSize = 12.sp)
            }
        }
    }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name ->
                scope.launch {
                    runCatching { playlistStore.create(name) }
                        .onSuccess { id -> showCreatePlaylist = false; openPlaylist(id) }
                        .onFailure { toastMessage(it.message ?: "创建歌单失败") }
                }
            },
        )
    }

    if (downloadQualitySong != null) {
        DesktopQualityDialog(
            title = "下载音质",
            selectedKey = downloadPreferredQuality,
            onDismiss = { downloadQualitySong = null },
            onSelect = { option ->
                val song = downloadQualitySong
                downloadQualitySong = null
                if (song != null) {
                    downloadManager.enqueue(song, startImmediately = true, selectedQuality = option.key)
                    toastMessage("已按 ${option.label} 加入下载队列")
                }
            },
        )
    }

    if (showImportPlaylist) {
        ImportPlaylistDialog(
            onDismiss = { showImportPlaylist = false },
            onImported = { id ->
                showImportPlaylist = false
                openPlaylist(id)
            },
        )
    }

    if (updateOpen) {
        DesktopUpdateDialog(
            currentVersion = DesktopRuntime.appVersion(),
            loading = updateLoading,
            checked = updateChecked,
            info = updateInfo,
            error = updateError,
            downloading = downloading,
            progress = downloadProgress,
            onDismiss = { if (!downloading) updateOpen = false },
            onRetry = ::checkUpdates,
            onInstall = {
                updateInfo?.let { info ->
                    scope.launch {
                        downloading = true
                        downloadProgress = 0f
                        updateError = null
                        DesktopUpdater.download(info) { downloadProgress = it }.fold(
                            onSuccess = { file ->
                                DesktopUpdater.install(file).fold(
                                    onSuccess = { onExit() },
                                    onFailure = {
                                        updateError = it.message ?: "无法启动安装程序"
                                        downloading = false
                                    },
                                )
                            },
                            onFailure = {
                                updateError = it.message ?: "下载更新失败"
                                downloading = false
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun DesktopSidePanelView(
    panel: DesktopPlayerPanel,
    knownSongs: List<Song>,
    lyricsDocument: LyricsDocument?,
    lyricsLoading: Boolean,
    onRetryLyrics: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val settings = koinInject<SettingsRepository>()
    val supplementMode by settings.lyricSupplementMode.collectAsState(LyricSupplementMode.Translation)
    val scope = rememberCoroutineScope()
    val hook = remember { DesktopRuntime.playerHook() }
    val queueIds by hook.queueSongIds.collectAsState()
    val currentId by hook.currentSongId.collectAsState()
    val position by hook.positionMs.collectAsState()
    val playing by hook.isPlaying.collectAsState()
    val lyricsSearch by hook.lyricsSearch.collectAsState()
    val songsById = remember(knownSongs) { knownSongs.associateBy { it.id } }
    val currentSong = currentId?.let { songsById[it] }
    Column(Modifier.width(340.dp).fillMaxHeight().background(scheme.surface).drawBehind {
        drawLine(
            color = scheme.onBackground.copy(alpha = 0.08f),
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            strokeWidth = 1f,
        )
    }) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (panel == DesktopPlayerPanel.QUEUE) "播放队列" else "歌词",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (panel == DesktopPlayerPanel.QUEUE && queueIds.isNotEmpty()) {
                Text("${queueIds.size} 首", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(end = 6.dp))
            }
            if (panel == DesktopPlayerPanel.LYRICS) {
                Text(
                    "译",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (supplementMode == LyricSupplementMode.Translation) scheme.primary else scheme.onBackgroundVariant,
                    modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch { settings.setLyricTranslationEnabled(supplementMode != LyricSupplementMode.Translation) }
                    }.padding(horizontal = 7.dp, vertical = 4.dp),
                )
                Text(
                    "音",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (supplementMode == LyricSupplementMode.Romanization) scheme.primary else scheme.onBackgroundVariant,
                    modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch { settings.setLyricRomanizationEnabled(supplementMode != LyricSupplementMode.Romanization) }
                    }.padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
            DesktopIconButton(TablerIcons.Close, "关闭面板", 28.dp, onClick = onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        when (panel) {
            DesktopPlayerPanel.QUEUE -> {
                if (queueIds.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("播放队列是空的", fontSize = 13.sp, color = scheme.onBackgroundVariant)
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f)) {
                            itemsIndexed(queueIds, key = { index, id -> "$index:$id" }) { index, id ->
                                val song = songsById[id]
                                val active = id == currentId
                                val interaction = remember { MutableInteractionSource() }
                                val hovered by interaction.collectIsHoveredAsState()
                                Row(
                                    Modifier.fillMaxWidth().height(64.dp)
                                        .background(if (active) scheme.primary.copy(alpha = 0.08f) else if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
                                        .hoverable(interaction)
                                        .clickable(interactionSource = interaction, indication = null) {
                                            if (index in queueIds.indices) hook.playAtIndex(index)
                                        }
                                        .padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                        if (active) {
                                            Icon(
                                                if (playing) TablerIcons.PauseFill else TablerIcons.PlayFill,
                                                null,
                                                tint = scheme.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        } else {
                                            Text("${index + 1}", fontSize = 11.sp, color = scheme.onBackgroundVariant)
                                        }
                                    }
                                    MusesCover(song?.coverUri, size = 40.dp, radius = MusesCoverRadius.SM)
                                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                        Text(
                                            song?.title ?: "未知歌曲",
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                            color = if (active) scheme.primary else scheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            song?.artist ?: "—",
                                            fontSize = 10.sp,
                                            color = scheme.onBackgroundVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    DesktopIconButton(TablerIcons.Close, "从队列移除", 24.dp) {
                                        if (index in queueIds.indices) hook.removeQueueItemAt(index)
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
                        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SecondaryButton("清空队列") { hook.clearQueueItems() }
                        }
                    }
                }
            }
            DesktopPlayerPanel.LYRICS -> {
                val lines = lyricsDocument?.lines.orEmpty()
                val activeIndex = if (lines.isEmpty()) -1 else lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
                val listState = rememberLazyListState()
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) listState.scrollToItem(activeIndex)
                }
                if (currentSong == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择一首歌开始播放", fontSize = 13.sp, color = scheme.onBackgroundVariant)
                    }
                } else if (lines.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        Text(currentSong.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(currentSong.artist ?: "未知歌手", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(22.dp))
                        if (lyricsLoading) {
                            Text("正在加载歌词…", fontSize = 11.sp, color = scheme.onBackgroundVariant)
                        } else when (val state = lyricsSearch) {
                            is com.muses.player.desktop.playback.DesktopLyricsSearchState.Searching ->
                                Text("正在搜索歌词…", fontSize = 11.sp, color = scheme.onBackgroundVariant)
                            is com.muses.player.desktop.playback.DesktopLyricsSearchState.Done ->
                                Text("已找到歌词，正在载入", fontSize = 11.sp, color = scheme.onBackgroundVariant)
                            is com.muses.player.desktop.playback.DesktopLyricsSearchState.Failed -> {
                                Text(state.message, fontSize = 11.sp, color = scheme.onBackgroundVariant)
                                Spacer(Modifier.height(10.dp))
                                SecondaryButton("再试一次", onRetryLyrics)
                            }
                            else -> SecondaryButton("搜索在线歌词", onRetryLyrics)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        itemsIndexed(lines) { index, line ->
                            val active = index == activeIndex
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(
                                    line.text.ifBlank { "·" },
                                    fontSize = if (active) 15.sp else 12.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) scheme.primary else scheme.onBackground.copy(alpha = 0.55f),
                                    lineHeight = if (active) 22.sp else 18.sp,
                                )
                                if (supplementMode == LyricSupplementMode.Translation) {
                                    line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                        Text(translation, fontSize = 11.sp, color = scheme.onBackgroundVariant.copy(alpha = 0.80f), modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                                if (supplementMode == LyricSupplementMode.Romanization) {
                                    line.romanization?.takeIf { it.isNotBlank() }?.let { romanization ->
                                        Text(romanization, fontSize = 11.sp, color = scheme.onBackgroundVariant.copy(alpha = 0.80f), modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun DesktopSettingsPage(
    version: String,
    onCheckUpdate: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val settings = koinInject<SettingsRepository>()
    val scope = rememberCoroutineScope()
    val desktopLyrics by settings.desktopLyricsEnabled.collectAsState(false)
    val lyricsTranslation by settings.lyricTranslationEnabled.collectAsState(true)
    val lyricsRomanization by settings.lyricRomanizationEnabled.collectAsState(true)
    val autoScrape by settings.autoScrapeEnabled.collectAsState(false)
    val playQuality by settings.onlinePreferredQuality.collectAsState("320k")
    val downloadQuality by settings.downloadPreferredQuality.collectAsState("320k")
    val chooseDownloadQuality by settings.chooseDownloadQuality.collectAsState(true)
    val downloadDirectory by settings.downloadDeviceDirectory.collectAsState("")
    val desktopLyricsLocked by settings.desktopLyricsLocked.collectAsState(false)
    val lyricsFontSize by settings.desktopLyricsFontSize.collectAsState(22L)
    var qualityPicker by remember { mutableStateOf<DesktopQualityPicker?>(null) }
    var showLyricsFontSize by remember { mutableStateOf(false) }
    val pickDownloadDirectory = rememberLocalFolderPicker { path ->
        scope.launch { settings.setDownloadDeviceDirectory(path) }
        onToast("下载目录已更新")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 34.dp, vertical = 10.dp),
    ) {
        Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
        Text("播放、歌词与下载的默认行为", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))

        DesktopSettingSection("播放与下载") {
            DesktopSettingActionRow("在线播放音质", "首选 ${qualitySelectionLabel(playQuality)}，不可用时自动回退", "选择") {
                qualityPicker = DesktopQualityPicker.PLAY
            }
            DesktopSettingActionRow("下载音质", "保存文件的首选档位 ${qualitySelectionLabel(downloadQuality)}", "选择") {
                qualityPicker = DesktopQualityPicker.DOWNLOAD
            }
            DesktopSettingToggleRow("下载前选择音质", "单曲下载时弹出音质选项", chooseDownloadQuality) {
                scope.launch { settings.setChooseDownloadQuality(it) }
            }
            DesktopSettingActionRow("下载目录", downloadDirectory.ifBlank { "系统下载目录 / Muses" }, "选择") {
                pickDownloadDirectory()
            }
            DesktopSettingToggleRow("播放时自动刮削", "为缺少标签或封面的歌曲补充元数据", autoScrape) {
                scope.launch { settings.setAutoScrapeEnabled(it) }
            }
        }

        DesktopSettingSection("歌词") {
            DesktopSettingToggleRow("桌面悬浮歌词", "播放时在桌面显示独立歌词窗口", desktopLyrics) {
                scope.launch { settings.setDesktopLyricsEnabled(it) }
            }
            DesktopSettingToggleRow("锁定悬浮歌词", "锁定后窗口不阻塞鼠标操作，可在设置中解除", desktopLyricsLocked) {
                scope.launch { settings.setDesktopLyricsLocked(it) }
            }
            DesktopSettingActionRow("悬浮歌词字号", "当前 ${lyricsFontSize}px", "选择") {
                showLyricsFontSize = true
            }
            DesktopSettingToggleRow("显示歌词翻译", "在歌词页与悬浮歌词中显示译文", lyricsTranslation) {
                scope.launch { settings.setLyricTranslationEnabled(it) }
            }
            DesktopSettingToggleRow("显示歌词音译", "显示罗马音或拼音", lyricsRomanization) {
                scope.launch { settings.setLyricRomanizationEnabled(it) }
            }
        }

        DesktopSettingSection("关于") {
            DesktopSettingActionRow("当前版本", "Muses 桌面版 $version", "检查更新") { onCheckUpdate() }
            DesktopSettingRowText("数据与更新", "安装包与更新清单来自 up.raink.cloud", "国内通道")
        }
        Spacer(Modifier.height(24.dp))
    }
    qualityPicker?.let { target ->
        DesktopQualityDialog(
            title = if (target == DesktopQualityPicker.PLAY) "在线播放音质" else "下载音质",
            selectedKey = if (target == DesktopQualityPicker.PLAY) playQuality else downloadQuality,
            onDismiss = { qualityPicker = null },
            onSelect = { option ->
                scope.launch {
                    if (target == DesktopQualityPicker.PLAY) settings.setOnlinePreferredQuality(option.key)
                    else settings.setDownloadPreferredQuality(option.key)
                }
                qualityPicker = null
                onToast("已设为 ${option.label}")
            },
        )
    }
    if (showLyricsFontSize) {
        Dialog(onDismissRequest = { showLyricsFontSize = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(320.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(20.dp)) {
                Text("悬浮歌词字号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Spacer(Modifier.height(8.dp))
                listOf(16L, 20L, 22L, 26L, 30L, 34L).forEach { size ->
                    val selected = size == lyricsFontSize
                    Row(
                        Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(role = Role.Button) {
                                scope.launch { settings.setDesktopLyricsFontSize(size) }
                                showLyricsFontSize = false
                                onToast("悬浮歌词字号已设为 ${size}px")
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${size}px", fontSize = 12.sp, color = if (selected) scheme.primary else scheme.onBackground, modifier = Modifier.weight(1f))
                        if (selected) Icon(TablerIcons.Check, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopQualityDialog(
    title: String,
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelect: (com.muses.player.core.lxsdk.QualitySelection) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(340.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(20.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Text("直接选择一个档位，播放时按该档位优先并自动回退。", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            musicQualitySelections.forEach { option ->
                val selected = option.key == qualitySelectionKey(selectedKey)
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(role = Role.Button) { onSelect(option) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.label, fontSize = 12.sp, color = if (selected) scheme.primary else scheme.onBackground, modifier = Modifier.weight(1f))
                    if (selected) Icon(TablerIcons.Check, "当前音质", tint = scheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingSection(title: String, content: @Composable () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainer)
            .padding(horizontal = 18.dp),
    ) { content() }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun DesktopSettingActionRow(
    title: String,
    summary: String,
    action: String,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(72.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground)
            Text(summary, fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            action,
            fontSize = 12.sp,
            color = if (hovered) scheme.primary else scheme.onBackgroundVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.06f)))
}

@Composable
private fun DesktopSettingToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground)
            Text(summary, fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DesktopToggle(checked, onChange)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.06f)))
}

@Composable
private fun DesktopSettingRowText(title: String, summary: String, value: String) {
    val scheme = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground)
            Text(summary, fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(horizontal = 8.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.06f)))
}

@Composable
private fun DesktopToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.width(40.dp).height(24.dp).clip(CircleShape)
            .background(if (checked) scheme.primary else scheme.onBackground.copy(alpha = 0.18f))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun DesktopSourcesPage(
    onOpenLocal: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val viewModel: LxScriptsViewModel = koinViewModel()
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val builtinEnabled by viewModel.builtinEnabled.collectAsState(true)
    val preferred by viewModel.preferredSource.collectAsState("")
    val order by viewModel.sourceOrder.collectAsState(emptyList())
    val validation by viewModel.importValidation.collectAsState()
    val urlStatus by viewModel.urlImportStatus.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showPriority by remember { mutableStateOf(false) }
    var scriptUrl by remember { mutableStateOf("") }
    var urlBusy by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    val orderedItems = items.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    val preferredName = when (preferred) {
        "" -> "自动选择"
        LxSourcePreference.BUILTIN -> "内置音源"
        else -> items.firstOrNull { it.id == preferred }?.name ?: "自动回退"
    }
    val filePicker = rememberLxScriptFilePicker { result ->
        result.fold(
            onSuccess = { source -> viewModel.stageImport(source); showImport = true },
            onFailure = { onToast(it.message ?: "读取脚本失败，请确认文件可读取") },
        )
    }
    val importUrl = rememberLxScriptUrlImporter { result ->
        urlBusy = false
        result.fold(
            onSuccess = { source -> viewModel.importFromUrlContent(source, scriptUrl.trim()) },
            onFailure = { urlError = it.message ?: "下载脚本失败" },
        )
    }
    LaunchedEffect(urlStatus) {
        when (val status = urlStatus) {
            LxUrlImportStatus.Success -> {
                showUrlImport = false
                viewModel.resetUrlImportStatus()
            }
            is LxUrlImportStatus.Failure -> {
                urlBusy = false
                urlError = status.message
                viewModel.resetUrlImportStatus()
            }
            else -> Unit
        }
    }
    LaunchedEffect(notice) {
        notice?.let { onToast(it); viewModel.clearNotice() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 34.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(74.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("音源", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                Text("导入洛雪脚本、管理在线音源优先级与本地音乐", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
            }
            SecondaryButton("从链接导入") {
                scriptUrl = ""
                urlError = null
                viewModel.resetUrlImportStatus()
                showUrlImport = true
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("导入脚本", filePicker)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(7.dp))
                .clickable(role = Role.Button) { showPriority = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("优先音源：$preferredName", fontSize = 12.sp, color = scheme.primary, modifier = Modifier.weight(1f))
            Text("调整", fontSize = 11.sp, color = scheme.primary)
        }

        DesktopSettingSection("在线音源") {
            DesktopSettingToggleRow(
                title = "内置音源",
                summary = "内置洛雪音源参与在线搜索与播放；关闭后只使用已导入脚本",
                checked = builtinEnabled,
                onChange = viewModel::setBuiltinEnabled,
            )
            if (loading && orderedItems.isEmpty()) {
                Text("正在读取音源脚本…", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(vertical = 18.dp))
            } else if (orderedItems.isEmpty()) {
                Text("还没有导入自定义音源。点击右上角「导入脚本」选择 .js / .txt 文件。", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                orderedItems.forEachIndexed { index, item ->
                    DesktopSourceRow(
                        item = item,
                        priority = if (item.id == preferred) "优先使用" else "设为优先",
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedItems.lastIndex,
                        onToggle = { enabled -> viewModel.setEnabled(item.id, enabled) },
                        onChoose = { viewModel.choosePreferred(item.id) },
                        onMoveUp = { viewModel.moveSource(item.id, -1) },
                        onMoveDown = { viewModel.moveSource(item.id, 1) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
        }

        DesktopSettingSection("本地音乐") {
            DesktopSettingActionRow("管理本地音乐", "添加或移除本机音乐文件夹，桌面版直接读取文件标签", "打开") { onOpenLocal() }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showImport) {
        Dialog(onDismissRequest = { if (!importBusy) { showImport = false; viewModel.clearStagedImport() } }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(24.dp)) {
                Text("导入音源脚本", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Text("先校验脚本能否初始化，再写入并启用。支持洛雪 .js / .txt 脚本。", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
                when (val current = validation) {
                    LxImportValidation.Idle -> Text("等待校验…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                    LxImportValidation.Validating -> Text("正在校验脚本…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                    is LxImportValidation.Valid -> {
                        Text(current.scriptName?.takeIf { it.isNotBlank() } ?: "未命名脚本", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground)
                        Text("可提供：${current.platforms.joinToString(" / ").ifBlank { "未知" }}", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    is LxImportValidation.Invalid -> Text(current.message, fontSize = 11.sp, color = scheme.error)
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    SecondaryButton("取消") { if (!importBusy) { showImport = false; viewModel.clearStagedImport() } }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.height(34.dp).clip(RoundedCornerShape(7.dp)).background(if (validation is LxImportValidation.Valid) scheme.primary else scheme.onBackground.copy(alpha = 0.12f)).clickable(enabled = validation is LxImportValidation.Valid && !importBusy, role = Role.Button) {
                        importBusy = true
                        viewModel.confirmImport(onComplete = { result ->
                            importBusy = false
                            result.fold(
                                onSuccess = { showImport = false; onToast("音源脚本已导入并设为优先") },
                                onFailure = { onToast(it.message ?: "导入失败") },
                            )
                        })
                    }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(if (importBusy) "导入中…" else "导入并启用", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimary)
                    }
                }
            }
        }
    }

    if (showUrlImport) {
        Dialog(onDismissRequest = { if (!urlBusy) showUrlImport = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(24.dp)) {
                Text("从链接导入", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Text("粘贴脚本的 HTTP/HTTPS 地址，Muses 会先下载并校验。", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp))
                Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(38.dp).clip(RoundedCornerShape(7.dp)).background(scheme.surfaceContainerHigh).padding(horizontal = 11.dp), contentAlignment = Alignment.CenterStart) {
                    if (scriptUrl.isEmpty()) Text("https://example.com/source.js", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                    BasicTextField(
                        value = scriptUrl,
                        onValueChange = { scriptUrl = it; urlError = null },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = scheme.onBackground),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                urlError?.let { Text(it, fontSize = 11.sp, color = scheme.error, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    SecondaryButton("取消") { if (!urlBusy) showUrlImport = false }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(if (urlBusy) "下载中…" else "导入") {
                        if (!urlBusy && scriptUrl.isNotBlank()) {
                            urlBusy = true
                            urlError = null
                            importUrl(scriptUrl.trim())
                        }
                    }
                }
            }
        }
    }

    if (showPriority) {
        Dialog(onDismissRequest = { showPriority = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(380.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(20.dp)) {
                Text("优先音源", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Text("优先失败后按备用顺序尝试。", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                val options = buildList {
                    add("" to "自动选择")
                    if (builtinEnabled) add(LxSourcePreference.BUILTIN to "内置音源")
                    orderedItems.forEach { add(it.id to it.name) }
                }
                options.forEach { (key, label) ->
                    val selected = preferred == key
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(role = Role.Button) { viewModel.choosePreferred(key); showPriority = false }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 12.sp, color = if (selected) scheme.primary else scheme.onBackground, modifier = Modifier.weight(1f))
                        if (selected) Icon(TablerIcons.Check, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSourceRow(
    item: LxScriptItem,
    priority: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onChoose: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.author?.takeIf { it.isNotBlank() }, item.version?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "洛雪音源脚本" },
                    fontSize = 11.sp,
                    color = scheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            DesktopToggle(item.enabled, onToggle)
        }
        if (item.platforms.isNotEmpty()) {
            Text("平台：${item.platforms.joinToString(" / ")}", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp))
        }
        item.loadError?.let { Text(it, fontSize = 11.sp, color = scheme.error, modifier = Modifier.padding(top = 4.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                priority,
                fontSize = 11.sp,
                color = if (priority == "优先使用") scheme.primary else scheme.onBackgroundVariant,
                modifier = Modifier.clickable(role = Role.Button, onClick = onChoose),
            )
            Spacer(Modifier.weight(1f))
            DesktopIconButton(TablerIcons.ChevronUp, "上移", 24.dp, enabled = canMoveUp, onClick = onMoveUp)
            DesktopIconButton(TablerIcons.ChevronDown, "下移", 24.dp, enabled = canMoveDown, onClick = onMoveDown)
            DesktopIconButton(TablerIcons.Delete, "删除音源", 24.dp, onClick = onDelete)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.06f)))
    }
}

private fun List<Song>.filterByQuery(query: String): List<Song> {
    val value = query.trim()
    if (value.isBlank()) return this
    return filter {
        it.title.contains(value, true) ||
            it.artist.orEmpty().contains(value, true) ||
            it.album.orEmpty().contains(value, true)
    }
}

private fun playSong(song: Song, queue: List<Song>) {
    if (queue.isEmpty()) return
    com.muses.player.desktop.DesktopRuntime.playerHook().play(song.id, queue)
}

@Composable
private fun DesktopSidebar(
    section: DesktopSection,
    playlists: List<com.muses.player.core.data.repository.UserPlaylist>,
    selectedPlaylistId: String?,
    localCount: Int,
    favoriteCount: Int,
    downloadedCount: Int,
    onDiscover: () -> Unit,
    onFavorites: () -> Unit,
    onDownloaded: () -> Unit,
    onPlaylists: () -> Unit,
    onLocal: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onCheckUpdate: () -> Unit,
    version: String,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        Modifier.width(240.dp).fillMaxHeight().background(scheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text("音乐库", fontSize = 13.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp))
        DesktopNavItem("发现音乐", TablerIcons.Compass, section == DesktopSection.DISCOVER, onDiscover)
        DesktopNavItem("我喜欢", TablerIcons.Heart, section == DesktopSection.FAVORITES, onFavorites, trail = favoriteCount.takeIf { it > 0 }?.toString())
        DesktopNavItem("已下载", TablerIcons.Download, section == DesktopSection.DOWNLOADED, onDownloaded, trail = downloadedCount.takeIf { it > 0 }?.toString())
        DesktopNavItem("我的歌单", TablerIcons.QueueMusic, section == DesktopSection.PLAYLISTS && selectedPlaylistId == null, onPlaylists)
        DesktopNavItem("本地音乐", TablerIcons.Folder, section == DesktopSection.LOCAL, onLocal, trail = localCount.takeIf { it > 0 }?.toString())
        DesktopNavItem("音源", TablerIcons.Radio, section == DesktopSection.SOURCES, onSources)

        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 22.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的歌单", fontSize = 13.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1f))
            DesktopIconButton(TablerIcons.Add, "新建歌单", 26.dp, onClick = onCreatePlaylist)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                val active = selectedPlaylistId == playlist.id
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().height(36.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) scheme.primary.copy(alpha = 0.11f) else if (hovered) scheme.onBackground.copy(alpha = 0.05f) else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onPlaylist(playlist.id) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        playlist.name,
                        fontSize = 13.sp,
                        color = if (active) scheme.primary else scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(playlist.tracks.size.toString(), fontSize = 11.sp, color = scheme.onBackgroundVariant)
                }
            }
        }
        DesktopNavItem("设置", TablerIcons.Settings, section == DesktopSection.SETTINGS, onSettings)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Muses $version", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(start = 2.dp), maxLines = 1
        )
            Spacer(Modifier.weight(1f))
            Text("检查更新", fontSize = 10.sp, color = scheme.primary,
                modifier = Modifier.clickable(role = Role.Button, onClick = onCheckUpdate).padding(horizontal = 4.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun DesktopNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    trail: String? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) scheme.primary.copy(alpha = 0.11f) else if (hovered) scheme.onBackground.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) scheme.primary else scheme.onBackgroundVariant, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) scheme.primary else scheme.onBackground, modifier = Modifier.weight(1f).padding(start = 11.dp))
        trail?.let { Text(it, fontSize = 10.sp, color = scheme.onBackgroundVariant) }
    }
}

@Composable
private fun DesktopTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    playlistCount: Int,
    onSubmit: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(scheme.background)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopIconButton(TablerIcons.ArrowBack, "返回", 36.dp, enabled = canGoBack, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        DesktopIconButton(TablerIcons.ArrowForward, "前进", 36.dp, enabled = false, onClick = {})
        Spacer(Modifier.width(24.dp))
        Row(
            Modifier.widthIn(min = 300.dp, max = 500.dp).weight(1f).height(40.dp)
                .clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TablerIcons.Search, null, tint = scheme.onBackgroundVariant, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (query.isEmpty()) Text("搜索歌曲、歌手、专辑", fontSize = 13.sp, color = scheme.onBackgroundVariant)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onQueryChange(""); true }
                            Key.Enter -> { onSubmit(); true }
                            else -> false
                        }
                    },
                )
            }
            if (query.isNotEmpty()) DesktopIconButton(TablerIcons.Close, "清空搜索", 24.dp, onClick = { onQueryChange("") })
        }
        Spacer(Modifier.weight(1f))
        Text("$playlistCount 个歌单", fontSize = 12.sp, color = scheme.onBackgroundVariant)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
            Text("M", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onPrimary)
        }
    }
}

@Composable
private fun DesktopDiscover(
    playlists: List<com.muses.player.core.data.repository.UserPlaylist>,
    localSongs: List<Song>,
    featured: List<OnlinePlaylist>,
    charts: List<OnlineChart>,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onOnline: (OnlinePlaylist) -> Unit,
    onChart: (OnlineChart) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val scrollState = rememberScrollState()
    val hour = LocalTime.now().hour
    val greeting = when {
        hour in 5..11 -> "早上好"
        hour in 12..17 -> "下午好"
        else -> "晚上好"
    }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日"))
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = 34.dp, end = 48.dp, top = 10.dp, bottom = 36.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(greeting, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                    Text("为你准备今天的声音 · $date", fontSize = 13.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp))
                }
                Text("${localSongs.size} 首本地音乐", fontSize = 12.sp, color = scheme.onBackgroundVariant)
            }

            val hero = playlists.firstOrNull()
            val onlineHero = if (hero == null) featured.firstOrNull() else null
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 30.dp).clip(RoundedCornerShape(16.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(scheme.primary.copy(alpha = 0.10f), scheme.surfaceContainer),
                        ),
                    )
                    .padding(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusesCover(
                    uri = hero?.tracks?.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri ?: onlineHero?.coverUrl,
                    size = 144.dp,
                    radius = MusesCoverRadius.MD,
                )
                Column(Modifier.padding(start = 22.dp).weight(1f)) {
                    Text(if (onlineHero != null) "今日推荐" else "继续聆听", fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Medium)
                    Text(hero?.name ?: onlineHero?.title ?: "从第一张歌单开始", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = scheme.onBackground, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            hero != null -> "${hero.tracks.size} 首 · 保存在本机"
                            onlineHero != null -> "${onlineHero.trackCount ?: 0} 首 · 来自网易云音乐"
                            else -> "建立你的桌面音乐库"
                        },
                        fontSize = 13.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp),
                    )
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {                        PrimaryButton(
                            when {
                                onlineHero != null -> "打开推荐"
                                hero != null -> "打开歌单"
                                else -> "新建歌单"
                            },
                        ) {
                            when {
                                onlineHero != null -> onOnline(onlineHero)
                                hero != null -> onOpenPlaylist(hero.id)
                                else -> onCreatePlaylist()
                            }
                        }
                        if (onlineHero == null) {
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton("导入歌单", onImportPlaylist)
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                SectionHeader("我的歌单", "${playlists.size} 个")
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    playlists.chunked(5).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            row.forEach { playlist ->
                                PlaylistCard(playlist, onOpenPlaylist, Modifier.weight(1f))
                            }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (featured.isNotEmpty()) {
                Spacer(Modifier.height(34.dp))
                SectionHeader("在线推荐", "来自网易云")
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    featured.take(10).chunked(5).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            row.forEach { item -> OnlineCard(item, { onOnline(item) }, Modifier.weight(1f)) }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (charts.isNotEmpty()) {
                Spacer(Modifier.height(34.dp))
                SectionHeader("排行榜", "网易云")
                Column(Modifier.fillMaxWidth()) {
                    charts.take(4).forEachIndexed { index, chart ->
                        ChartRow(index, chart, { onChart(chart) })
                        if (index < charts.take(4).lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.06f)).padding(start = 54.dp))
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = androidx.compose.foundation.rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun SquareCover(uri: String?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        MusesCover(uri, size = maxWidth, radius = MusesCoverRadius.MD)
    }
}

@Composable
private fun SectionHeader(title: String, trail: String? = null) {
    val scheme = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground, modifier = Modifier.weight(1f))
        trail?.let { Text(it, fontSize = 12.sp, color = scheme.onBackgroundVariant) }
    }
}

@Composable
private fun PlaylistCard(
    playlist: com.muses.player.core.data.repository.UserPlaylist,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onOpen(playlist.id) }
            .padding(10.dp),
    ) {
        SquareCover(playlist.tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri)
        Text(playlist.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text("${playlist.tracks.size} 首", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun OnlineCard(item: OnlinePlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp),
    ) {
        SquareCover(item.coverUrl)
        Text(item.title, fontSize = 13.sp, color = scheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp))
        item.trackCount?.let { Text("$it 首", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun ChartRow(index: Int, chart: OnlineChart, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(7.dp))
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontSize = 12.sp, color = if (index < 3) scheme.primary else scheme.onBackgroundVariant, modifier = Modifier.width(24.dp))
        MusesCover(chart.coverUrl, size = 44.dp, radius = MusesCoverRadius.SM)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(chart.name, fontSize = 14.sp, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            chart.updateInfo?.let { Text(it, fontSize = 11.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(TablerIcons.Play, null, tint = scheme.onBackgroundVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DesktopPlaylistGrid(
    playlists: List<com.muses.player.core.data.repository.UserPlaylist>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的歌单", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground, modifier = Modifier.weight(1f))
            SecondaryButton("导入歌单", onImport)
            Spacer(Modifier.width(8.dp))
            PrimaryButton("新建歌单", onCreate)
        }
        if (playlists.isEmpty()) {
            EmptyPrompt("还没有自建歌单，点击右上角新建，或从网易云 / QQ 音乐导入。")
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(playlists.chunked(5)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { PlaylistCard(it, onOpen, Modifier.weight(1f)) }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPrompt(message: String, action: String? = null, onClick: (() -> Unit)? = null) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(message, fontSize = 13.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1f))
        if (action != null && onClick != null) SecondaryButton(action, onClick)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(scheme.primary)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimary) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, color = scheme.onBackground) }
}

@Composable
private fun DesktopIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(size).clip(RoundedCornerShape(7.dp))
            .background(if (hovered && enabled) scheme.onBackground.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) scheme.onBackgroundVariant else scheme.onBackgroundVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(size * 0.54f))
    }
}

@Composable
private fun DesktopPlaylistDetail(
    playlistName: String,
    tracks: List<Song>,
    query: String,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val filtered = tracks.filterByQuery(query)
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(
                uri = tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri,
                size = 120.dp,
                radius = MusesCoverRadius.MD,
            )
            Column(Modifier.padding(start = 22.dp).weight(1f)) {
                Text(playlistName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tracks.size} 首 · 保存在本机", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("播放全部", onPlayAll)
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("返回歌单", onBack)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        TrackTable(
            songs = filtered,
            emptyText = if (query.isBlank()) "这个歌单还没有歌曲" else "没有找到匹配歌曲",
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DesktopOnlineDetail(
    title: String,
    tracks: List<Song>,
    loading: Boolean,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(
                uri = tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri,
                size = 120.dp,
                radius = MusesCoverRadius.MD,
            )
            Column(Modifier.padding(start = 22.dp).weight(1f)) {
                Text(
                    if (loading && tracks.isEmpty()) "正在读取在线歌单…" else title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${tracks.size} 首 · 来自网易云音乐", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("播放全部", onPlayAll)
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("返回发现", onBack)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        if (loading && tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在读取歌单，请稍候…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
            }
        } else {
            TrackTable(
                songs = tracks,
                emptyText = "这个在线歌单暂时没有可用歌曲",
                onPlay = onPlay,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DesktopSongPage(
    title: String,
    subtitle: String,
    songs: List<Song>,
    emptyText: String,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onImportFolder: (() -> Unit)? = null,
    importLabel: String = "添加文件夹",
    downloadTasks: List<DownloadTask> = emptyList(),
    onRemoveDownload: (String) -> Unit = {},
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(74.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                Text(subtitle, fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
            }
            if (songs.isNotEmpty()) PrimaryButton("播放全部", onPlayAll)
            if (onImportFolder != null) {
                if (songs.isNotEmpty()) Spacer(Modifier.width(8.dp))
                SecondaryButton(importLabel, onImportFolder)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        val pendingDownloads = downloadTasks.filter { it.status != DownloadStatus.COMPLETED }
        if (pendingDownloads.isNotEmpty()) {
            DesktopDownloadTaskList(pendingDownloads, onRemoveDownload)
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        }
        TrackTable(
            songs = songs,
            emptyText = emptyText,
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DesktopDownloadTaskList(tasks: List<DownloadTask>, onRemove: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("下载任务", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1f))
            Text("${tasks.size} 个", fontSize = 10.sp, color = scheme.onBackgroundVariant)
        }
        tasks.take(4).forEach { task ->
            val total = task.totalBytes ?: 0L
            val progress = if (total > 0) (task.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
            Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                MusesCover(task.track.coverUri, size = 28.dp, radius = MusesCoverRadius.SM)
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.track.title, fontSize = 11.sp, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            downloadStatusLabel(task),
                            fontSize = 9.sp,
                            color = if (task.status == DownloadStatus.FAILED) scheme.error else scheme.onBackgroundVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(scheme.onBackground.copy(alpha = 0.10f))) {
                        if (progress > 0f) Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
                    }
                    task.error?.let { message ->
                        Text(message, fontSize = 9.sp, color = scheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                DesktopIconButton(TablerIcons.Close, "取消下载", 24.dp) { onRemove(task.id) }
            }
        }
        if (tasks.size > 4) Text("还有 ${tasks.size - 4} 个下载任务", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(start = 37.dp))
    }
}

private fun downloadStatusLabel(task: DownloadTask): String = when (task.status) {
    DownloadStatus.WAITING -> "等待中"
    DownloadStatus.PREPARING -> "准备中"
    DownloadStatus.DOWNLOADING -> {
        val total = task.totalBytes ?: 0L
        if (total > 0) "${(task.downloadedBytes * 100 / total).coerceIn(0, 100)}%" else "下载中"
    }
    DownloadStatus.METADATA -> "整理标签"
    DownloadStatus.SAVING -> "保存中"
    DownloadStatus.UPLOADING -> "上传中"
    DownloadStatus.PENDING_UPLOAD -> "等待上传"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.COMPLETED -> "已完成"
}

@Composable
private fun DesktopSearchPage(
    keyword: String,
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    recentSearches: List<String>,
    onRecent: (String) -> Unit,
    onClearHistory: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth().height(74.dp), verticalArrangement = Arrangement.Center) {
            Text("搜索结果", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
            Text("“$keyword” · ${songs.size} 个结果", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (recentSearches.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近搜索", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(end = 8.dp))
                recentSearches.take(6).forEach { word ->
                    Box(
                        Modifier.padding(end = 6.dp).widthIn(max = 150.dp).clip(RoundedCornerShape(6.dp))
                            .background(scheme.surfaceContainerHigh)
                            .clickable(role = Role.Button) { onRecent(word) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) { Text(word, fontSize = 11.sp, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.weight(1f))
                Text("清除", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.clickable(role = Role.Button, onClick = onClearHistory))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在搜索各平台…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, fontSize = 12.sp, color = scheme.onBackgroundVariant)
            }
            else -> TrackTable(
                songs = songs,
                emptyText = "没有找到相关歌曲",
                onPlay = onPlay,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackTable(
    songs: List<Song>,
    emptyText: String,
    onPlay: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(38.dp))
            Text("标题", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(2.6f))
            Text("歌手", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1.3f))
            Text("专辑", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1.7f))
            Text("音质", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(68.dp))
            Text("时长", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(58.dp))
            Spacer(Modifier.width(34.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, fontSize = 13.sp, color = scheme.onBackgroundVariant)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        TrackRow(index, song, onPlay)
                    }
                }
                VerticalScrollbar(
                    adapter = androidx.compose.foundation.rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, song: Song, onPlay: (Song) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val actions = LocalDesktopSongActions.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPlay(song) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(38.dp))
        Row(Modifier.weight(2.6f), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(song.coverUri, size = 40.dp, radius = MusesCoverRadius.SM)
            Column(Modifier.padding(start = 11.dp)) {
                Text(song.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(song.artist ?: "—", fontSize = 12.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
        Text(song.album ?: "—", fontSize = 12.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.7f))
        Text(song.audioQuality ?: "—", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(68.dp))
        Text(formatDuration(song.durationMs), fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(58.dp))
        Box {
            DesktopIconButton(TablerIcons.MoreHorizontal, "更多操作", 28.dp) { menuOpen = true }
            if (menuOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier.width(190.dp).clip(RoundedCornerShape(9.dp))
                            .background(scheme.surface).padding(6.dp),
                    ) {
                        SongMenuAction("添加到播放队列") {
                            menuOpen = false
                            actions.onAddToQueue(song)
                        }
                        SongMenuAction(if (actions.isLiked(song)) "取消喜欢" else "收藏到我喜欢") {
                            menuOpen = false
                            actions.onToggleLike(song)
                        }
                        if (song.sourceType == SourceType.ONLINE) {
                            SongMenuAction("下载到本机") {
                                menuOpen = false
                                actions.onDownload(song)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongMenuAction(text: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp))
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, fontSize = 12.sp, color = scheme.onBackground)
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun DesktopUpdateDialog(
    currentVersion: String,
    loading: Boolean,
    checked: Boolean,
    info: DesktopUpdateInfo?,
    error: String?,
    downloading: Boolean,
    progress: Float,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(24.dp),
        ) {
            Text("检查更新", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Text("当前版本 $currentVersion", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            when {
                loading -> Text("正在检查更新…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                error != null -> {
                    Text(error, fontSize = 12.sp, color = scheme.error)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("重试", onRetry)
                }
                checked && info == null -> Text("已经是最新版本。", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                info != null -> {
                    Text("发现新版本 ${info.version}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, fontSize = 11.sp, color = scheme.onBackgroundVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    if (info.size > 0) {
                        Text("安装包 ${info.size / (1024 * 1024)} MB", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (downloading) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.onBackground.copy(alpha = 0.1f))) {
                            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
                        }
                        Text("正在下载 ${(progress * 100).toInt()}%", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton(if (downloading) "请稍候" else "关闭", { if (!downloading) onDismiss() })
                if (info != null && !downloading) {
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("下载并安装", onInstall)
                }
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(380.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(24.dp),
        ) {
            Text("新建歌单", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Text("给歌单起一个名字，之后可以随时修改。", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp))
            Box(
                Modifier.fillMaxWidth().padding(top = 18.dp).height(38.dp).clip(RoundedCornerShape(7.dp))
                    .background(scheme.surfaceContainerHigh).padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) Text("例如：深夜循环", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                SecondaryButton("取消", onDismiss)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(7.dp)).background(scheme.primary)
                        .clickable(enabled = name.isNotBlank(), role = Role.Button) { onCreate(name.trim()) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("创建", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimary) }
            }
        }
    }
}

@Composable
private fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val http = koinInject<SearchHttp>()
    val service = remember(http) { PlaylistImportService(http) }
    val store = koinInject<UserPlaylistStore>()
    val metadata = koinInject<PlaylistMetadataManager>()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf<ImportedPlaylist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(24.dp)) {
            Text(if (loaded == null) "导入歌单" else "确认导入", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            Text(
                if (loaded == null) "粘贴网易云音乐或 QQ 音乐公开歌单的完整分享链接。"
                else "共 ${loaded!!.songs.size} 首。导入后可在桌面继续播放和整理。",
                fontSize = 11.sp,
                color = scheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
            Box(
                Modifier.fillMaxWidth().padding(top = 18.dp).height(38.dp).clip(RoundedCornerShape(7.dp))
                    .background(scheme.surfaceContainerHigh).padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (loaded == null && link.isEmpty()) Text("https://music.163.com/playlist?id=…", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                if (loaded != null && name.isEmpty()) Text("歌单名称", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                BasicTextField(
                    value = if (loaded == null) link else name,
                    onValueChange = { value -> if (loaded == null) { link = value; error = null } else name = value.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
            error?.let { Text(it, fontSize = 11.sp, color = scheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                SecondaryButton(if (busy) "处理中…" else "取消") { if (!busy) { if (loaded != null) loaded = null else onDismiss() } }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (busy) scheme.onBackground.copy(alpha = 0.12f) else scheme.primary)
                        .clickable(enabled = !busy && (if (loaded == null) link.isNotBlank() else name.isNotBlank()), role = Role.Button) {
                            if (busy) return@clickable
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    if (loaded == null) {
                                        val result = service.load(parsePlaylistAddress(link.trim()))
                                        loaded = result
                                        name = result.name.take(40)
                                    } else {
                                        val current = loaded ?: return@launch
                                        val id = store.createImported(name.trim(), current.songs.map { it.toSong("online") }.asReversed())
                                        metadata.start()
                                        onImported(id)
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "读取歌单失败，请检查链接"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (loaded == null) "预览歌单" else "导入", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimary)
                }
            }
        }
    }
}

