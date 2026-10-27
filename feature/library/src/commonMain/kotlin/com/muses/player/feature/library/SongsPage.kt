package com.muses.player.feature.library

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.muses.player.core.playback.PlaybackMeta
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.model.Song
import com.muses.player.core.model.libraryQualityLabel
import com.muses.player.core.ui.components.MusesSongActionsSheet
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.MusesCoverRadius
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesListRow
import com.muses.player.core.ui.components.MusesSnackbar
import com.muses.player.core.ui.components.MusesTextButton
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesRefreshableContent
import com.muses.player.core.ui.components.MusesRefreshablePlaceholder

/**
 * 歌曲页 —— SongsPage.vue 一比一翻译。
 *
 * 结构对照（BEM 类名见各段注释）：
 * - `.songs-page__navbar`：MusesTopBar(title=歌曲, right=搜索) + bottomContent
 *   （工具条 ↔ 搜索栏二选一，同一块玻璃无分界线）
 * - 工具条 `.songs-page__toolbar-left`：随机播放按钮 + 歌曲总数
 * - 列表行：MusesListRow(title, subtitle="artist - album",
 *   leading=封面，after=⋮ 三点菜单)
 * - 行点击：全列表入队播放该曲
 * - 空态 m-empty；⋮ 动作单 m-actions
 */
@Composable
fun SongsPage(
    /** 播放端口（U16：commonMain 感知端口而非安卓 PlayerConnection；null = 未接线） */
    playback: PlaybackPort?,
    /** M3：加入待刮削队列（经回调注入，feature:library 不直接依赖 core:scrape） */
    onEnqueueScrape: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 作为「曲库」Tab 的内容嵌入时为 false：顶栏（标题 + 搜索 + 工具栏）交由外部容器统一提供 */
    showTopBar: Boolean = true,
    viewModel: SongsViewModel = koinViewModel(),
    /** 专辑或艺术家详情传入自己的歌曲范围，复用歌曲页的全部交互。 */
    scopedSongs: List<Song>? = null,
    pageTitle: String = "歌曲",
    onBack: (() -> Unit)? = null,
    showSearch: Boolean = true,
    onRefresh: suspend () -> Unit = { viewModel.refresh() },
) {
    val scheme = MiuixTheme.colorScheme
    var searchQuery by remember { mutableStateOf("") }
    val librarySongs = if (scopedSongs == null) viewModel.songs.collectAsState().value else emptyList()
    val songs = remember(scopedSongs, librarySongs, searchQuery) {
        if (scopedSongs == null) librarySongs else {
            val query = searchQuery.trim()
            if (query.isEmpty()) scopedSongs else scopedSongs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    song.artist?.contains(query, ignoreCase = true) == true ||
                    song.album?.contains(query, ignoreCase = true) == true
            }
        }
    }
    // ---- 跳转到当前播放（SongsPage.vue scrollToCurrentSong/jump-fab 组）----
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 当前播放歌曲 id（playerState.currentSong.id；null = 未播放）
    val currentSongId: String? = playback?.currentSongId?.let { flow ->
        flow.collectAsState().value
    }
    val currentMeta by playback?.currentMeta?.collectAsState() ?: remember { mutableStateOf<PlaybackMeta?>(null) }

    // 列表滚动中防抖 300ms（对照 onListScroll/isListScrolling：滚动时隐藏气泡不挡更多按钮）
    var scrollSettled by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            scrollSettled = false
        } else {
            delay(300)
            scrollSettled = true
        }
    }

    // 歌曲 id → 下标映射：滚动帧与跳转查找 O(1)，避免每次全量 indexOfFirst
    val songIndex = remember(songs) { songs.mapIndexed { i, s -> s.id to i }.toMap() }

    // 当前歌曲「在列表 / 在可视区」（snapshotFlow 响应滚动帧；visibleItemsInfo 即真实可视区，无 overscan）
    val (currentInList, currentInViewport) = remember(currentSongId, songs, songIndex) {
        snapshotFlow {
            if (currentSongId == null) return@snapshotFlow false to false
            val idx = songIndex[currentSongId] ?: return@snapshotFlow false to false
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.find { it.index == idx }
                ?: return@snapshotFlow true to false
            val visible = item.offset < info.viewportEndOffset &&
                item.offset + item.size > info.viewportStartOffset
            true to visible
        }
    }.collectAsState(Pair(false, false)).value

    // showJumpBubble：在列表 && 滚出可视区 && 非滚动中
    val showJumpBubble = currentInList && !currentInViewport && scrollSettled

    // ---- 页面状态 ----
    var isSearching by remember { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    fun exitSearch() {
        isSearching = false
        searchQuery = ""
        if (scopedSongs == null) viewModel.updateSearchQuery("")
    }

    // 阶段二顶栏换原生：自绘 MusesNavbar 玻璃退役，haze 局部态随之下线；
    // FAB 改吃 TabsLayout 全局 Haze（环境值直达，无需中转）。
    // 阶段二槽位化：顶栏进 Scaffold topBar（原生大标题折叠），列表进 content，
    // FAB 进 floatingActionButton 槽（自动避让停靠迷你条）。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = scheme.surface,
        // 应用外层 Scaffold 管理系统栏；歌曲页的内容区不重复叠加一份系统栏 inset。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = pageTitle,
                onBack = onBack,
                visible = showTopBar,
                actions = {
                    if (showSearch) {
                        MusesIconButton(onClick = {
                            isSearching = true
                            searchQuery = ""
                        }) {
                            Icon(TablerIcons.Search, contentDescription = "搜索歌曲")
                        }
                    }
                },
                bottomContent = {
                    when {
                        songs.isNotEmpty() && !isSearching -> {
                            // .songs-page__toolbar-left：随机播放按钮 + 歌曲总数
                            // 随机播放全部：随机挑一首作为起点（对齐原版 onShuffleAll
                            // 「先 shuffle 再取随机第 0 首」——Media3 的 shuffleMode
                            // 只影响后续顺序、不改变当前曲，固定 first 会永远播第一首），
                            // 开 shuffle 保证后续顺序也随机
                            val shuffleAll: () -> Unit = {
                                if (songs.isNotEmpty()) {
                                    playback?.apply {
                                        play(songs.random().id, songs)
                                        setShuffleEnabled(true)
                                    }
                                }
                            }
                            // 左右边距与歌曲行封面左缘对齐（行内边距 16dp，见 MusesListRow）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 图标与歌曲数同属一个可点区域（用户定案：点数字同样触发随机播放）
                                Row(
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = shuffleAll,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MusesIconButton(onClick = shuffleAll) {
                                        Icon(TablerIcons.Shuffle, contentDescription = "随机播放全部")
                                    }
                                    Text(
                                        text = songs.size.toString(),
                                        style = MiuixTheme.textStyles.body1,
                                        color = scheme.onBackground,
                                    )
                                }
                            }
                        }

                        songs.isNotEmpty() && isSearching -> {
                            // .songs-page__searchbar（左右边距同工具栏，与歌曲行对齐）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    TablerIcons.Search,
                                    contentDescription = null,
                                    tint = scheme.onBackgroundVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "在 ${songs.size} 首歌曲中搜索",
                                            style = MiuixTheme.textStyles.body1,
                                            color = scheme.onBackgroundVariant,
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = {
                                            searchQuery = it
                                            if (scopedSongs == null) viewModel.updateSearchQuery(it)
                                        },
                                        singleLine = true,
                                        textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onBackground),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                MusesTextButton(text = "取消", onClick = { exitSearch() })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (showJumpBubble) {
                // 悬浮件浮在内容之上，miuix Scaffold 感知不到；FAB 手动抬升到 chrome 之上。
                // 定位源 = LocalBottomChromeElevation（迷你条可见顶的窗口坐标，壳层逐帧上报）
                // 与本 slot 实测底（窗口坐标）同系相减 → FAB 底 = 迷你条顶 - 16dp 呼吸，
                // 展开两件套 / 滚动融合一行两态都贴合（对齐 Halcyon：FAB bottom=176dp ≈
                // 其展开态 chrome 顶 + 少量呼吸）。不用节点总高 / 距屏底口径：FAB slot
                // 自带内缩（实测 12dp，与 WindowInsets 无关），距屏底口径会多空一截。
                // 列表 contentPadding 仍恒定（防滚动死角，见 BottomChrome）；
                var fabSlotBottom by remember { mutableStateOf(0f) }
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { coords ->
                        fabSlotBottom = coords.boundsInWindow().bottom
                    },
                ) {
                    val fabClearance = run {
                        val chromeTop = com.muses.player.core.ui.theme.LocalBottomChromeElevation.current.value
                        val slotBottom = with(LocalDensity.current) { fabSlotBottom.toDp() }
                        // 首帧 slot 还没上报时退回恒定口径，避免 clearance 归零压住 chrome
                        if (fabSlotBottom == 0f) {
                            com.muses.player.core.ui.theme.LocalBottomChromePadding.current + 16.dp
                        } else {
                            slotBottom - chromeTop + 16.dp
                        }
                    }
                    JumpToCurrentFab(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = fabClearance, end = 16.dp),
                    onClick = {
                        val idx = songIndex[currentSongId] ?: -1
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    },
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        MusesRefreshableContent(
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            if (songs.isEmpty()) {
                MusesRefreshablePlaceholder {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MusesEmpty(
                        title = "空空如也~",
                        bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
                    )
                }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize(),
                    state = listState,
                    // 末项避让底部悬浮件（悬浮件高度见 BottomChrome；空态分支不受影响）
                    contentPadding = PaddingValues(
                        start = SongListLayout.contentHorizontalPadding,
                        end = SongListLayout.contentHorizontalPadding,
                        bottom = 16.dp +
                            com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
                ) {
                itemsIndexed(songs, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { _, song ->
                    // 当前播放曲：标题用 primary 色区分。
                    val isCurrent = song.id == currentSongId
                    MusesListRow(
                        isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(song),
                        songLayout = true,
                        songId = song.id,
                        titleColor = if (isCurrent) scheme.primary else null,
                        subtitleColor = if (isCurrent) scheme.primary else null,
                        qualityBadgeLabel = song.libraryQualityLabel,
                        title = run {
                            val useMetaForTitle = song.id == currentSongId
                                && song.metaSources?.title == null
                                && song.tagsVersion < com.muses.player.core.data.db.SongTags.TAGS_VERSION
                            if (useMetaForTitle) currentMeta?.title?.trim()?.takeIf { it.isNotEmpty() } ?: song.title else song.title
                        },
                        subtitle = run {
                            val isCurrent = song.id == currentSongId
                            val useMetaArtist = isCurrent && song.metaSources?.artist == null && song.tagsVersion < com.muses.player.core.data.db.SongTags.TAGS_VERSION
                            val useMetaAlbum = isCurrent && song.metaSources?.album == null && song.tagsVersion < com.muses.player.core.data.db.SongTags.TAGS_VERSION
                            val metaArtist = if (useMetaArtist) currentMeta?.artist?.trim()?.takeIf { it.isNotEmpty() } else null
                            val metaAlbum = if (useMetaAlbum) currentMeta?.album?.trim()?.takeIf { it.isNotEmpty() } else null
                            "${metaArtist ?: song.artist ?: "未知艺术家"} - ${metaAlbum ?: song.album ?: "未知专辑"}"
                        },
                        // 歌曲行与榜单、歌单共用紧凑尺寸。
                        onClick = { playback?.play(song.id, songs) },
                        leading = {
                            val useMetaCover = song.id == currentSongId && song.metaSources?.cover == null && song.tagsVersion < com.muses.player.core.data.db.SongTags.TAGS_VERSION
                            val displayCover = if (useMetaCover) currentMeta?.coverUri ?: song.coverUri else song.coverUri
                            com.muses.player.core.ui.components.MusesCover(
                                uri = displayCover,
                                size = SongListLayout.coverSize,
                                radius = MusesCoverRadius.SM,
                            )
                            Spacer(Modifier.width(SongListLayout.coverGap))
                        },
                        after = {
                            com.muses.player.core.ui.components.SongPlaylistButton(song)
                            com.muses.player.core.ui.components.SongMoreButton(
                                onClick = { actionSong = song },
                            )
                        },
                        // Web 版 <m-list :dividers="false">：椒盐歌曲列表无行间分割线
                        dividers = false,
                    )
                }
            }
        }
    }


    // ---- ⋮ 动作单（m-actions）----
    MusesSongActionsSheet(
        songId = actionSong?.id,
        favoriteSong = actionSong,
        onDismiss = { actionSong = null },
        onEnqueueScrape = onEnqueueScrape,
        onAddToQueue = { id ->
            val song = songs.firstOrNull { it.id == id }
            if (playback == null || song == null) MusesSnackbar.show("添加到播放队列失败")
            else if (id in playback.queueSongIds.value) MusesSnackbar.show("已在播放队列中")
            else {
                playback.addToQueue(listOf(song))
                MusesSnackbar.show("添加成功")
            }
        },
    )

    }
}

/**
 * 跳转当前播放 FAB（`.songs-page__jump-fab` 一比一翻译）：
 * MFab 44px 圆底被页面样式覆盖为液态玻璃配方 —— glass-bg 半透明圆底（blur 由
 * 半透明底承担，同 navbar 策略）+ 顶部内高光 1px + 白色高光描边 + text-2 图标；
 * :active 底色 rgba(primary, 0.5)。
 */
@Composable
private fun JumpToCurrentFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // miuix 官方 FloatingActionButton（自带 shadowElevation 悬浮阴影），
    // 尺寸沿用原 44dp；surfaceVariant 基底延续原玻璃钮的浅色观感
    top.yukonga.miuix.kmp.basic.FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
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
