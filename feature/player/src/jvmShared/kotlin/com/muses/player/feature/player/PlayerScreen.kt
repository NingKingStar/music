package com.muses.player.feature.player

import com.muses.player.core.ui.components.MusesBottomSheet
import com.muses.player.core.ui.components.MusesDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.muses.player.core.playback.PlaybackStates
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Icon
import com.muses.player.core.ui.components.MarqueeText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.ui.components.PlayerControls
import com.muses.player.core.ui.components.LocalFavoriteSongs
import com.muses.player.core.ui.components.SongFavoriteButton
import com.muses.player.core.ui.components.PlayerCoverHero
import com.muses.player.core.ui.components.LocalPlayerArtworkMorph
import com.muses.player.core.ui.components.LocalPlayerContentColor
import com.muses.player.core.ui.components.PlayerModeBar
import com.muses.player.core.ui.components.PlayerProgress
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesListRow
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.components.MusesCoverRadius
import com.muses.player.feature.player.backdrop.FlowingLightBackdrop
import com.muses.player.feature.player.lyric.AmllLyricLine
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.feature.player.lyric.LyricsPanel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.abs
import top.yukonga.miuix.kmp.squircle.squircleBackground

/**
 * 沉浸式播放页 —— 一比一复刻 Capacitor PlayerPage.vue
 *
 * 复刻契约（BEM → Compose 映射，保留原类名以便对照）：
 * - .player-page__drag-layer：translateY(dragOffsetY) + is-dragging，无过渡跟手，松手 0.22s easeOut 回弹
 * - .player-page__bg / BackgroundRender：album/flowSpeed/hasLyric + fallback-background，opacity 0.75，粘性封面
 * - .player-page__song-head--fixed：标题/艺术家常驻（手机面板外），平板由 --in-panel 承担
 * - .panels：width 200% → translateX(-activePanel*50%)，0.22s easeOut，info-panel / lyric-panel 各 50%（平板收缩为 100% + 左右双栏）
 * - .player-page__cover-hero：aspect-ratio 1 正方形，max-height min(50vh,420px)，圆角 12
 * - .player-page__meta-window：displayedWindow 五行，当前行 scale 1.05 / 0.92，79px 视口，translateY -29.5 居中
 * - .progress-range + .player-page__time-row：m-range + 时间行
 * - .controls：三键 lg（48/28），.mode-bar 四键 md（40/20）max-w 320，无 is-active
 * - LyricPlayer：lyric-lines/current-time/align center 0.5 enableBlur/enableScale/wordFadeWidth 0.5，Fab 组 3s idle 隐藏
 * - .player-page__bottom-bar：平板全宽控制条（进度全宽 + 三段式按钮 spaceBetween）
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    viewModel: PlayerViewModel = koinViewModel(),
    onOpenQueue: () -> Unit = {},
    onOpenEditMeta: () -> Unit = {},
    /**
     * 下滑跟手中：上报收起进度（0 = 完全展开，1 = 已收起）。
     *
     * 非 null 时由宿主统一的动画进度驱动
     * 全屏播放页纵向位移，本页不再叠加自己的拖动位移。
     */
    onSeekCollapse: ((Float) -> Unit)? = null,
    /** 下滑松手：上报收起决定及最终拖动进度，让宿主从手指松开的位置接续动画。 */
    onSettleCollapse: ((Boolean, Float) -> Unit)? = null,
    /**
     * 内容显现进度：0 = 标题/歌词/控制区隐藏，1 = 完全显示。
     *
     * 封面不跟随此透明度，由同一面板进度单独缩放和移动。
     *
     * 传 lambda（内部才读 State），避免转场中每帧重组整棵沉浸页。
     */
    transitionProgress: () -> Float = { 1f },
    /** 面板收缩时非封面元素随顶部边界下移的像素距离。 */
    collapseOffsetY: () -> Float = { 0f },
    /** 封面上方标题与艺术家的收起渐隐进度。 */
    headingCollapseAlpha: () -> Float = { 1f },
    /** 背景在同一块面板中由迷你条底色过渡到沉浸页流光的进度。 */
    backgroundAlpha: () -> Float = { 1f },
    /** 宿主已持有的封面，供页面首次挂载时立即绘制转场封面。 */
    initialCoverUri: String? = null,
    initialCoverSongId: String? = null,
) {
    val settingsRepository = koinInject<SettingsRepository>()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.onPlaybackUiResumed()
    }
    val coverContentColorEnabled by settingsRepository.coverContentColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffleModeEnabled by viewModel.shuffleModeEnabled.collectAsStateWithLifecycle()
    val parsedLines by viewModel.parsedLines.collectAsStateWithLifecycle()
    val lyricsDocument by viewModel.lyricsDocument.collectAsStateWithLifecycle()
    val lyricsLoading by viewModel.lyricsLoading.collectAsStateWithLifecycle()
    val hasTranslation by viewModel.hasTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val lyricPosition by viewModel.lyricPosition.collectAsStateWithLifecycle()
    // 卡拉OK 逐词渐变需要逐帧位置：VM 的 ~100ms 轮询值作为锚点，UI 每帧线性外推
    val lyricPositionProvider = rememberLyricPositionProvider(
        positionFlow = viewModel.lyricPosition,
        isPlaying = isPlaying,
    )
    val stickyCover by viewModel.stickyCover.collectAsStateWithLifecycle()
    var contentColor by remember { mutableStateOf(Color.White) }
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()

    // U21：屏幕尺寸改用视口约束（见下方 BoxWithConstraints），原 LocalConfiguration 仅安卓可用

    // U12：标题/艺术家改由当前曲展示流（曲库实体与在线会话条目归一）；原 Media3 动态 ID3 标签与扫描标签同源，
    // 差异仅在未回写窗口期。注意：metaTitle/metaArtist 是来源标记（embedded/scrape…，见 Mappers 写入 wire 值），
    // 不是展示值！展示永远取 title/artist 本体（刮削写回已把刮削值写入本体，标记只做
    // 「是否被刮削过」的非空判断，见 mergeNowPlaying）。此前误把标记当名字展示，
    // 刮削完艺术家会显示成 "embedded"。
    val nowPlayingMeta by viewModel.nowPlayingMeta.collectAsStateWithLifecycle()
    val favoriteSongId by viewModel.currentSongId.collectAsStateWithLifecycle()
    val currentMeta = nowPlayingMeta?.takeIf { it.songId == favoriteSongId }
    val displayCover = if (currentMeta?.isOnline == true) stickyCover else if (currentMeta != null) stickyCover ?: initialCoverUri?.takeIf { initialCoverSongId == currentMeta.songId } else null
    val onlineSongs by com.muses.player.core.model.online.OnlineTrackSession.snapshot.collectAsStateWithLifecycle()
    val playbackSummary by viewModel.playbackSummary.collectAsStateWithLifecycle()
    val findOtherVersions = com.muses.player.core.ui.components.LocalFindOtherSongVersions.current
    val findVersions = {
        val song = com.muses.player.core.model.online.OnlineTrackSession.originalSong(favoriteSongId.orEmpty())
        if (song != null) findOtherVersions?.invoke(listOfNotNull(song.title, song.artist).joinToString(" "))
    }
    var showSourceDetails by remember { mutableStateOf(false) }
    if (showSourceDetails) MusesDialog(
        title = "播放来源", message = playbackSummary ?: "尚未取得实际播放来源",
        confirmText = "重试原来源", dismissText = "关闭",
        onDismiss = { showSourceDetails = false },
        onConfirm = { showSourceDetails = false; viewModel.retryOriginalSource() },
        content = {
            Text("仅自动替代可信同版。其他演唱者、现场或翻唱版本由你选择。")
            if (findOtherVersions != null) Text("查找其他版本（手动选择）",
                modifier = Modifier.clickable { showSourceDetails = false; findVersions() }.padding(12.dp))
        },
    )
    val favoriteSong = onlineSongs[favoriteSongId] ?: LocalFavoriteSongs.current.library.firstOrNull { it.id == favoriteSongId }
    val title = currentMeta?.title?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    val artist = currentMeta?.artist ?: ""
    val hasSong = currentMeta != null && title.isNotEmpty()

    // 拖动层状态（对齐 PlayerPage.vue drag-layer）
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDraggingVertically by remember { mutableStateOf(false) }
    // 歌词面板是否激活：垂直下滑仅 info-panel 生效（对齐 canStartVerticalDismiss → isLyricPanelTarget）
    var isLyricPanelActive by remember { mutableStateOf(false) }
    var lyricBounds by remember { mutableStateOf<Rect?>(null) }
    var isLyricAtTop by remember { mutableStateOf(false) }
    var dismissCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 回弹动画：0.22s easeOut（motion-v easeOut ≈ CubicBezier(0,0,0.58,1)）
    val reboundEasing = remember { CubicBezierEasing(0f, 0f, 0.58f, 1f) }

    fun startRebound(from: Float) {
        if (from <= 0f) return
        scope.launch {
            val anim = androidx.compose.animation.core.Animatable(from)
            anim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220, easing = reboundEasing),
            ) { dragOffsetY = value }
            dragOffsetY = 0f
            isDraggingVertically = false
        }
    }

    fun clearDragImmediate() {
        dragOffsetY = 0f
        isDraggingVertically = false
    }

    // 外层：m-popup 背景透明（对齐 .player-page__popup background: transparent !important）——
    // 无 scrim 黑化，drag-layer 下滑时直接漏出底下列表（原版 1:1）
    CompositionLocalProvider(
        com.muses.player.core.ui.components.LocalSelectPlaybackMode provides viewModel::selectPlaybackMode,
        com.muses.player.core.ui.components.LocalPlayerCoverFailure provides viewModel::onCoverLoadFailed,
        LocalPlaybackSourceDetails provides { showSourceDetails = true },
        LocalPlayerContentColor provides if (coverContentColorEnabled) contentColor else Color.White,
        LocalImmersiveFavoriteSong provides favoriteSong,
        com.muses.player.feature.player.lyric.LocalPlaybackLyricsRetry provides
            if (currentMeta?.isOnline == true) viewModel::retryLyrics else null,
        com.muses.player.feature.player.lyric.LocalPlaybackLyricsLoading provides lyricsLoading,
    ) {
        BoxWithConstraints(
        // clipToBounds：宿主在转场中平移整页，这里裁掉视口外内容，避免屏外绘制。
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        // U21：屏幕尺寸取自视口约束（原 LocalConfiguration 仅安卓可用）
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val isTabletLayout = screenWidth >= 768.dp && screenHeight < screenWidth
        val isNarrowHeight = screenHeight <= 520.dp

        // 下滑阈值：96~160，取 0.18*height 的 clamp（对齐 getDismissThreshold）
        val dismissThresholdPx = with(density) {
            val h = screenHeight.toPx()
            (h * 0.18f).coerceIn(96.dp.toPx(), 160.dp.toPx())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 用 offset 而非 graphicsLayer.translationY 做下滑跟手位移：
                // offset 无独立 RenderNode，绘制指令并入窗口根 display list，位移每帧
                // 触发全窗口重绘 → 下滑暴露的新区域必然重绘底层主页面列表；
                // graphicsLayer 纯位移只做合成、暴露区不重绘（露窗口底色，绿屏实验证实）
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                .onGloballyPositioned { dismissCoordinates = it }
                .then(
                    // 手势开始时歌词未到顶部则整次交给列表；顶部下滑与标题、封面下滑可收起。
                    Modifier.pointerInput(isTabletLayout, dismissThresholdPx) {
                        val bottomExclusionPx = with(density) { 180.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val coordinates = dismissCoordinates
                            val inLyrics = (isTabletLayout || isLyricPanelActive) &&
                                coordinates?.isAttached == true &&
                                lyricBounds?.contains(coordinates.localToWindow(down.position)) == true
                            // 按下时确定归属，滚到顶部途中不抢走当前歌词滚动。
                            if ((!inLyrics || isLyricAtTop) && down.position.y <= size.height - bottomExclusionPx) {
                                var totalX = 0f
                                var totalY = 0f
                                var accumulatedY = 0f
                                var dragging = false
                                var finished = false
                                var cancelled = false
                                val touchSlop = viewConfiguration.touchSlop

                                while (!finished && !cancelled) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        cancelled = true
                                        continue
                                    }
                                    val delta = change.positionChange()
                                    if (!dragging) {
                                        totalX += delta.x
                                        totalY += delta.y
                                        // 横向意图交给面板 Pager；只有明确向下越过 touch slop 才接管。
                                        if (abs(totalX) > touchSlop && abs(totalX) > abs(totalY)) {
                                            cancelled = true
                                            continue
                                        }
                                        if (totalY > touchSlop && totalY > abs(totalX)) {
                                            dragging = true
                                            isDraggingVertically = true
                                            accumulatedY = (totalY - touchSlop).coerceAtLeast(0f)
                                            change.consume()
                                        }
                                    } else {
                                        accumulatedY = (accumulatedY + delta.y).coerceAtLeast(0f)
                                        change.consume()
                                        if (onSeekCollapse != null) {
                                            onSeekCollapse((accumulatedY / size.height.coerceAtLeast(1)).coerceIn(0f, 0.999f))
                                        } else {
                                            dragOffsetY = accumulatedY
                                        }
                                    }
                                    finished = !change.pressed
                                }

                                if (dragging) {
                                    isDraggingVertically = false
                                    if (finished) {
                                        if (onSettleCollapse != null) {
                                            onSettleCollapse(
                                                accumulatedY >= dismissThresholdPx,
                                                (accumulatedY / size.height.coerceAtLeast(1)).coerceIn(0f, 0.999f),
                                            )
                                        } else if (accumulatedY >= dismissThresholdPx) {
                                            clearDragImmediate()
                                            onClose()
                                        } else if (accumulatedY > 0f) {
                                            val from = accumulatedY
                                            scope.launch {
                                                val anim = Animatable(from)
                                                anim.animateTo(0f, tween(220, easing = reboundEasing)) { dragOffsetY = value }
                                                dragOffsetY = 0f
                                            }
                                        }
                                    } else {
                                        if (onSettleCollapse != null) {
                                            onSettleCollapse(
                                                false,
                                                (accumulatedY / size.height.coerceAtLeast(1)).coerceIn(0f, 0.999f),
                                            )
                                        } else if (accumulatedY > 0f) {
                                            val from = accumulatedY
                                            scope.launch {
                                                val anim = Animatable(from)
                                                anim.animateTo(0f, tween(220, easing = reboundEasing)) { dragOffsetY = value }
                                                dragOffsetY = 0f
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
        ) {
            // 流光与迷你条底色共用宿主的一块面板，避免末尾叠出第二层胶囊。
            FlowingLightBackdrop(
                coverUri = displayCover,
                hasLyric = parsedLines.isNotEmpty(),
                modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer {
                    alpha = backgroundAlpha().coerceIn(0f, 1f)
                },
                flowSpeed = 2f,
                onContentColorChange = { contentColor = it },
            )
            var activePanel by remember { mutableStateOf(0) }
            LaunchedEffect(activePanel) { isLyricPanelActive = activePanel == 1 }
            if (isTabletLayout) {
                TabletImmersiveLayout(
                    title = title,
                    artist = artist,
                    sourceSummary = playbackSummary.orEmpty(),
                    coverUri = displayCover,
                    lines = parsedLines,
                    lyricsDocument = lyricsDocument,
                    lyricPosition = lyricPosition,
                    lyricPositionProvider = lyricPositionProvider,
                    hasTranslation = hasTranslation,
                    translationEnabled = translationEnabled,
                    onToggleTranslation = { viewModel.toggleTranslation() },
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleModeEnabled,
                    onSeek = { viewModel.seekTo(it); viewModel.onSeekEnd(it) },
                    onSeekStart = { viewModel.onSeekStart() },
                    onSeekEnd = { viewModel.onSeekEnd(it) },
                    onPlayPause = { viewModel.playPause() },
                    onPrevious = { viewModel.skipToPrevious() },
                    onNext = { viewModel.skipToNext() },
                    onToggleRepeat = {
                        val modes = com.muses.player.core.model.playback.PlaybackMode.entries
                        val current = com.muses.player.core.model.playback.PlaybackMode.from(viewModel.playback.playerConfig.value)
                        viewModel.selectPlaybackMode(modes[(current.ordinal + 1) % modes.size])
                    },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onOpenQueue = onOpenQueue,
                    onOpenEditMeta = onOpenEditMeta,
                    isNarrowHeight = isNarrowHeight,
                    maxWidth = screenWidth,
                    maxHeight = screenHeight,
                    onLyricBoundsChange = { lyricBounds = it },
                    onLyricAtTopChange = { isLyricAtTop = it },
                    transitionProgress = transitionProgress,
                    collapseOffsetY = collapseOffsetY,
                    headingCollapseAlpha = headingCollapseAlpha,
                )
            } else {
                PhoneImmersiveLayout(
                    title = title,
                    artist = artist,
                    sourceSummary = playbackSummary.orEmpty(),
                    coverUri = displayCover,
                    lines = parsedLines,
                    lyricsDocument = lyricsDocument,
                    lyricPosition = lyricPosition,
                    lyricPositionProvider = lyricPositionProvider,
                    hasTranslation = hasTranslation,
                    translationEnabled = translationEnabled,
                    onToggleTranslation = { viewModel.toggleTranslation() },
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleModeEnabled,
                    onSeek = { viewModel.seekTo(it); viewModel.onSeekEnd(it) },
                    onSeekStart = { viewModel.onSeekStart() },
                    onSeekEnd = { viewModel.onSeekEnd(it) },
                    onPlayPause = { viewModel.playPause() },
                    onPrevious = { viewModel.skipToPrevious() },
                    onNext = { viewModel.skipToNext() },
                    onToggleRepeat = {
                        val modes = com.muses.player.core.model.playback.PlaybackMode.entries
                        val current = com.muses.player.core.model.playback.PlaybackMode.from(viewModel.playback.playerConfig.value)
                        viewModel.selectPlaybackMode(modes[(current.ordinal + 1) % modes.size])
                    },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onOpenQueue = onOpenQueue,
                    onOpenEditMeta = onOpenEditMeta,
                    isNarrowHeight = isNarrowHeight,
                    maxWidth = screenWidth,
                    maxHeight = screenHeight,
                    onRequestClose = onClose,
                    dragOffsetY = dragOffsetY,
                    isDragging = isDraggingVertically,
                    onActivePanelChange = { activePanel = it },
                    onLyricBoundsChange = { lyricBounds = it },
                    onLyricAtTopChange = { isLyricAtTop = it },
                    transitionProgress = transitionProgress,
                    collapseOffsetY = collapseOffsetY,
                    headingCollapseAlpha = headingCollapseAlpha,
                )
            }
        }

        // 限流/播放错误条（深色浮层，播放页沉浸风格：常驻至手动关闭，带重试/关闭；
        // 一过性短提示走 MusesSnackbar，此处故意不用 Snackbar——自动消退会吞掉错误）
        if (playbackError != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .squircleBackground(Color(0xCC1A1A1A), 8.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(playbackError!!, color = Color.White, modifier = Modifier.weight(1f))
                    Text(
                        "重试",
                        color = Color.White,
                        modifier = Modifier.clickable(onClick = { viewModel.retryPlayback() }).padding(8.dp),
                    )
                    if (findOtherVersions != null) Text("其他版本", color = Color.White,
                        modifier = Modifier.clickable { findVersions() }.padding(8.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        TablerIcons.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp).clickable { viewModel.clearPlaybackError() },
                    )
                }
            }
        }
        }
    }
}

// ---------- 背景：BackgroundRender 复刻 + fallback-background ----------

/**
 * 背景层一比一复刻 Capacitor BackgroundRender
 * - album：封面 URI（对应 BackgroundRender :album）
 * - flowSpeed：2（固定，MeshGradientRenderer 流速）
 * - hasLyric：是否有歌词（影响背景渲染参数，Compose 侧暂仅作语义保留，背景始终渲染）
 * - fallback-background：无封面时深色占位（#05070D 纵向渐变），hidden 时 opacity 0
 */
@Composable
private fun PlayerBackground(
    coverUri: String?,
    hasLyric: Boolean,
    modifier: Modifier = Modifier,
) {
    // hasLyric 语义保留：供未来 MeshGradient 强度调节，当前 Compose 侧背景渲染一致
    @Suppress("UNUSED_PARAMETER") val _hasLyric = hasLyric
    Box(modifier = modifier.background(Color(0xFF05070D))) {
        // fallback-background：始终存在，showAlbumBackground 时 --hidden opacity 0
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A1E2E), Color(0xFF0D0F1A), Color(0xFF05070D)),
                    ),
                )
                .alpha(if (!coverUri.isNullOrBlank()) 0f else 1f),
        )
        if (!coverUri.isNullOrBlank()) {
            // BackgroundRender：album 模糊铺满，opacity 0.75，flowSpeed 2 模拟为静态模糊 + 渐变
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = 1.08f, scaleY = 1.08f)
                    .blur(28.dp)
                    .alpha(0.75f),
            )
            // 渐变遮罩保证前景可读（顶部浅、底部深）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color(0xFF05070D).copy(alpha = 0.55f),
                                Color(0xFF05070D).copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .alpha(0.75f),
            )
            // 顶部光晕（径向高光，非纯黑证明）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                            center = Offset(0.5f * 1080f, 0.28f * 1920f),
                            radius = 900f,
                        ),
                    )
                    .alpha(0.75f),
            )
        }
    }
}

// ---------- 手机布局：固定头部 + 双面板 0.22s easeOut ----------

/** 打开时封面先飞到正封，标题、歌词与控制区再按宿主进度淡入。 */
private fun contentAlphaFor(progress: Float): Float = progress.coerceIn(0f, 1f)

@Composable
private fun PhoneImmersiveLayout(
    title: String,
    artist: String,
    sourceSummary: String = "",
    coverUri: String?,
    lines: List<AmllLyricLine>,
    lyricsDocument: LyricsDocument?,
    lyricPosition: Long,
    lyricPositionProvider: () -> Int,
    hasTranslation: Boolean,
    translationEnabled: Boolean,
    onToggleTranslation: () -> Unit,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    isNarrowHeight: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    onRequestClose: () -> Unit,
    dragOffsetY: Float,
    isDragging: Boolean,
    onActivePanelChange: (Int) -> Unit = {},
    onLyricBoundsChange: (Rect) -> Unit = {},
    onLyricAtTopChange: (Boolean) -> Unit = {},
    /** 转场进度（见 [PlayerScreen]）：驱动「非封面内容」渐隐 */
    transitionProgress: () -> Float = { 1f },
    collapseOffsetY: () -> Float = { 0f },
    headingCollapseAlpha: () -> Float = { 1f },
) {
    var activePanel by remember { mutableStateOf(0) }
    // 进度条手势进行中时禁用 pager 横滑：杜绝 seek 拖动被当成切页（由 ProgressSection.onSeekDragActive 驱动）
    var isSeekDragging by remember { mutableStateOf(false) }
    // HorizontalPager 替代 Row 200% 以避免 TabsLayout 半屏约束导致的半宽偏移
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 }, initialPage = activePanel)
    LaunchedEffect(activePanel) {
        if (pagerState.currentPage != activePanel) {
            try { pagerState.animateScrollToPage(activePanel, animationSpec = tween(durationMillis = 220, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))) } catch (_: Exception) {}
        }
        onActivePanelChange(activePanel)
    }
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != activePanel) {
            activePanel = pagerState.currentPage
            onActivePanelChange(activePanel)
        }
    }

    // 「非封面内容」统一可见度：标题 / 歌词 / 控制都据此渐隐，
    // 而封面（InfoPanel 里的 CoverHero）不受影响——它走 share element，由宿主插值 bounds。
    // 注意用 graphicsLayer 且**延迟读进度**（不在此处取值，否则每帧重组整棵页面）。
    val headingTransform = Modifier.graphicsLayer {
        translationY = collapseOffsetY()
        alpha = contentAlphaFor(transitionProgress()) * headingCollapseAlpha()
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        // 固定头部：player-page__song-head--fixed（手机常驻，平板隐藏，此处仅手机分支）
        // 顶部避让 calc(16px + safe-area) 左右 24px，平板隐藏
        FixedSongHead(
            title = title,
            artist = artist,
            summary = sourceSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 0.dp)
                .then(headingTransform),
        )

        // 已移除手机端额外小圆点指示器：对齐 Capacitor 原版无指示器（PRD R7 1:1）

        // 面板容器：改用 HorizontalPager 以确保单屏全宽
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            beyondViewportPageCount = 0,
            userScrollEnabled = !isSeekDragging,
        ) { page ->
            when (page) {
                0 -> InfoPanel(
                    coverUri = coverUri,
                    lines = lines,
                    lyricPosition = lyricPosition,
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    onSeek = onSeek,
                    onSeekStart = onSeekStart,
                    onSeekEnd = onSeekEnd,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onOpenQueue = onOpenQueue,
                    onOpenEditMeta = onOpenEditMeta,
                    isNarrowHeight = isNarrowHeight,
                    isTablet = false,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    onSeekDragActive = { isSeekDragging = it },
                    contentAlpha = { contentAlphaFor(transitionProgress()) },
                )
                1 -> LyricsPanel(
                    document = lyricsDocument,
                    positionMs = lyricPosition,
                    isPlaying = isPlaying,
                    onSeek = onSeek,
                    onAtTopChange = onLyricAtTopChange,
                    // 歌词面板整体都是「非封面内容」，直接整块渐隐
                    modifier = Modifier.onGloballyPositioned { onLyricBoundsChange(it.boundsInWindow()) }
                        .padding(horizontal = 24.dp).graphicsLayer {
                        translationY = collapseOffsetY()
                        alpha = contentAlphaFor(transitionProgress())
                    },
                )
            }
        }
    }
}

@Composable
private fun TabletImmersiveLayout(
    title: String,
    artist: String,
    sourceSummary: String = "",
    coverUri: String?,
    lines: List<AmllLyricLine>,
    lyricsDocument: LyricsDocument?,
    lyricPosition: Long,
    lyricPositionProvider: () -> Int,
    hasTranslation: Boolean,
    translationEnabled: Boolean,
    onToggleTranslation: () -> Unit,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    isNarrowHeight: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    onLyricBoundsChange: (Rect) -> Unit = {},
    onLyricAtTopChange: (Boolean) -> Unit = {},
    /** 转场进度（见 [PlayerScreen]）：驱动「非封面内容」渐隐 */
    transitionProgress: () -> Float = { 1f },
    collapseOffsetY: () -> Float = { 0f },
    headingCollapseAlpha: () -> Float = { 1f },
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 平板不渲染固定头部，由面板内头部承担
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 左栏：info-panel（50%）含面板内头部 + 封面 hero（居中），不含三行小窗与手机控件区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 面板内头部：player-page__song-head--in-panel（仅平板显示）
                FixedSongHead(
                    title = title,
                    artist = artist,
                    summary = sourceSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp)
                        .graphicsLayer {
                            translationY = collapseOffsetY()
                            alpha = contentAlphaFor(transitionProgress()) * headingCollapseAlpha()
                        },
                )
                // 封面居中（平板 info-inner justify-content center）：CoverHero 响应式 min(50vh,420) contain
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverHero(
                        coverUri = coverUri,
                        screenHeight = maxHeight,
                        screenWidth = maxWidth,
                        isNarrowHeight = isNarrowHeight,
                    )
                }
                // 平板左侧不展示三行歌词与手机控件区（display:none），由底部条承担
                Spacer(Modifier.height(8.dp))
            }
            // 右栏：lyric-panel（50%）右侧歌词 — Immersive  面板，header 在平板隐藏
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { onLyricBoundsChange(it.boundsInWindow()) }
                    .graphicsLayer {
                        translationY = collapseOffsetY()
                        alpha = contentAlphaFor(transitionProgress())
                    },
            ) {
                // 歌词面板（V2 范围外，不动）：NativeLyricsPanel 手势分流保持原生
                LyricsPanel(
                    document = lyricsDocument,
                    positionMs = lyricPosition,
                    isPlaying = isPlaying,
                    onSeek = onSeek,
                    onAtTopChange = onLyricAtTopChange,
                )
            }
        }
        // 底部全宽控制条：player-page__bottom-bar（仅平板，flex none，z 10，渐变背景）
        Box(Modifier.graphicsLayer {
            alpha = contentAlphaFor(transitionProgress())
        }) {
            TabletBottomBar(
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                onSeekStart = onSeekStart,
                onSeekEnd = onSeekEnd,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleRepeat = onToggleRepeat,
                onToggleShuffle = onToggleShuffle,
                onOpenQueue = onOpenQueue,
                onOpenEditMeta = onOpenEditMeta,
                screenWidth = maxWidth,
                screenHeight = maxHeight,
            )
        }
    }
}

// ---------- 固定头部：player-page__song-head--fixed / --in-panel ----------

private val LocalPlaybackSourceDetails = staticCompositionLocalOf<(() -> Unit)?> { null }
private val LocalImmersiveFavoriteSong = staticCompositionLocalOf<com.muses.player.core.model.Song?> { null }

@Composable
private fun FixedSongHead(
    title: String,
    artist: String,
    summary: String = "",
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalPlayerContentColor.current
    val showSourceDetails = LocalPlaybackSourceDetails.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                color = contentColor.copy(alpha = 0.95f),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp, // 0.01em × 20px
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 26.sp,
            )
            if (artist.isNotEmpty()) {
                Text(
                    text = artist,
                    color = contentColor.copy(alpha = 0.6f),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 来源与音质：只展示可核实信息，脚本声明与实测严格区分（见 playbackDetailSummary）
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    color = contentColor.copy(alpha = 0.45f),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 1.dp).clickable { showSourceDetails?.invoke() },
                )
            }
        }
        LocalImmersiveFavoriteSong.current?.let { song ->
            SongFavoriteButton(song, likedTint = contentColor, unlikedTint = contentColor.copy(alpha = 0.6f))
            com.muses.player.core.ui.components.SongPlaylistButton(song)
        }
    }
}

// ---------- 信息面板：info-panel（手机：含三行小窗+控制，平板：仅封面） ----------

@Composable
private fun InfoPanel(
    coverUri: String?,
    lines: List<AmllLyricLine>,
    lyricPosition: Long,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    isNarrowHeight: Boolean,
    isTablet: Boolean,
    maxWidth: Dp = 360.dp,
    maxHeight: Dp = 800.dp,
    onSeekDragActive: (Boolean) -> Unit = {},
    /**
     * 「非封面内容」的可见度 lambda（传 lambda 不传值：转场中每帧都要重算，
     * 而取值的调用点在 graphicsLayer 里，不会触发重组）。
     */
    contentAlpha: () -> Float = { 1f },
) {
    // info-panel：panel padding calc(16+safe) 24 16（对齐 .player-overlay .panel）；
    // info-inner gap 14、padding-top 16、song-meta margin-bottom 18（对齐 .info-panel-inner）
    // 断点收紧（对齐全局 media query）：≤720 gap 4、≤520 gap 2
    val shortHeight = maxHeight <= 720.dp
    val artworkMorph = LocalPlayerArtworkMorph.current
    var controlsTopInWindow by remember { mutableFloatStateOf(Float.NaN) }
    val coverClearancePx = with(LocalDensity.current) { 8.dp.toPx() }
    val coverRevealFadePx = with(LocalDensity.current) { 18.dp.toPx() }
    val innerGap = when {
        isNarrowHeight -> 2.dp
        shortHeight -> 4.dp
        else -> 14.dp
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        // info-inner overflow: hidden（对齐 .player-page__info-inner）——页面不可滚动，封面弹性吃满剩余空间
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isTablet) Arrangement.Center else Arrangement.Bottom,
    ) {
        // 平板已在外层渲染头部，此处不再重复；手机版头部由 fixed 承担，此处不渲染 in-panel
        Spacer(Modifier.height(16.dp)) // info-inner padding-top 16
        // 封面 hero：弹性占满剩余空间（对齐 cover-hero flex:1 1 auto；min-height 0 收缩），
        // 上限 min(50vh,420px)，窄屏 34vw/150 限制通过 CoverHero 内部计算
        CoverHero(
            coverUri = coverUri,
            screenHeight = maxHeight,
            screenWidth = maxWidth,
            isNarrowHeight = isNarrowHeight,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(innerGap))
        // 手机控件区：player-page__info-controls（平板 display:none，由底部条承担）
        // 控件保持在全屏布局原位；封面经过时用短渐变柔和隐藏重叠部分。
        if (!isTablet) {
            // horizontalAlignment 必须显式居中：这层是转场渐隐包裹 Column，
            // 外层 InfoPanel 的 CenterHorizontally 不会传导给子级布局参数，
            // 缺省 Start 会让 wrap-content 的 PlayerControls 贴左、与
            // fillMaxWidth 的进度条错位（MuMu 实测三键组中心 331 vs 540）。
            Column(
                modifier = Modifier
                    .onGloballyPositioned { controlsTopInWindow = it.boundsInWindow().top }
                    .graphicsLayer {
                        alpha = contentAlpha()
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val coverBottom = artworkMorph?.currentBounds()?.bottom
                        if (coverBottom != null && !controlsTopInWindow.isNaN()) {
                            val fadeStart = coverBottom + coverClearancePx - controlsTopInWindow
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black),
                                    startY = fadeStart,
                                    endY = fadeStart + coverRevealFadePx,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressSection(
                    position = position,
                    duration = duration,
                    onSeekStart = onSeekStart,
                    onSeekEnd = onSeekEnd,
                    screenHeight = maxHeight,
                    onSeekDragActive = onSeekDragActive,
                )
                Spacer(Modifier.height(innerGap))
                ControlsRow(
                    isPlaying = isPlaying,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    screenWidth = maxWidth,
                    screenHeight = maxHeight,
                )
                Spacer(Modifier.height(innerGap))
                ModeBarRow(
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onOpenQueue = onOpenQueue,
                    onOpenEditMeta = onOpenEditMeta,
                    screenHeight = maxHeight,
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------- 非歌词部分已上收 ui-shared（V2）： ----------
// CoverHero → PlayerCoverHero / ProgressSection → PlayerProgress /
// ControlsRow → PlayerControls / ModeBarRow → PlayerModeBar。
// 安卓保留同名私有适配壳（断点 gap/barHeight 计算 + 参数映射），
// 手势铁律与渲染实现只在 ui-shared commonMain 维护一份。

@Composable
private fun CoverHero(
    coverUri: String?,
    modifier: Modifier = Modifier,
    screenHeight: Dp = 800.dp,
    screenWidth: Dp = 360.dp,
    isNarrowHeight: Boolean = false,
) {
    PlayerCoverHero(
        coverUri = coverUri,
        modifier = modifier,
        screenHeight = screenHeight,
        screenWidth = screenWidth,
        isNarrowHeight = isNarrowHeight,
    )
}

@Composable
private fun ProgressSection(
    position: Long,
    duration: Long,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    screenHeight: Dp,
    // 进度条手势活跃状态：按下即 true（tap/拖动均算），抬手/取消即 false。
    // 手机布局据此禁用 HorizontalPager 横滑，杜绝 seek 拖动被当成切页。
    onSeekDragActive: (Boolean) -> Unit = {},
) {
    val barHeight = if (screenHeight <= 520.dp) 18.dp else 20.dp
    PlayerProgress(
        positionMs = position,
        durationMs = duration,
        onSeekStart = onSeekStart,
        onSeekEnd = onSeekEnd,
        barHeight = barHeight,
        onSeekDragActive = onSeekDragActive,
    )
}

@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    screenWidth: Dp,
    screenHeight: Dp,
    compact: Boolean = false,
) {
    // controls：三键 lg（48/28），gap clamp(24,10vw,44)；矮屏断点收紧（≤720 clamp(12,4vw,20)、≤520 clamp(10,3.5vw,16)）
    val gap = if (compact) 12.dp else {
        when {
            screenHeight <= 520.dp -> (screenWidth * 0.035f).coerceIn(10.dp, 16.dp)
            screenHeight <= 720.dp -> (screenWidth * 0.04f).coerceIn(12.dp, 20.dp)
            else -> (screenWidth * 0.10f).coerceIn(24.dp, 44.dp)
        }
    }
    PlayerControls(
        isPlaying = isPlaying,
        onPrevious = onPrevious,
        onPlayPause = onPlayPause,
        onNext = onNext,
        compact = compact,
        gap = gap,
    )
}

@Composable
private fun ModeBarRow(
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    screenHeight: Dp,
) {
    // mode-bar：max-width 320（≤720 收 280、≤520 收 260），space-between，无 is-active（仅图标对 + aria-label）
    val modeMaxWidth = when {
        screenHeight <= 520.dp -> 260.dp
        screenHeight <= 720.dp -> 280.dp
        else -> 320.dp
    }
    PlayerModeBar(
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        onToggleRepeat = onToggleRepeat,
        onToggleShuffle = onToggleShuffle,
        onOpenQueue = onOpenQueue,
        onOpenEditMeta = onOpenEditMeta,
        maxWidth = modeMaxWidth,
    )
}

// ---------- 平板底部控制条：player-page__bottom-bar ----------

@Composable
private fun TabletBottomBar(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    screenWidth: Dp,
    screenHeight: Dp,
) {
    val contentColor = LocalPlayerContentColor.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0x0005070D), Color(0x8C05070D)),
                ),
            )
            .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp)
            .navigationBarsPadding(),
    ) {
        // 进度全宽：bottom-progress
        ProgressSection(
            position = position,
            duration = duration,
            onSeekStart = onSeekStart,
            onSeekEnd = onSeekEnd,
            screenHeight = screenHeight,
        )
        Spacer(Modifier.height(2.dp))
        // 三段式：left mode + center controls + right mode，space-between，中组居中于屏幕中心
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.muses.player.core.ui.components.PlaybackModeButton(repeatMode, shuffleEnabled, onToggleRepeat)
            }
            PlayerControls(
                isPlaying = isPlaying,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                compact = true,
                gap = (screenWidth * 0.05f).coerceIn(20.dp, 44.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                MusesIconButton(onClick = onOpenQueue, imageVector = TablerIcons.QueueMusic, contentDescription = "播放队列", tint = contentColor.copy(alpha = 0.8f))
                MusesIconButton(onClick = onOpenEditMeta, imageVector = TablerIcons.MoreHorizontal, contentDescription = "更多", tint = contentColor.copy(alpha = 0.8f))
            }
        }
    }
}

// ---------- 工具 ----------

/**
 * 歌词逐帧进度源：为卡拉OK 渲染器提供 () -> Int 的播放位置（ms）。
 *
 * 为什么不能直接把 VM 的 lyricPosition 传进去：
 * - VM 轮询粒度 ~100ms，仅够驱动「整词二段高亮」；逐词渐变需要 60fps 连续进度，否则填充边缘会跳变
 * - 若把 lyricPosition 作为 State 在 Compose 层读取，每 100ms 会重组整棵歌词 LazyColumn（旧实现即如此）
 *
 * 做法（对齐 lyrics-ui 官方 sample 的 awaitFrame 模式）：
 * - 锚点：VM 轮询值，在 LaunchedEffect 内 collect（协程内收集不触发重组），记入普通字段
 * - 外推：每帧 withFrameMillis 以「锚点 + 帧时间差」线性推算，写 MutableLongState
 * - 只有渲染器的 Canvas DrawScope 会读取该 State → 仅触发绘制失效，不触发重组
 */
@Composable
private fun rememberLyricPositionProvider(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
): () -> Int {
    val animatedPosition = remember { mutableLongStateOf(0L) }
    // 锚点用普通字段：避免被 Compose 快照记录从而引发重组
    val clock = remember { LyricClock() }
    val playingState = rememberUpdatedState(isPlaying)

    LaunchedEffect(positionFlow) {
        positionFlow.collect { clock.anchorPositionMs = it }
    }

    LaunchedEffect(Unit) {
        var lastAnchor = -1L
        while (true) {
            withFrameMillis { frameTimeMs ->
                val base = clock.anchorPositionMs
                if (base != lastAnchor) {
                    // VM 给出新锚点（~100ms 一次 / seek 后立即），重置外推起点
                    lastAnchor = base
                    clock.anchorFrameMs = frameTimeMs
                }
                animatedPosition.longValue = if (playingState.value) {
                    base + (frameTimeMs - clock.anchorFrameMs).coerceAtLeast(0L)
                } else {
                    base
                }
            }
        }
    }

    return remember { { animatedPosition.longValue.toInt() } }
}

/** 逐帧外推锚点：[anchorPositionMs] 为 VM 轮询位置，[anchorFrameMs] 为收到该锚点时的帧时刻 */
private class LyricClock {
    @Volatile var anchorPositionMs: Long = 0L
    @Volatile var anchorFrameMs: Long = 0L
}

// 注：computeCurrentIndex/formatTime 已随 V2 上收到 ui-shared（formatPlayerTime），
// 本文件残留版本已删除；SimpleLyricsPanel 自带同名私有实现，不受影响。

// ---------- 队列弹窗（底部 popup：遮罩 + 上滑进入 + 顶部圆角，内容沿用原队列页） ----------

@Composable
fun QueueScreen(
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val queue by viewModel.queueRows.collectAsStateWithLifecycle()
    val currentId by viewModel.currentSongId.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentIndex = queue.indexOfFirst { it.songId == currentId }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollSettled by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            scrollSettled = false
        } else {
            delay(300)
            scrollSettled = true
        }
    }
    val showJumpButton by remember(currentIndex) {
        derivedStateOf {
            currentIndex >= 0 && scrollSettled && listState.layoutInfo.visibleItemsInfo.none {
                it.index == currentIndex &&
                    it.offset < listState.layoutInfo.viewportEndOffset &&
                    it.offset + it.size > listState.layoutInfo.viewportStartOffset
            }
        }
    }

    if (showClearConfirmation) {
        MusesDialog(
            onDismiss = { showClearConfirmation = false },
            title = "清空播放队列",
            message = "确定清空播放队列吗？",
            dismissText = "取消",
            confirmText = "确定",
            confirmEnabled = queue.isNotEmpty(),
            onConfirm = {
                showClearConfirmation = false
                viewModel.clearQueue()
            },
        )
    }

    // 统一封装：miuix OverlayBottomSheet（遮罩/圆角/动画走官方）
    MusesBottomSheet(
        onDismiss = onClose,
        title = "播放队列",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f),
        ) {
            // 当前曲在实际播放队列中的位置与清空操作。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${currentIndex + 1} / ${queue.size}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                MusesIconButton(
                    onClick = { showClearConfirmation = true },
                    enabled = queue.isNotEmpty(),
                    contentDescription = "清空队列",
                ) {
                    Text(
                        text = "清除",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                    )
                }
            }

            if (queue.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("空空如也~", color = scheme.onBackground.copy(alpha = 0.6f))
                }
            } else {
                // 底部内边距 16dp：弹窗内无迷你条，原 96dp 预留不再需要
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(
                            start = SongListLayout.contentHorizontalPadding,
                            end = SongListLayout.contentHorizontalPadding,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
                    ) {
                        itemsIndexed(queue, key = { _, item -> item.songId }, contentType = { _, _ -> "queue" }) { index, item ->
                            val isCurrent = index == currentIndex
                            MusesListRow(
                                songLayout = true,
                                songId = item.songId,
                                marqueeEnabled = isCurrent && isPlaying,
                                title = item.title,
                                subtitle = "${item.artist ?: "未知艺术家"} - ${item.album ?: "未知专辑"}",
                                titleColor = if (isCurrent) scheme.primary else null,
                                subtitleColor = if (isCurrent) scheme.primary else null,
                                dividers = false,
                                onClick = { viewModel.playAtIndex(index) },
                                leading = {
                                    MusesCover(uri = item.coverUri, size = com.muses.player.core.ui.components.SongListLayout.coverSize, radius = MusesCoverRadius.SM)
                                    Spacer(Modifier.width(com.muses.player.core.ui.components.SongListLayout.coverGap))
                                },
                                after = {
                                    MusesIconButton(
                                        onClick = { viewModel.removeQueueItemAt(index) },
                                        imageVector = TablerIcons.Close,
                                        contentDescription = "从队列移除",
                                        size = MusesIconButtonSize.SM,
                                        tint = scheme.onBackgroundVariant,
                                    )
                                },
                            )
                        }
                    }
                    if (showJumpButton) {
                        top.yukonga.miuix.kmp.basic.FloatingActionButton(
                            onClick = {
                                if (currentIndex in queue.indices) {
                                    scope.launch { listState.animateScrollToItem(currentIndex) }
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            minWidth = 44.dp,
                            minHeight = 44.dp,
                            containerColor = scheme.surfaceVariant,
                        ) {
                            Icon(
                                TablerIcons.MyLocation,
                                contentDescription = "跳转到当前播放",
                                tint = scheme.onBackgroundVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
