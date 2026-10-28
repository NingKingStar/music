package com.muses.player.navigation

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.MusesTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import top.yukonga.miuix.kmp.basic.Text
import com.muses.player.core.ui.components.MusesBottomDock
import com.muses.player.core.ui.components.MusesBottomDockItem
import com.muses.player.core.ui.components.MusesDockActionPill
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import androidx.compose.ui.unit.dp
import com.muses.player.core.data.dao.SongDao
import com.muses.player.core.data.db.SongTags
import com.muses.player.core.data.mapper.toDomain
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.lyrics.LyricsMatcher
import com.muses.player.core.lyrics.matchDocument
import com.muses.player.core.lyrics.observeDocuments
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.parser.LxLyricParser
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.NoOpOnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.playback.PlaybackMeta
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.ui.components.MiniPlayerBar
import com.muses.player.core.ui.components.LocalPlayerArtworkMorph
import com.muses.player.core.ui.components.PlayerArtworkMorph
import com.muses.player.core.ui.components.LocalPlayerSharedTransitionScope
import com.muses.player.core.ui.components.MusesSnackbarHostContent
import com.muses.player.feature.shell.platform.PermissionsEffect
import com.muses.player.feature.shell.platform.ShellBackHandler
import com.muses.player.feature.library.AlbumDetailScreen
import com.muses.player.feature.library.AlbumsPage
import com.muses.player.feature.library.ArtistDetailScreen
import com.muses.player.feature.library.ArtistsPage
import com.muses.player.feature.library.LibraryScreen
import com.muses.player.feature.player.PlayerScreen
import com.muses.player.feature.player.QueueScreen
import com.muses.player.feature.player.lyric.LyricsParser
import com.muses.player.feature.sources.LxScriptsScreen
import com.muses.player.feature.sources.LxSourceFormScreen
import com.muses.player.feature.home.HomeScreen
import com.muses.player.feature.home.HomeCollectionScreen
import com.muses.player.feature.home.ChartDetailScreen
import com.muses.player.feature.sources.OnlineSearchScreen
import com.muses.player.feature.sources.SourcesScreen
import com.muses.player.feature.sources.WebDavFormScreen
import com.muses.player.settings.AiSettingsScreen
import com.muses.player.settings.SettingsScreen
import com.muses.player.settings.MineScreen
import com.muses.player.settings.StatisticsScreen
import com.muses.player.settings.HistoryScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** MiniPlayerBar 的数据快照（对照 MiniPlayer.vue 的 playerState.currentSong 消费口径） */
data class NowPlayingUiState(
    val songId: String,
    val title: String,
    /** 宽屏副标题（≥ [TabletBreakpoint]）：「{artist} - {album}」，空值回退「未知艺术家/未知专辑」 */
    val subtitle: String,
    /** 窄屏副标题：只显示艺术家（专辑不进迷你条，09-07 定案） */
    val artist: String,
    val coverUri: String?,
)

/**
 * 主界面 ViewModel：管理播放连接与 MiniPlayer 数据（U22 上收 commonMain，
 * 播放链经 [PlaybackPort] 端口驱动，曲库标签合并逻辑保持不变）。
 */
class MainViewModel constructor(
    private val playback: PlaybackPort,
    private val songDao: SongDao,
    private val songRepository: SongRepository,
    private val settingsRepository: SettingsRepository,
    /**
     * 在线曲目歌词端口（迷你条歌词模式用）。
     * 缺省空实现：未装配在线音源时一切照旧，无需判空。
     */
    private val onlineMetadataResolver: OnlineTrackMetadataResolver = NoOpOnlineTrackMetadataResolver,
    /** Muses 在线歌词匹配（AMLL → 平台五源 → LRCLIB）：脚本 `lyric` 取不到时兜底 */
    private val lyricsMatcher: LyricsMatcher? = null,
    /** 首选脚本预热（可选）：只加载当前首选脚本，不解析地址。 */
    private val lxScriptRepository: com.muses.player.core.lxsdk.LxScriptRepository? = null,
) : ViewModel() {

    val isPlaying: StateFlow<Boolean> = playback.isPlaying

    /**
     * 当前曲信息：优先用 Room 的已回写标签（lazyScan 后），未回写前回退到播放栈实时
     * 元数据（[PlaybackMeta]，通知栏同源，解决「通知栏有信息但底部栏仍未知」）。
     * null = 无当前曲（MiniPlayer 显示空态整条，对照 `.mini-player--empty`）。
     */
    val nowPlaying: StateFlow<NowPlayingUiState?> = combine(
        playback.currentSongId,
        playback.currentMeta,
        OnlineTrackSession.snapshot,
    ) { songId, meta, online -> Triple(songId, meta, online) }
        .flatMapLatest { (songId, meta, online) ->
            if (songId == null) {
                flowOf(null)
            } else if (online[songId] != null) {
                // 在线歌曲已经登记，立即展示，不等待曲库查询。
                flowOf(mergeNowPlaying(songId, null, meta, online[songId]))
            } else {
                // 修复：改为 observeById Flow，使播放时回写后迷你条与列表一致（原 one-shot 不响应 DB 更新）
                // 在线曲目不入库：同时订阅会话表快照，让「迟到登记」也能点亮迷你条标题/封面
                songDao.observeById(songId).map { song -> mergeNowPlaying(songId, song, meta, online[songId]) }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── 迷你条歌词模式 ──

    /** 开关状态 */
    val miniPlayerLyricsEnabled: StateFlow<Boolean> = settingsRepository.miniPlayerLyricsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 当前歌词行文本（null = 无歌词或开关关闭） */
    private val _currentLyricLine = MutableStateFlow<String?>(null)
    val currentLyricLine: StateFlow<String?> = _currentLyricLine.asStateFlow()
    private val _currentLyricMarquee = MutableStateFlow<com.muses.player.core.ui.components.TimedTextMarquee?>(null)
    val currentLyricMarquee = _currentLyricMarquee.asStateFlow()

    /** 已解析歌词文档（切歌时更新） */
    private var lyricsDocument: LyricsDocument? = null
    private var lyricsSongId: String? = null

    init {
        observeLyrics()
        startLyricPositionPolling()
        warmPreferredLxSource()
    }

    /**
     * 空闲时预热当前首选脚本：冷启动后第一首歌不必再等 QuickJS 创建与脚本执行。
     * 只做「加载脚本」这一步，不解析地址、不发网络请求；自动选择模式没有首选，不做任何事。
     */
    private fun warmPreferredLxSource() {
        val repository = lxScriptRepository ?: return
        viewModelScope.launch {
            delay(3_000)
            if (playback.isPlaying.value) return@launch
            runCatching { repository.warmPreferred() }
        }
    }

    /** 订阅当前曲歌词变化：切歌时解析歌词文本 → 更新 [lyricsDocument] */
    private fun observeLyrics() {
        viewModelScope.launch {
            combine(
                playback.currentSongId,
                OnlineTrackSession.snapshot,
            ) { songId, online -> songId to online[songId] }
                .distinctUntilChanged()
                .flatMapLatest { (songId, online) ->
                    if (songId == null) {
                        flowOf(Triple(songId, null, null))
                    } else {
                        songDao.observeById(songId).map { entity -> Triple(songId, entity, online) }
                    }
                }
                .collectLatest { (songId, songEntity, online) ->
                    lyricsSongId = null
                    lyricsDocument = null
                    _currentLyricLine.value = null
                    _currentLyricMarquee.value = null
                    if (songEntity != null) {
                        val doc = withContext(Dispatchers.Default) {
                            LyricsParser.parseDocument(songEntity.lyrics)
                        }
                        if (songId == playback.currentSongId.value) {
                            lyricsDocument = doc; lyricsSongId = songId
                        }
                    } else if (online != null) {
                        val embedded = withContext(Dispatchers.Default) { LyricsParser.parseDocument(online.lyrics) }
                        if (songId == playback.currentSongId.value) {
                            lyricsDocument = embedded; lyricsSongId = songId
                        }
                        val matcher = lyricsMatcher
                        if (matcher != null) {
                            matcher.observeDocuments(online, onlineMetadataResolver).collect { update ->
                                if (songId == playback.currentSongId.value) {
                                    lyricsDocument = update.document; lyricsSongId = songId
                                }
                            }
                        } else {
                            val doc = loadOnlineLyrics(online)
                            if (songId == playback.currentSongId.value) {
                                lyricsDocument = doc; lyricsSongId = songId
                            }
                        }
                    }
                }
        }
    }

    /**
     * 在线曲目歌词：**脚本 `lyric` 优先 → Muses 在线歌词匹配兜底**（同播放页口径）。
     *
     * 与播放页（`PlayerViewModel`）看似各自拉取，实则共享注入的 [OnlineTrackMetadataResolver]
     * （装配时为 `CachedOnlineTrackMetadataResolver` 单例），同一首曲目只真正跑一次脚本。
     */
    private suspend fun loadOnlineLyrics(song: Song): LyricsDocument? {
        val ref = OnlineTrackRef.parse(song.path) ?: return null
        val lyrics = try {
            onlineMetadataResolver.resolveLyrics(ref)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (lyrics != null) {
            // 解析器自身已带异常兜底（LxLyricParser.parse）；此处再包一层是防御未来替换实现
            val fromScript = withContext(Dispatchers.Default) {
                runCatching {
                    LxLyricParser.parse(lyrics.lyric, lyrics.tlyric, lyrics.rlyric, lyrics.lxlyric)
                }.getOrNull()
            }
            if (fromScript != null && fromScript.lines.isNotEmpty()) return fromScript
        }
        // 脚本没给歌词（多数洛雪脚本只声明 musicUrl）→ 回退 Muses 在线歌词匹配
        return lyricsMatcher?.matchDocument(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            durationSec = song.durationSec,
            songPath = song.path,
        )
    }

    /** 歌词进度轮询：~100ms，根据播放位置查找当前歌词行；关闭时降频到 1s */
    private fun startLyricPositionPolling() {
        viewModelScope.launch {
            var lastLineKey: String? = null
            var lastPosition = 0L
            var wasPlaying = false
            while (true) {
                val enabled = miniPlayerLyricsEnabled.value
                if (enabled) {
                    val doc = lyricsDocument.takeIf { lyricsSongId == playback.currentSongId.value }
                    val pos = playback.currentPosition()
                    val index = doc?.highlightedIndex(pos)
                    val line = index?.let { doc?.lines?.getOrNull(it) }
                    val playing = playback.isPlaying.value
                    val lineKey = line?.let { "${playback.currentSongId.value}:$index:${it.timeMs}" }
                    _currentLyricLine.value = line?.text
                    if (line == null) {
                        _currentLyricMarquee.value = null
                    } else if (lineKey != lastLineKey || (playing && !wasPlaying) || kotlin.math.abs(pos - lastPosition) > 1_500) {
                        val end = doc?.lines?.getOrNull(index!! + 1)?.timeMs
                            ?: (line.timeMs + (line.durationMs ?: 5_000L))
                        _currentLyricMarquee.value = com.muses.player.core.ui.components.TimedTextMarquee(
                            durationMillis = (end - pos).coerceAtLeast(1), key = "$lineKey:$pos",
                        )
                    }
                    lastLineKey = lineKey
                    lastPosition = pos
                    wasPlaying = playing
                } else {
                    _currentLyricLine.value = null
                    _currentLyricMarquee.value = null
                    lastLineKey = null
                }
                delay(if (enabled) 100 else 1000)
            }
        }
    }

    private fun mergeNowPlaying(
        songId: String?,
        song: com.muses.player.core.data.db.SongEntity?,
        meta: PlaybackMeta?,
        online: Song? = null,
    ): NowPlayingUiState? {
        if (songId == null) return null
        // 在线曲目不入库：会话表条目即权威展示源（搜索结果的标题/艺术家/专辑/封面）
        if (song == null && online != null) {
            val onlineArtist = online.artist?.trim()?.takeIf { it.isNotEmpty() } ?: "未知艺术家"
            val onlineAlbum = online.album?.trim()?.takeIf { it.isNotEmpty() } ?: "未知专辑"
            return NowPlayingUiState(
                songId = songId,
                title = online.title.trim().takeIf { it.isNotEmpty() } ?: "未知歌曲",
                subtitle = "$onlineArtist - $onlineAlbum",
                artist = onlineArtist,
                coverUri = online.coverUri ?: meta?.takeIf { it.songId == songId }?.coverUri,
            )
        }
        val currentMetadata = meta?.takeIf { it.songId == songId }
        val metaTitle = currentMetadata?.title?.trim()?.takeIf { it.isNotEmpty() }
        val metaArtist = currentMetadata?.artist?.trim()?.takeIf { it.isNotEmpty() }
        val metaAlbum = currentMetadata?.album?.trim()?.takeIf { it.isNotEmpty() }
        val metaCover = currentMetadata?.coverUri
        // 修复：已刮削字段（metaTitle/metaArtist/metaAlbum/metaCover 非空）优先库值，避免重刮削后仍显示旧文件标签
        val title = when {
            song == null -> metaTitle ?: "未知歌曲"
            song.metaTitle != null -> song.title
            song.tagsVersion < SongTags.TAGS_VERSION -> metaTitle ?: song.title
            else -> song.title
        }
        val artist = when {
            song == null -> metaArtist ?: "未知艺术家"
            song.metaArtist != null -> song.artist ?: "未知艺术家"
            song.tagsVersion < SongTags.TAGS_VERSION -> metaArtist ?: song.artist ?: "未知艺术家"
            else -> song.artist ?: "未知艺术家"
        }
        val album = when {
            song == null -> metaAlbum ?: "未知专辑"
            song.metaAlbum != null -> song.albumTitle ?: "未知专辑"
            song.tagsVersion < SongTags.TAGS_VERSION -> metaAlbum ?: song.albumTitle ?: "未知专辑"
            else -> song.albumTitle ?: "未知专辑"
        }
        val cover = when {
            song == null -> metaCover
            song.metaCover != null -> song.coverUri
            song.tagsVersion < SongTags.TAGS_VERSION -> metaCover ?: song.coverUri
            else -> song.coverUri
        }
        return NowPlayingUiState(
            songId = songId,
            title = title,
            subtitle = "$artist - $album",
            artist = artist,
            coverUri = cover,
        )
    }

    /** 存量库专辑/艺术家索引回填（幂等） */
    fun rebuildLibraryIndexes() {
        viewModelScope.launch {
            runCatching { songRepository.rebuildDerivedIndexes() }
        }
    }

    fun connectPlayer() {
        playback.connect()
    }

    fun disconnectPlayer() {
        playback.disconnect()
    }

    fun playPause() = playback.playPause()

    fun skipToNext() = playback.skipToNext()

    fun skipToPrevious() = playback.skipToPrevious()
}

/**
 * 主框架入口：手机和平板共用底部导航、迷你播放器与页面返回栈。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun MusesApp(openSettingsRequest: Long = 0L) {
    val builtinUpdater = org.koin.compose.koinInject<com.muses.player.core.lxsdk.store.BuiltinLxSourceUpdater>()
    LaunchedEffect(builtinUpdater) { builtinUpdater.runPeriodicChecks() }
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPlayerSharedTransitionScope provides this) {
            MusesAppContent(openSettingsRequest)
        }
    }
}

@Composable
private fun MusesAppContent(openSettingsRequest: Long) {
        // miuix-nav 返回栈（类型化路由，存栈恢复经 kotlinx.serialization；替代 CMP Navigation）
        // 启动落地首页（探索 = 排行榜 + 猜你喜欢）
        val backStack = rememberNavBackStack<MusesRoute>(MusesRoute.Home)
        // 当前栈顶（SnapshotStateList 读取即订阅，路由变化自动重组；对照原 currentBackStackEntryAsState）
        val currentKey = backStack.lastOrNull()

        // 底部迷你条和导航胶囊在手机、平板上共用。
        val viewModel: MainViewModel = koinViewModel()
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            viewModel.connectPlayer()
        }

        // 连接播放服务
        LaunchedEffect(Unit) {
            viewModel.connectPlayer()
            // 存量库回填：albums/artists 索引此前无维护方，启动时幂等重建一次
            viewModel.rebuildLibraryIndexes()
        }

        // 权限申请（安卓：READ_MEDIA_AUDIO/POST_NOTIFICATIONS；桌面空实现）
        PermissionsEffect()

        // S3 待审队列宿主 VM（MusesApp 作用域持有，review 回调直用；原按回退栈取 Scrape 页 entry 实例）
        val scrapeVm: com.muses.player.feature.scrape.ScrapeViewModel = koinViewModel()

        // 打开时面板用较长的舒展节奏从迷你条扩展，文字和控制区延后显现；
        // 收起仍按原节奏缩回，封面通过共享元素在两端之间飞行。
        var showPlayerOverlay by remember { mutableStateOf(false) }
        val playerProgressState = remember { Animatable(0f) }
        val playerOpen = showPlayerOverlay
        val playerTransitionScope = rememberCoroutineScope()
        var playerTransitionJob by remember { mutableStateOf<Job?>(null) }
        fun animatePlayerTo(target: Boolean) {
            playerTransitionJob?.cancel()
            playerTransitionJob = playerTransitionScope.launch {
                playerProgressState.animateTo(
                    targetValue = if (target) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (target) PlayerOpenDurationMillis else PlayerTransitionMillis,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
        fun seekPlayerTo(fraction: Float) {
            // 手势每帧只保留最新一次 seek，防止快速拖动时旧协程覆盖新进度。
            playerTransitionJob?.cancel()
            playerTransitionJob = playerTransitionScope.launch {
                playerProgressState.snapTo(1f - fraction.coerceIn(0f, 0.999f))
            }
        }
        fun openPlayer() {
            showPlayerOverlay = true
            playerTransitionJob?.cancel()
            playerTransitionJob = playerTransitionScope.launch {
                playerProgressState.snapTo(0f)
                // 先让播放页和封面完成首帧布局，再开始插值，避免首帧直接跳到大面板。
                withFrameNanos { }
                withFrameNanos { }
                playerProgressState.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(PlayerOpenDurationMillis, easing = FastOutSlowInEasing),
                )
            }
        }
        fun closePlayer() {
            showPlayerOverlay = false
            animatePlayerTo(target = false)
        }
        LaunchedEffect(openSettingsRequest) {
            if (openSettingsRequest > 0L) {
                closePlayer()
                backStack.pushUnique(MusesRoute.Settings)
            }
        }
        fun settlePlayer(collapse: Boolean, releasedFraction: Float) {
            val target = !collapse
            showPlayerOverlay = target
            playerTransitionJob?.cancel()
            playerTransitionJob = playerTransitionScope.launch {
                val startFraction = releasedFraction.coerceIn(0f, 0.999f)
                playerProgressState.snapTo(1f - startFraction)
                if (collapse) {
                    playerProgressState.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 675f),
                    )
                } else {
                    playerProgressState.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(220, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)),
                    )
                }
            }
        }
        val playerProgress = playerProgressState.value.coerceIn(0f, 1f)
        val playerOverlayMounted = showPlayerOverlay || playerProgress > 0f
        // 迷你条在窗口中的真实矩形（px）：底部 chrome 可见顶的上报源（悬浮 FAB 定位见 bottomBarElevation）。
        // 融合态（CompactPlayerDock）与展开态各有一个实例，两者都要上报，缺一会拿到过期矩形。
        var miniBarBounds by remember { mutableStateOf<Rect?>(null) }
        var showQueueOverlay by remember { mutableStateOf(false) }
        var playerActionSongId by remember { mutableStateOf<String?>(null) }
        val playerVm: com.muses.player.feature.player.PlayerViewModel = koinViewModel()
        val currentSongId by playerVm.currentSongId.collectAsState()
        val playbackError by playerVm.playbackError.collectAsState()
        LaunchedEffect(playbackError) {
            playbackError?.let { com.muses.player.core.ui.components.MusesSnackbar.show(it) }
        }
        val downloadManager = org.koin.compose.koinInject<com.muses.player.download.DownloadManager>()
        val downloadSettings = org.koin.compose.koinInject<SettingsRepository>()
        val chooseDownloadQuality by downloadSettings.chooseDownloadQuality.collectAsState(true)
        val defaultDownloadQuality by downloadSettings.downloadPreferredQuality.collectAsState("320k")
        var qualityDownloadSong by remember { mutableStateOf<Song?>(null) }
        val requestDownload: (Song) -> Unit = { song ->
            if (chooseDownloadQuality) qualityDownloadSong = song
            else downloadManager.enqueue(song, startImmediately = true)
            Unit
        }
        val favoritesStore = org.koin.compose.koinInject<com.muses.player.core.data.repository.FavoriteSongsStore>()
        val favorites by favoritesStore.state.collectAsState()
        val playlistStore = org.koin.compose.koinInject<com.muses.player.core.data.repository.UserPlaylistStore>()
        var playlistSong by remember { mutableStateOf<Song?>(null) }
        val addSongToPlaylist: (Song) -> Unit = { song -> playlistSong = song }
        val downloadDuplicate by downloadManager.libraryDuplicatePrompt.collectAsState()
        val onlineSongs by com.muses.player.core.model.online.OnlineTrackSession.snapshot.collectAsState()
        val downloadSong = onlineSongs[playerActionSongId]
        val isOnlineActionSong = playerActionSongId?.startsWith("online:") == true
        val playbackPort = org.koin.compose.koinInject<com.muses.player.core.playback.PlaybackPort>()
        val songRepository = org.koin.compose.koinInject<com.muses.player.core.data.repository.SongRepository>()
        val actionScope = rememberCoroutineScope()
        val setSongLiked: (Song, Boolean) -> Unit = { song, liked ->
            actionScope.launch {
                try {
                    favoritesStore.setLiked(song, liked)
                    com.muses.player.core.ui.components.MusesSnackbar.show(
                        if (liked) "已加入我喜欢" else "已从我喜欢移除")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { com.muses.player.core.ui.components.MusesSnackbar.show("保存喜欢标记失败，请重试") }
            }
            Unit
        }
        val scrapeQueueVm: com.muses.player.feature.scrape.ScrapeQueueAccessViewModel = koinViewModel()

        // 全局短提示宿主状态（MusesApp 作用域持有，跨重组保持；消费见 MusesSnackbar）
        val snackbarHostState = remember { SnackbarHostState() }

        // 手机和平板共用「探索 / 歌单 / 我的」三个底部入口。
        val bottomItems = listOf(
            NavDestination.Home.toNavItem(currentKey, backStack),
            NavDestination.Songs.toNavItem(currentKey, backStack),
            NavDestination.Mine.toNavItem(currentKey, backStack),
        )

        val observedNowPlaying by viewModel.nowPlaying.collectAsState()
        val nowPlaying = observedNowPlaying?.takeIf { it.songId == currentSongId }
        val isPlaying by viewModel.isPlaying.collectAsState()
        val isBuffering by playerVm.isBuffering.collectAsState()
        val miniPlayerLyricsEnabled by viewModel.miniPlayerLyricsEnabled.collectAsState()
        val currentLyricLine by viewModel.currentLyricLine.collectAsState()
        val currentLyricMarquee by viewModel.currentLyricMarquee.collectAsState()

        // ── 滚动融合（借鉴 Halcyon 的 BottomDockMode）──
        // 列表向下滚（内容上滑）→ 紧凑；向上滚 → 展开；不消费滚动（返回 Offset.Zero）。
        // 阀值不对齐（-12 / +16）是 Halcyon 的做法：向下更敏感、向上需明确回滚，避免抖动。
        var bottomDockCompact by rememberSaveable { mutableStateOf(false) }
        val dockScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    when {
                        available.y < -12f -> bottomDockCompact = true
                        available.y > 16f -> bottomDockCompact = false
                    }
                    return Offset.Zero
                }
            }
        }
        // 无歌曲时不进入紧凑形态（没内容可融合）；当前 tab 图标用于融合胶囊左侧
        val dockCompact = bottomDockCompact && nowPlaying != null
        val currentTabItem = bottomItems.firstOrNull { it.active }
            ?: bottomItems.first()
        // 路由变化时重置为展开态（对齐 Halcyon 的 LaunchedEffect(currentRoute, canCompact)）：
        // 否则从列表页滚着进子页（如在线搜索/详情）时，底栏会带着上一页的融合态打开，
        // 底栏显示成三部分而非完整导航。
        LaunchedEffect(currentKey) { bottomDockCompact = false }
        // 融合过渡进度（借鉴 Halcyon）：弹簧进度驱动导航图标缩放，
        // 让「两行 ↔ 一行」的切换不再是一帧跳变（Halcyon 用 spring(0.88, 520)）。
        val compactProgress by animateFloatAsState(
            targetValue = if (dockCompact) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 520f),
            label = "bottom-dock-compact",
        )

        // 根 Scaffold bottomBar 同时承载手机和平板的迷你条与悬浮导航。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isWideSubtitle = maxWidth >= TabletBreakpoint
            // 内容区保持**全屏**（不挤压）：内容可以一直滚到屏幕底、从悬浮 chrome 背后穿过，
            // 这是悬浮 Dock 该有的观感。避免遮挡靠各页面统一的 contentPadding 避让（见 TabsLayout），
            // 关键是避让值**恒定**——一旦随滚动融合变化，就会出现「避让变小后列表位置不跟着变 →
            // 展开回去时底部内容被多出的 chrome 盖住、且已到底再也滚不出来」的死角。
            val chromeSideMargin = 18.dp
            // 底部 chrome 可见顶的窗口坐标（悬浮 FAB 定位源，见 LocalBottomChromeElevation）：
            // 直接取迷你条**可见**矩形顶（融合/展开两态都由 reportMiniBarBounds 上报，
            // 且两态它都是最靠上的可见件）。不用 bottomBar 节点总高——节点含透明 padding，
            // 实测比可见胶囊顶高 20dp，FAB 跟着悬空（反馈「还是离得挺远」）。
            // 也不换算「距屏底」——FAB slot 自带内缩且与 WindowInsets 无关（手势导航下
            // nav=0），FAB 侧用同一窗口坐标系实测 slot 底相减即可，与 inset 来源无关。
            val bottomBarElevation = remember { mutableStateOf(com.muses.player.core.ui.theme.PhoneBottomChromePadding) }
            val shellDensity = LocalDensity.current
            LaunchedEffect(miniBarBounds) {
                val bounds = miniBarBounds ?: return@LaunchedEffect
                bottomBarElevation.value = with(shellDensity) { bounds.top.toDp() }
            }
            CompositionLocalProvider(
                com.muses.player.core.ui.components.LocalSongMarqueeState provides
                    com.muses.player.core.ui.components.SongMarqueeState(currentSongId, isPlaying),
                com.muses.player.core.ui.components.LocalDownloadSong provides requestDownload,
                com.muses.player.core.ui.components.LocalFavoriteSongs provides favorites,
                com.muses.player.core.ui.components.LocalSetSongLiked provides setSongLiked,
                com.muses.player.core.ui.components.LocalAddSongToPlaylist provides addSongToPlaylist,
                com.muses.player.core.ui.theme.LocalBottomChromeElevation provides bottomBarElevation,
            ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MiuixTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // 订阅始终留在主宿主，沉浸页挂载期间只由上层宿主显示同一提示状态。
                snackbarHost = { MusesSnackbarHostContent(snackbarHostState, visible = !playerOverlayMounted) },
                bottomBar = {
                    AnimatedContent(
                        targetState = dockCompact,
                        // 吃系统导航栏/手势条 inset + 8dp 保底视觉抬升（对齐 Halcyon 的 MainBottomDock）：
                        // 部分 OEM（ColorOS 某些模式）在显示手势条时会上报 nav inset = 0，
                        // 仅靠 inset 会让底栏贴住手势条；8dp 保证始终有一点呼吸。
                        // （FAB 定位不用这里的节点总高：节点含透明 padding 不等于可见顶，
                        //   改由 miniBarBounds 可见矩形上报，见上方 bottomBarElevation。）
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp),
                        transitionSpec = {
                            // 两套布局淡入淡出；内部尺寸变化由 compactProgress 弹簧连续插值
                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                        },
                        label = "bottom-dock",
                    ) { compact ->
                    if (compact) {
                        // 滚动融合形态（借鉴 Halcyon）：两行合并为一行
                        // [当前 tab 图标] + [紧凑迷你播放器]，点左侧图标展开回两行
                        CompactPlayerDock(
                            tabIcon = currentTabItem.icon,
                            title = nowPlaying?.title ?: "空空如也~",
                            subtitle = nowPlaying?.artist ?: "未知艺术家",
                            coverUri = nowPlaying?.coverUri,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            hasSong = nowPlaying != null,
                            onExpand = { bottomDockCompact = false },
                            onOpenPlayer = { openPlayer() },
                            onTogglePlayback = { viewModel.playPause() },
                            onOpenQueue = { showQueueOverlay = true },
                            sideMargin = chromeSideMargin,
                            compactProgress = compactProgress,
                            playerTransitionProgress = if (playerOverlayMounted) 1f else 0f,
                            onPlayerBounds = { miniBarBounds = it },
                        )
                    } else {
                    // 底部槽位 = 迷你条（上）+ 悬浮导航栏（下）。
                    // 迷你条与底栏同为悬浮胶囊，上下叠放（形态对齐 Halcyon 的悬浮 Dock）。
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // 歌词模式：开关开启且有当前歌词行时，用歌词替换艺术家
                    val lyricLine = if (miniPlayerLyricsEnabled) currentLyricLine else null
                    val miniSubtitle = if (lyricLine != null) {
                        lyricLine
                    } else if (isWideSubtitle) {
                        // 宽屏（Windows/平板）「艺术家 - 专辑」
                        nowPlaying?.subtitle ?: "未知艺术家 - 未知专辑"
                    } else {
                        // 窄屏「艺术家」
                        nowPlaying?.artist ?: "未知艺术家"
                    }
                    MiniPlayerBar(
                        title = nowPlaying?.title ?: "空空如也~",
                        subtitle = miniSubtitle,
                        subtitleMarquee = if (lyricLine != null) currentLyricMarquee else null,
                        coverUri = nowPlaying?.coverUri,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        hasSong = nowPlaying != null,
                        onOpenPlayer = { openPlayer() },
                        onTogglePlayback = { viewModel.playPause() },
                        onOpenQueue = { showQueueOverlay = true },
                        onNext = { viewModel.skipToNext() },
                        onPrevious = { viewModel.skipToPrevious() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = chromeSideMargin, vertical = 8.dp)
                            .graphicsLayer { alpha = if (playerOverlayMounted) 0f else 1f }
                            .reportMiniBarBounds { miniBarBounds = it },
                    )
                    }
                    // 悬浮胶囊底栏（自研 MusesBottomDock，图标 + 文字，选中态为滑动胶囊）：
                    // 与迷你条只留迷你条自身的 8dp 下边距作间隙。曾额外 `.offset(y = 12.dp)` 下移底栏
                    // 去「收紧底部留白」，但那会让迷你条与底栏之间空出 20dp，观感松散（已移除）。
                    // 注意任何位移必须包在组件外面：经 modifier 参数传进去只会偏移内部内容，背景不动。
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // 导航胶囊占满可用宽度，搜索已移至探索页顶部。
                        // 左右边距必须由**外层 Row 的 padding**提供：
                        // 若写在 MusesBottomDock 自己的 modifier 链上（fillMaxWidth().padding()），
                        // 胶囊背景仍按全宽绘制，窄屏下右侧会贴到屏幕边缘被裁（360dp 实测）。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = chromeSideMargin),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MusesBottomDock(
                                modifier = Modifier.weight(1f),
                                items = bottomItems.map { item ->
                                    MusesBottomDockItem(
                                        icon = item.icon,
                                        label = item.label,
                                        active = item.active,
                                        onClick = item.onClick,
                                    )
                                },
                            )
                        }
                    }
                    }
                    }
                }
            },
        ) { _ ->
            // 内容区保持全屏（不挤压）：内容滚到底时从悬浮 chrome 背后穿过，避免遮挡靠恒定避让（见 TabsLayout）。
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(dockScrollConnection),
            ) {
            // 子页面系统返回 → 上一级；主页（栈深 1）不消费，留在本页。
            // overlay 打开时不抢返回：沉浸页/队列各自消费（沉浸页见下方 ShellBackHandler，队列由 sheet 自行消费）。
            ShellBackHandler(enabled = backStack.size > 1 && !playerOpen && !showQueueOverlay && downloadDuplicate == null && playlistSong == null) {
                backStack.pop()
            }
            TabsLayout {
                AppNavHost(backStack, scrapeVm)
            }
            // 队列走 MusesBottomSheet（miuix OverlayBottomSheet）：必须组合在 Scaffold 内容层级内，
            // 根弹窗宿主（LocalRootDialogStates 由 Scaffold 提供）才能接管渲染；之前跟沉浸页一样放
            // Scaffold 外，宿主查不到导致 sheet 静默不显示（裸 Dialog 自带窗口才不受影响）。
            // 宿主层绘制在 bottomBar 之上，不会被迷你条盖住；返回/遮罩由 sheet 自行消费，不套 BackHandler。
            if (showQueueOverlay && !playerOverlayMounted) {
                QueueScreen(onClose = { showQueueOverlay = false })
            }
            if (!playerOverlayMounted) {
                com.muses.player.download.DownloadLibraryDuplicateDialog(downloadManager)
                DownloadQualityPicker(qualityDownloadSong, defaultDownloadQuality, onDismiss = { qualityDownloadSong = null }) { song, quality ->
                    qualityDownloadSong = null
                    downloadManager.enqueue(song, startImmediately = true, selectedQuality = quality)
                }
                playlistSong?.let { song ->
                    com.muses.player.core.ui.components.PlaylistPickerDialog(song, playlistStore, onDismiss = { playlistSong = null })
                }
            }
            }
        }
            } // CompositionLocalProvider 闭合：包住整个 Scaffold（FAB 槽也在内）
        } // BoxWithConstraints 关（主屏常驻，动画中不位移/不淡化）
        if (playerOverlayMounted) {
            CompositionLocalProvider(
                com.muses.player.core.ui.components.LocalFavoriteSongs provides favorites,
                com.muses.player.core.ui.components.LocalSetSongLiked provides setSongLiked,
                com.muses.player.core.ui.components.LocalAddSongToPlaylist provides addSongToPlaylist,
            ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // 沉浸页位于主 Scaffold 上方；提示也必须在这一层的官方槽位绘制。
                snackbarHost = { SnackbarHost(state = snackbarHostState) },
            ) { _ ->
                Box(Modifier.fillMaxSize()) {
                    ImmersivePlayerOverlay(
                        playerProgress = playerProgress,
                        readPlayerProgress = { playerProgressState.value },
                        sheetOpen = playerActionSongId != null || showQueueOverlay || downloadDuplicate != null || playlistSong != null,
                        miniBarBounds = miniBarBounds,
                        miniPlayerLyricsEnabled = miniPlayerLyricsEnabled,
                        currentLyricLine = currentLyricLine,
                        currentLyricMarquee = currentLyricMarquee,
                        nowPlaying = nowPlaying,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        onClose = { closePlayer() },
                        onOpenPlayer = { openPlayer() },
                        onTogglePlayback = { viewModel.playPause() },
                        onOpenQueue = { showQueueOverlay = true },
                        onOpenEditMeta = { playerActionSongId = currentSongId },
                        onNext = { viewModel.skipToNext() },
                        onPrevious = { viewModel.skipToPrevious() },
                        onSeekCollapse = { fraction -> seekPlayerTo(fraction) },
                        onSettleCollapse = { collapse, fraction -> settlePlayer(collapse, fraction) },
                    )
                    if (showQueueOverlay) {
                        QueueScreen(onClose = { showQueueOverlay = false })
                    }
                    com.muses.player.core.ui.components.MusesSongActionsSheet(
                        songId = playerActionSongId,
                        favoriteSong = downloadSong ?: favorites.library.firstOrNull { it.id == playerActionSongId },
                        onDismiss = { playerActionSongId = null },
                        onEnqueueScrape = if (isOnlineActionSong) null else scrapeQueueVm::enqueue,
                        onAddToQueue = { id ->
                            actionScope.launch {
                                // 在线曲目不入库，查库落空时回落到会话登记表
                                val song = songRepository.getSong(id) ?: onlineSongs[id]
                                if (song == null) {
                                    com.muses.player.core.ui.components.MusesSnackbar.show("添加到播放队列失败")
                                } else if (id in playbackPort.queueSongIds.value) {
                                    com.muses.player.core.ui.components.MusesSnackbar.show("已在播放队列中")
                                } else {
                                    playbackPort.addToQueue(listOf(song))
                                    com.muses.player.core.ui.components.MusesSnackbar.show("添加成功")
                                }
                            }
                        },
                        onEnqueueDownload = downloadSong?.let { song -> { requestDownload(song) } },
                    )
                    com.muses.player.download.DownloadLibraryDuplicateDialog(downloadManager)
                    DownloadQualityPicker(qualityDownloadSong, defaultDownloadQuality, onDismiss = { qualityDownloadSong = null }) { song, quality ->
                        qualityDownloadSong = null
                        downloadManager.enqueue(song, startImmediately = true, selectedQuality = quality)
                    }
                    playlistSong?.let { song ->
                        com.muses.player.core.ui.components.PlaylistPickerDialog(song, playlistStore, onDismiss = { playlistSong = null })
                    }
                }
            }
            }
        }
}

@Composable
private fun ImmersivePlayerOverlay(
    playerProgress: Float,
    readPlayerProgress: () -> Float,
    sheetOpen: Boolean,
    miniBarBounds: Rect?,
    miniPlayerLyricsEnabled: Boolean,
    currentLyricLine: String?,
    currentLyricMarquee: com.muses.player.core.ui.components.TimedTextMarquee?,
    nowPlaying: NowPlayingUiState?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClose: () -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekCollapse: (Float) -> Unit,
    onSettleCollapse: (Boolean, Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val source = miniBarBounds
        val sourceWidth = (source?.width ?: fullWidthPx).coerceIn(1f, fullWidthPx)
        val sourceHeight = (source?.height ?: with(density) { 72.dp.toPx() }).coerceIn(1f, fullHeightPx)
        val sourceLeft = (source?.left ?: 0f).coerceIn(0f, fullWidthPx - sourceWidth)
        val sourceTop = (source?.top ?: fullHeightPx - sourceHeight).coerceIn(0f, fullHeightPx - sourceHeight)
        val miniSurfaceColor = MiuixTheme.colorScheme.surfaceContainer
        val miniArtworkSize = with(density) { 40.dp.toPx() }
        val coverLeft = sourceLeft + with(density) { 12.dp.toPx() }
        val coverTop = sourceTop + (sourceHeight - miniArtworkSize) / 2f
        val artworkMorph = remember(coverLeft, coverTop, miniArtworkSize) {
            PlayerArtworkMorph(
                sourceBounds = Rect(coverLeft, coverTop, coverLeft + miniArtworkSize, coverTop + miniArtworkSize),
                progress = readPlayerProgress,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().drawWithContent {
                val progress = readPlayerProgress().coerceIn(0f, 1f)
                val panelLeft = sourceLeft * (1f - progress)
                val panelTop = sourceTop * (1f - progress)
                val panelRect = Rect(
                    left = panelLeft,
                    top = panelTop,
                    right = panelLeft + sourceWidth + (fullWidthPx - sourceWidth) * progress,
                    bottom = panelTop + sourceHeight + (fullHeightPx - sourceHeight) * progress,
                )
                val radius = with(density) { 28.dp.toPx() } * (1f - progress)
                val path = Path().apply { addRoundRect(RoundRect(panelRect, CornerRadius(radius, radius))) }
                clipPath(path) {
                    drawRect(miniSurfaceColor)
                    this@drawWithContent.drawContent()
                }
            },
        ) {
            ShellBackHandler(enabled = !sheetOpen) { onClose() }
            CompositionLocalProvider(LocalPlayerArtworkMorph provides artworkMorph) {
                MusesTheme(useDarkTheme = true) {
                    PlayerScreen(
                        modifier = Modifier.fillMaxSize(),
                        onClose = onClose,
                        onOpenQueue = onOpenQueue,
                        onOpenEditMeta = onOpenEditMeta,
                        transitionProgress = { ((readPlayerProgress() - 0.05f) / 0.2f).coerceIn(0f, 1f) },
                        collapseOffsetY = { sourceTop * (1f - readPlayerProgress().coerceIn(0f, 1f)) },
                        headingCollapseAlpha = { ((readPlayerProgress().coerceIn(0f, 1f) - 0.75f) / 0.25f).coerceIn(0f, 1f) },
                        backgroundAlpha = { (readPlayerProgress().coerceIn(0f, 1f) / 0.25f).coerceIn(0f, 1f) },
                        initialCoverUri = nowPlaying?.coverUri,
                        initialCoverSongId = nowPlaying?.songId,
                        onSeekCollapse = onSeekCollapse,
                        onSettleCollapse = onSettleCollapse,
                    )
                }
            }
            if (source != null && playerProgress < 0.16f) {
                val transitionSubtitle = if (miniPlayerLyricsEnabled) currentLyricLine ?: nowPlaying?.artist ?: "未知艺术家"
                else nowPlaying?.artist ?: "未知艺术家"
                Box(
                    Modifier
                        .offset { IntOffset(sourceLeft.roundToInt(), sourceTop.roundToInt()) }
                        .size(width = with(density) { sourceWidth.toDp() }, height = with(density) { sourceHeight.toDp() })
                        .graphicsLayer { alpha = (1f - readPlayerProgress() / 0.16f).coerceIn(0f, 1f) },
                ) {
                    MiniPlayerBar(
                        title = nowPlaying?.title ?: "空空如也~",
                        subtitle = transitionSubtitle,
                        subtitleMarquee = if (miniPlayerLyricsEnabled && currentLyricLine != null) currentLyricMarquee else null,
                        coverUri = nowPlaying?.coverUri,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        hasSong = nowPlaying != null,
                        onOpenPlayer = onOpenPlayer,
                        onTogglePlayback = onTogglePlayback,
                        onOpenQueue = onOpenQueue,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        modifier = Modifier.fillMaxSize(),
                        sharedArtwork = false,
                        drawSurface = false,
                        drawArtwork = false,
                    )
                }
            }
        }
    }
}

/**
 * 椒盐点击切换播放页使用 300ms FastOutSlowIn tween；拖动松手使用 Pager 的弹簧吸附。
 */
private const val PlayerTransitionMillis = 300
private const val PlayerOpenDurationMillis = 380


/**
 * 迷你条矩形上报：把迷你条在**窗口坐标系**中的真实矩形写到 [onBounds]（px）。
 *
 * 用 `boundsInWindow` 而非 `boundsInRoot`：悬浮底栏根据窗口坐标定位，避免透明 padding
 * 影响可见顶的位置。播放页开合会读取这组窗口坐标作为扩展/收缩的起始矩形。
 * 空态（无当前曲）时迷你条整条不可点、也不会开沉浸页，但照旧上报——
 * 多一次上报的成本可忽略，避免了「刚选好歌就点开」时矩形还是旧值的分支。
 */
private fun Modifier.reportMiniBarBounds(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInWindow()) }

/** 导航项组装（图标/文案/激活判定均来自 NavDestination 的 Web 层映射） */
private fun NavDestination.toNavItem(
    currentKey: NavKey?,
    backStack: NavBackStack,
): MusesNavItem = MusesNavItem(
    icon = icon,
    label = label,
    active = this == activeDestination(backStack),
    onClick = { navigateToTab(backStack, this) },
)

/**
 * 幂等 push：栈顶即目标则不动（单例），否则先移除同值 key 再压栈。
 * miuix-nav 同值重复 push 会在 reconcile 直接抛 IllegalArgumentException，
 * 此处顺带承担连点防抖（对照官方指南「push 幂等」要求）。
 */
private fun NavBackStack.pushUnique(key: NavKey) {
    if (lastOrNull() == key) return
    remove(key)
    add(key)
}

/** 回退：仅栈深 >1 时弹栈（miuix-nav 空栈非法；对照原 popBackStack 布尔语义）。 */
private fun NavBackStack.pop() {
    if (size > 1) removeAt(lastIndex)
}

/** 移除目标路由上方的子页面，回到目标页。 */
private fun NavBackStack.popTo(key: NavKey) {
    val targetIndex = indexOfLast { it == key }
    if (targetIndex < 0) return
    while (lastIndex > targetIndex) removeAt(lastIndex)
}

/**
 * 切 tab：主页单例替换 + 点当前 tab 直接回主页。
 *
 * - 目标已在栈内（含正处其子页面时点本 tab）：弹到目标之上全清，直接回到该主页；
 * - 切到别的 tab：整栈替换为新主页单例（先 add 再从头清，全程非空，miuix-nav 空栈非法）；
 * - 子页面栈不跨 tab 保留（列表由 flow 重载，滚动回到顶部），切走即丢弃，返回时逐级弹栈。
 */
private fun navigateToTab(backStack: NavBackStack, destination: NavDestination) {
    val target = destination.routeKey
    if (backStack.contains(target)) {
        while (backStack.lastOrNull() != target) backStack.removeAt(backStack.lastIndex)
        return
    }
    backStack.add(target)
    while (backStack.size > 1) backStack.removeAt(0)
}

@Composable
private fun AppNavHost(
    backStack: NavBackStack,
    /** S3 待审队列宿主 VM（MusesApp 作用域持有；原按回退栈取 Scrape 页 entry 实例） */
    scrapeVm: com.muses.player.feature.scrape.ScrapeViewModel,
) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        effects = top.yukonga.miuix.kmp.nav.core.NavDisplayEffects(
            cornerClipRadius = top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius(),
        ),
    ) {
        entry<MusesRoute.Songs> {
            val store = org.koin.compose.koinInject<com.muses.player.core.data.repository.UserPlaylistStore>()
            val favorites = com.muses.player.core.ui.components.LocalFavoriteSongs.current
            val downloads = org.koin.compose.koinInject<com.muses.player.core.download.DownloadQueueStore>()
            val downloaded by downloads.downloadedSongs.collectAsState(initial = emptyList())
            var importing by remember { mutableStateOf(false) }
            com.muses.player.feature.library.PlaylistsScreen(store, favorites.likedSongs.size, downloaded.size,
                onOpen = { backStack.pushUnique(MusesRoute.UserCollection(it)) }, onImport = { importing = true })
            if (importing) ImportPlaylistDialog(store, onDismiss = { importing = false },
                onOpen = { importing = false; backStack.pushUnique(MusesRoute.UserCollection(it)) })
        }
        entry<MusesRoute.UserCollection>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            UserCollectionRoute(route.collectionId, onBack = { backStack.pop() },
                onManageDownloads = { backStack.pushUnique(MusesRoute.Downloads) })
        }
        entry<MusesRoute.LocalLibrary>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            // 原曲库作为“我的”子页保留歌曲、专辑与艺术家入口。
            val playback = org.koin.compose.koinInject<com.muses.player.core.playback.PlaybackPort>()
            // M3：刮削队列入队（ScrapeQueueStore 为 @Singleton，经 koinViewModel 载体注入）
            val scrapeQueueVm: com.muses.player.feature.scrape.ScrapeQueueAccessViewModel = koinViewModel()
            val libraryScanner = org.koin.compose.koinInject<com.muses.player.feature.sources.LibraryRefreshScanner>()
            LibraryScreen(
                onBack = { backStack.pop() },
                playback = playback,
                onEnqueueScrape = { ids -> scrapeQueueVm.enqueue(ids) },
                onScanSources = {
                    com.muses.player.core.ui.components.MusesSnackbar.show(libraryScanner.refresh())
                },
                onAlbumClick = { albumId -> backStack.pushUnique(MusesRoute.AlbumDetail(albumId)) },
                onArtistClick = { artistId -> backStack.pushUnique(MusesRoute.ArtistDetail(artistId)) },
            )
        }
        entry<MusesRoute.Albums> {
            AlbumsPage(
                onAlbumClick = { albumId ->
                    backStack.pushUnique(MusesRoute.AlbumDetail(albumId))
                },
            )
        }
        entry<MusesRoute.Artists> {
            ArtistsPage(
                onArtistClick = { artistId ->
                    backStack.pushUnique(MusesRoute.ArtistDetail(artistId))
                },
            )
        }
        entry<MusesRoute.AlbumDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            val playerConnection = koinViewModel<com.muses.player.feature.player.PlayerViewModel>().playback
            val scrapeQueueVm: com.muses.player.feature.scrape.ScrapeQueueAccessViewModel = koinViewModel()
            AlbumDetailScreen(
                albumId = route.albumId,
                onBack = { backStack.pop() },
                playback = playerConnection,
                onEnqueueScrape = scrapeQueueVm::enqueue,
            )
        }
        entry<MusesRoute.ArtistDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            val playerConnection = koinViewModel<com.muses.player.feature.player.PlayerViewModel>().playback
            val scrapeQueueVm: com.muses.player.feature.scrape.ScrapeQueueAccessViewModel = koinViewModel()
            ArtistDetailScreen(
                artistId = route.artistId,
                onBack = { backStack.pop() },
                playback = playerConnection,
                onEnqueueScrape = scrapeQueueVm::enqueue,
            )
        }
        // 刮削页随 M3 复刻（VM 由宿主直持传入；原 ScrapeScreen 内 koinViewModel() entry 作用域已改显式注入）
        entry<MusesRoute.Scrape> {
            com.muses.player.feature.scrape.ScrapeScreen(
                viewModel = scrapeVm,
                onBack = { backStack.pop() },
                onOpenReview = { songId ->
                    backStack.pushUnique(MusesRoute.ScrapeReview(songId))
                },
                // S3 逐首审核：带 queue 上下文进入审核页（「应用并下一首」推进用）
                onStartReviewQueue = { firstSongId, queue ->
                    backStack.pushUnique(
                        MusesRoute.ScrapeReview(firstSongId, queue.joinToString(",")),
                    )
                },
            )
        }
        // 单曲刮削审核页（Tagger 式就地审核，design §2.1）
        entry<MusesRoute.ScrapeReview>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            // 路由直传参数经 Koin parametersOf 供给 VM（miuix-nav 无 SavedStateHandle）
            val reviewVm: com.muses.player.feature.scrape.ScrapeReviewViewModel = koinViewModel(
                parameters = { parametersOf(route.songId, route.queueCsv) },
            )
            com.muses.player.feature.scrape.ScrapeReviewScreen(
                viewModel = reviewVm,
                onBack = { backStack.pop() },
                // S3「应用并下一首」：同步预览（剔除已写回者）→ 推进待审队列 → 打开下一首（结束则返回）
                onAppliedAndNext = { writtenSongId, _ ->
                    scrapeVm.refreshAfterExternalWriteback(writtenSongId)
                    val next = scrapeVm.advanceReview(writtenSongId)
                    if (next != null) {
                        val rest = scrapeVm.pendingReviewQueue.value.orEmpty()
                        // 对照原 popUpTo(REVIEW){inclusive} + navigate：同值替换（entry 重建，VM 随之重建）
                        backStack.remove(route)
                        backStack.add(MusesRoute.ScrapeReview(next, rest.joinToString(",")))
                    } else {
                        backStack.pop()
                    }
                },
                // S3 手动返回：清待审队列，不强推下一首
                onManualBack = { scrapeVm.cancelReviewQueue() },
            )
        }
        entry<MusesRoute.Sources> {
            SourcesScreen(
                onBack = { backStack.pop() },
                onOpenWebdavAdd = { backStack.pushUnique(MusesRoute.WebDavAdd) },
                onOpenWebdavEdit = { sourceId ->
                    backStack.pushUnique(MusesRoute.WebDavEdit(sourceId))
                },
                onOpenLxScripts = { backStack.pushUnique(MusesRoute.LxScripts) },
                onOpenLxAdd = { backStack.pushUnique(MusesRoute.LxSourceAdd) },
                onOpenLxEdit = { sourceId -> backStack.pushUnique(MusesRoute.LxSourceEdit(sourceId)) },
            )
        }
        entry<MusesRoute.LxSourceAdd>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            LxSourceFormScreen(
                onBack = { backStack.pop() },
                onSaved = { backStack.popTo(MusesRoute.Sources) },
            )
        }
        entry<MusesRoute.LxSourceEdit>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            LxSourceFormScreen(
                sourceId = route.sourceId,
                onBack = { backStack.pop() },
                onSaved = { backStack.popTo(MusesRoute.Sources) },
            )
        }
        entry<MusesRoute.LxScripts>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            LxScriptsScreen(onBack = { backStack.pop() })
        }
        entry<MusesRoute.Home> {
            HomeScreen(
                onOpenSearch = { backStack.pushUnique(MusesRoute.OnlineSearch()) },
                onOpenRecommendations = { backStack.pushUnique(MusesRoute.HomeCollection(true)) },
                onOpenCharts = { backStack.pushUnique(MusesRoute.HomeCollection(false)) },
                onOpenChart = { platform, chartId, title ->
                    backStack.pushUnique(MusesRoute.ChartDetail(platform, chartId, title))
                },
                onOpenPlaylist = { platform, playlistId, title ->
                    backStack.pushUnique(MusesRoute.PlaylistDetail(platform, playlistId, title))
                },
            )
        }
        entry<MusesRoute.HomeCollection>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            HomeCollectionScreen(
                showRecommendations = route.recommendations,
                onBack = { backStack.pop() },
                // 未配置 AI 服务时直接进入服务配置页。
                onOpenAiConfig = { backStack.pushUnique(MusesRoute.AiSettings) },
                onOpenChart = { platform, chartId, chartName ->
                    backStack.pushUnique(MusesRoute.ChartDetail(platform, chartId, chartName))
                },
            )
        }
        entry<MusesRoute.ChartDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            ChartDetailScreen(
                platform = route.platform,
                chartId = route.chartId,
                chartName = route.chartName,
                onBack = { backStack.pop() },
            )
        }
        entry<MusesRoute.PlaylistDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            ChartDetailScreen(
                platform = route.platform,
                chartId = route.playlistId,
                chartName = route.title,
                onBack = { backStack.pop() },
                isPlaylist = true,
            )
        }
        entry<MusesRoute.OnlineSearch> { route ->
            OnlineSearchScreen(
                onBack = { backStack.pop() },
                initialKeyword = route.keyword,
                onAlbumClick = { albumId -> backStack.pushUnique(MusesRoute.AlbumDetail(albumId)) },
                onArtistClick = { artistId -> backStack.pushUnique(MusesRoute.ArtistDetail(artistId)) },
            )
        }
        entry<MusesRoute.WebDavAdd>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            WebDavFormScreen(
                sourceId = null,
                onBack = { backStack.pop() },
            )
        }
        entry<MusesRoute.WebDavEdit>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            WebDavFormScreen(
                sourceId = route.sourceId,
                onBack = { backStack.pop() },
            )
        }
        entry<MusesRoute.Mine> {
            MineScreen(
                onOpenSources = { backStack.pushUnique(MusesRoute.Sources) },
                onOpenScrape = { backStack.pushUnique(MusesRoute.Scrape) },
                onOpenStatistics = { backStack.pushUnique(MusesRoute.Statistics) },
                onOpenHistory = { backStack.pushUnique(MusesRoute.History) },
                onOpenSettings = { backStack.pushUnique(MusesRoute.Settings) },
                onOpenDownloads = { backStack.pushUnique(MusesRoute.Downloads) },
                onOpenLibrary = { backStack.pushUnique(MusesRoute.LocalLibrary) },
            )
        }
        entry<MusesRoute.Statistics>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            StatisticsScreen(onBack = { backStack.pop() })
        }
        entry<MusesRoute.Downloads>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            com.muses.player.download.DownloadQueueScreen(onBack = { backStack.pop() })
        }
        entry<MusesRoute.History>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            HistoryScreen(onBack = { backStack.pop() })
        }
        entry<MusesRoute.Settings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            SettingsScreen(
                onBack = { backStack.pop() },
                onOpenAiSettings = { backStack.pushUnique(MusesRoute.AiSettings) },
                onOpenSyncSettings = { backStack.pushUnique(MusesRoute.SyncSettings) },
            )
        }
        entry<MusesRoute.AiSettings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            AiSettingsScreen(onBack = { backStack.pop() })
        }
        entry<MusesRoute.SyncSettings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            com.muses.player.sync.SyncSettingsScreen(onBack = { backStack.pop() })
        }
    }
}

/**
 * 滚动融合形态的底部胶囊（借鉴 Halcyon 的 `CompactBottomDock`）。
 *
 * 列表向下滚动时，展开态的两行（独立迷你条 + 悬浮导航栏）融合为**一行**：
 * `[当前 tab 图标] + [紧凑迷你播放器]`，点左侧图标展开回两行。
 *
 * 为什么保留迷你播放器而不换成更矮的紧凑版：Muses 的迷你条本身就是 64dp 悬浮胶囊，
 * 与导航 pill 同高，融合后仍是一行完整胶囊，无需再做第二套播放器组件。
 */
@Composable
private fun CompactPlayerDock(
    tabIcon: ImageVector,
    title: String,
    subtitle: String,
    coverUri: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasSong: Boolean,
    onExpand: () -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenQueue: () -> Unit,
    sideMargin: Dp,
    compactProgress: Float,
    playerTransitionProgress: Float,
    /** 上报迷你条在窗口中的矩形（用于悬浮底栏定位） */
    onPlayerBounds: (Rect) -> Unit,
) {
    val collapse = compactProgress.coerceIn(0f, 1f)
    // 借鉴 Halcyon：同一弹簧进度驱动图标缩放（连续插值，过渡不跳变）
    val iconScale = 1f - 0.14f * collapse
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sideMargin, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        // 左：当前 tab（点击展开回两行）。与分离态搜索钮共用 MusesDockActionPill，
        // 按压反馈（放大 + primary 叠底）天然一致，不会再出现默认水波纹的方形灰框。
        MusesDockActionPill(
            icon = tabIcon,
            label = "展开导航",
            selected = false,
            onClick = onExpand,
            iconSize = DockPillIconSize,
            iconScale = iconScale,
        )
        // 中：迷你播放器（复用既有组件，weight 占满剩余宽度）
        Box(Modifier.weight(1f)) {
            MiniPlayerBar(
                title = title,
                subtitle = subtitle,
                coverUri = coverUri,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                hasSong = hasSong,
                coverSize = 38.dp,
                onOpenPlayer = onOpenPlayer,
                onTogglePlayback = onTogglePlayback,
                onOpenQueue = onOpenQueue,
                // 紧凑模式优先为歌曲标题保留空间。
                showQueueButton = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - playerTransitionProgress }
                    .reportMiniBarBounds(onPlayerBounds),
            )
        }
        }
    }
}

/**
 * 融合胶囊两侧图标 pill 的**图标**尺寸（外框直接用 [MusesDockActionPill] 默认的 56dp，与迷你条同高）。
 *
 * 比底栏的 24dp 略收（22dp）：pill 里只有图标没有文字，24dp 在圆形里会显得撑。
 */
private val DockPillIconSize = 22.dp
