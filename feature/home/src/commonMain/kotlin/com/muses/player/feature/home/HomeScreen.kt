package com.muses.player.feature.home

import com.muses.player.core.search.performanceLabel
import com.muses.player.core.search.qualityLabel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.muses.player.core.ai.AiRecommendedTrack
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.ui.components.MusesButton
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesPullToRefresh
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.SongItem
import com.muses.player.core.ui.components.SongListItem
import com.muses.player.core.ui.components.SongListJumpFab
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.LocalSongMarqueeState
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.FabPosition
import com.muses.player.core.ui.components.MarqueeText as Text
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页入口的内容页：按入口展示排行榜或猜你喜欢。
 *
 * 交互约定：
 * - 排行榜：右上角选择有可用 LX 脚本的平台，点击榜单卡片进入歌曲子页；
 * - 猜你喜欢：AI 读曲库画像出「歌名+歌手」，再回平台精确匹配；未配置时给明确入口。
 */
@Composable
fun HomeCollectionScreen(
    showRecommendations: Boolean,
    onBack: () -> Unit,
    /** 跳 AI 服务二级页（地址/模型/Key 配置入口） */
    onOpenAiConfig: () -> Unit,
    /** 打开指定排行榜歌曲页 */
    onOpenChart: (platform: String, chartId: String, chartName: String) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val currentSongId = LocalSongMarqueeState.current.songId
    val scheme = MiuixTheme.colorScheme
    val chart = state.chart
    val chartCovers by viewModel.chartCovers.collectAsState()
    val recommend = state.recommend
    val platformNames = chart.platformNames
    var showPlatformPopup by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tracks = recommend.result?.tracks.orEmpty()
    val currentTrackIndex = remember(currentSongId, tracks) {
        tracks.indexOfFirst { it.result.toSong("online").id == currentSongId }
    }
    val currentTrackKey = tracks.getOrNull(currentTrackIndex)?.result?.let { "rec-${it.platform}-${it.songId}" }

    LaunchedEffect(showRecommendations) {
        if (showRecommendations) viewModel.refreshRecommend() else viewModel.loadCharts()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = if (showRecommendations) "猜你喜欢" else "排行榜",
                onBack = onBack,
                actions = {
                    if (!showRecommendations && chart.platforms.size > 1) {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            IconButton(
                                onClick = { showPlatformPopup = true },
                                minWidth = 40.dp,
                                minHeight = 40.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = (platformNames[chart.selectedPlatform] ?: chart.selectedPlatform.orEmpty())
                                            .removeSuffix("音乐").trimEnd(),
                                        style = MiuixTheme.textStyles.body1,
                                        color = scheme.onBackground,
                                    )
                                    Icon(
                                        imageVector = TablerIcons.ChevronDown,
                                        contentDescription = "切换音源",
                                        modifier = Modifier.size(20.dp),
                                        tint = scheme.onBackground,
                                    )
                                }
                            }
                            WindowListPopup(
                                show = showPlatformPopup,
                                alignment = PopupPositionProvider.Align.End,
                                enableWindowDim = true,
                                onDismissRequest = { showPlatformPopup = false },
                            ) {
                                ListPopupColumn {
                                    chart.platforms.forEachIndexed { index, platform ->
                                        DropdownImpl(
                                            text = (platformNames[platform] ?: platform).removeSuffix("音乐").trimEnd(),
                                            optionSize = chart.platforms.size,
                                            isSelected = platform == chart.selectedPlatform,
                                            index = index,
                                            onSelectedIndexChange = {
                                                viewModel.selectPlatform(platform)
                                                showPlatformPopup = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                bottomContent = {
                    val tracks = recommend.result?.tracks.orEmpty()
                    if (showRecommendations && tracks.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = viewModel::shuffleRecommend),
                                verticalAlignment = Alignment.CenterVertically) {
                                MusesIconButton(onClick = viewModel::shuffleRecommend) {
                                    Icon(TablerIcons.Shuffle, contentDescription = "随机播放推荐歌曲")
                                }
                                Text(tracks.size.toString(), style = MiuixTheme.textStyles.body1,
                                    color = scheme.onBackground)
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            SongListJumpFab(listState, currentTrackKey.takeIf { showRecommendations }) {
                // 推荐列表前可能有提示或配置，跳转时计入这些行。
                val headerCount = (if (state.message != null) 1 else 0) +
                    (if (!recommend.configured) 1 else 0) +
                    (if (recommend.error != null) 1 else 0)
                scope.launch { listState.animateScrollToItem(headerCount + currentTrackIndex) }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        val pageContent: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = if (showRecommendations) SongListLayout.contentHorizontalPadding else 16.dp,
                end = if (showRecommendations) SongListLayout.contentHorizontalPadding else 16.dp,
                bottom = 16.dp + LocalBottomChromePadding.current,
            ),
            verticalArrangement = Arrangement.spacedBy(if (showRecommendations) SongListLayout.itemSpacing else 14.dp),
        ) {
            // 一次性提示（如「该平台没有可用音源脚本」）
            state.message?.let { message ->
                item(key = "message") {
                    HomeBanner(text = message, onClose = viewModel::clearMessage)
                }
            }

            // ── 排行榜 ──
            if (!showRecommendations) {
            when {
                chart.loadingCharts && chart.charts.isEmpty() -> item(key = "chart-loading") {
                    LoadingRow()
                }
                chart.error != null -> item(key = "chart-error") {
                    HomeBanner(text = chart.error, onClose = null)
                }
                chart.charts.isEmpty() -> item(key = "chart-empty") {
                    MusesEmpty(title = "空空如也~")
                }
                else -> items(chart.charts.chunked(3), key = { "chart-row-${it.first().chartId}" }) { row ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth = (maxWidth - 24.dp) / 3
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { chartItem ->
                                ChartCard(
                                    chart = chartItem,
                                    width = cardWidth,
                                    fallbackCover = chartCovers["${chartItem.platform}:${chartItem.chartId}"],
                                    onCoverMissing = { failed -> viewModel.ensureChartCover(chartItem, failed) },
                                    onClick = {
                                        onOpenChart(chartItem.platform, chartItem.chartId, chartItem.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            }

            // ── 猜你喜欢 ──
            if (showRecommendations) {
            when {
                recommend.loading && recommend.result?.tracks.isNullOrEmpty() -> Unit
                !recommend.configured && recommend.result?.tracks.isNullOrEmpty() -> item(key = "rec-unconfigured") {
                    AiHintCard(
                        title = "配置 AI 服务",
                        description = "填写服务地址、模型和 API Key",
                        actionLabel = "去配置",
                        onAction = onOpenAiConfig,
                    )
                }
                recommend.error != null && recommend.result == null -> item(key = "rec-error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HomeBanner(text = recommend.error, onClose = null)
                        Spacer(Modifier.height(6.dp))
                        MusesButton(onClick = viewModel::retryRecommend) { Text("重试") }
                    }
                }
                recommend.result != null -> {
                    val result = recommend.result
                    if (!recommend.configured) item(key = "rec-configure-fill") {
                        AiHintCard(title = "配置 AI 服务", description = "配置完成后继续补充推荐",
                            actionLabel = "去配置", onAction = onOpenAiConfig)
                    }
                    if (recommend.error != null && result.tracks.isNotEmpty()) item(key = "rec-fill-error") {
                        Column {
                            HomeBanner(text = recommend.error, onClose = null)
                            MusesButton(onClick = viewModel::retryRecommend, enabled = !recommend.loading) { Text("重试补齐") }
                        }
                    }
                    if (result.tracks.isEmpty()) {
                        item(key = "rec-empty") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MusesEmpty(
                                    title = recommend.error ?: "暂时没有合适的推荐",
                                )
                                MusesButton(onClick = viewModel::retryRecommend, enabled = !recommend.loading) { Text("重试") }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = result.tracks,
                            key = { _, track: AiRecommendedTrack -> "rec-${track.result.platform}-${track.result.songId}" },
                        ) { index, track ->
                            val song = track.result
                            val playableSong = song.toSong("online")
                            SongListItem(
                                isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(playableSong),
                                song = SongItem(id = playableSong.id, title = song.name,
                                    artist = song.artist, albumTitle = song.album, coverUri = song.coverUrl),
                                isCurrent = playableSong.id == currentSongId,
                                showCover = true,
                                titleBadgeLabel = song.performanceLabel,
                                qualityBadgeLabel = song.qualityLabel,
                                onClick = { viewModel.playRecommend(index) },
                                trailingContent = {
                                    com.muses.player.core.ui.components.OnlineSongDownloadAction(
                                        playableSong, onDismissRecommendation = { viewModel.dismissRecommend(track) })
                                },
                            )
                        }
                    }
                }
            }

            }
            item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
        }
        }
        MusesPullToRefresh(
            isRefreshing = if (showRecommendations) recommend.loading else chart.loadingCharts || chart.refreshingCharts,
            onRefresh = {
                if (showRecommendations) viewModel.retryRecommend()
                else viewModel.loadCharts(forceRefresh = true)
            },
            modifier = Modifier.padding(padding),
            content = pageContent,
        )
    }
}

@Composable
private fun ChartCard(chart: OnlineChart, width: Dp, fallbackCover: String?, onCoverMissing: (Boolean) -> Unit, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    LaunchedEffect(chart.platform, chart.chartId, chart.coverUrl) { if (chart.coverUrl.isNullOrBlank()) onCoverMissing(false) }
    Column(modifier = Modifier.width(width).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.MusicNote, contentDescription = null, tint = scheme.onSurfaceVariantSummary, modifier = Modifier.size(30.dp))
            AsyncImage(model = fallbackCover ?: chart.coverUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { onCoverMissing(true) })
        }
        Spacer(Modifier.height(5.dp))
        Text(chart.name, fontSize = 13.sp, color = scheme.onSurface, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, marqueeEnabled = false)
        if (!chart.updateInfo.isNullOrBlank()) Text(
            text = chart.updateInfo.orEmpty(),
            fontSize = 11.sp,
            color = scheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            marqueeEnabled = false,
        )
    }
}

/** AI 未启用/未配置时的引导卡片 */
@Composable
private fun AiHintCard(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 15.sp, color = scheme.onSurface, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(text = description, fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
            }
            MusesButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 提示横幅；[onClose] 非空时可点关闭 */
@Composable
private fun HomeBanner(text: String, onClose: (() -> Unit)?) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.errorContainer)
            .then(if (onClose != null) Modifier.clickable(onClick = onClose) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TablerIcons.Info,
            contentDescription = null,
            tint = scheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = scheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (onClose != null) {
            Icon(
                imageVector = TablerIcons.Close,
                contentDescription = "关闭提示",
                tint = scheme.onErrorContainer,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** 居中加载指示器 */
@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
    }
}
