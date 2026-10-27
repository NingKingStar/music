package com.muses.player.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.muses.player.core.search.OnlinePlaylist
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.ui.components.LocalFavoriteSongs
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesPullToRefresh
import com.muses.player.core.ui.components.MarqueeText as Text
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/** 探索首页：平台推荐歌单、AI 猜你喜欢和主要榜单。 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenRecommendations: () -> Unit,
    onOpenCharts: () -> Unit,
    onOpenChart: (platform: String, chartId: String, title: String) -> Unit,
    onOpenPlaylist: (platform: String, playlistId: String, title: String) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val scheme = MiuixTheme.colorScheme
    val state by viewModel.state.collectAsState()
    val preview by viewModel.recommendationPreview.collectAsState()
    val favorites = LocalFavoriteSongs.current
    val previewTracks = preview.filter { !favorites.isInLibrary(it.toSong("online")) }.take(3)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(viewModel, lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            viewModel.loadRecommendedPlaylists()
            viewModel.loadCharts()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar(title = "探索", bottomContent = {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).homeCardClick(onOpenSearch).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(TablerIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("搜索歌曲、歌手、专辑", color = scheme.onBackgroundVariant, fontSize = 15.sp)
                }
            }
        }) },
    ) { padding ->
        MusesPullToRefresh(
            isRefreshing = state.featured.loading || state.chart.loadingCharts || state.chart.refreshingCharts,
            onRefresh = {
                viewModel.loadRecommendedPlaylists(forceRefresh = true)
                viewModel.loadCharts(forceRefresh = true)
            },
            modifier = Modifier.padding(padding),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 680.dp
                LazyColumn(
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 960.dp).fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = if (wide) 24.dp else 16.dp,
                        end = if (wide) 24.dp else 16.dp,
                        top = 8.dp,
                        bottom = 24.dp + LocalBottomChromePadding.current,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "recommendations") {
                        RecommendationEntry(previewTracks, onOpenRecommendations)
                    }
                    item(key = "playlist-heading") {
                        ExploreSectionHeader("推荐歌单")
                    }
                    item(key = "featured") {
                        RecommendedPlaylists(
                            state.featured, wide,
                            onOpenPlaylist = { onOpenPlaylist(it.platform, it.id, it.title) },
                            onRetry = { viewModel.loadRecommendedPlaylists(forceRefresh = true) },
                        )
                    }
                    item(key = "chart-heading") {
                        ExploreSectionHeader("排行榜", "全部榜单", onOpenCharts)
                    }
                    item(key = "charts") {
                        ExploreCharts(state.chart,
                            onRetry = { viewModel.loadCharts(forceRefresh = true) },
                            onOpenChart = { onOpenChart(it.platform, it.chartId, it.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
            if (action != null) {
                Row(Modifier.heightIn(min = 40.dp).homeCardClick(onAction), verticalAlignment = Alignment.CenterVertically) {
                    Text(action, fontSize = 12.sp, color = scheme.onBackgroundVariant)
                    Icon(TablerIcons.ChevronRight, null, Modifier.size(14.dp), tint = scheme.onBackgroundVariant)
                }
            }
        }
    }
}

/** 所有入口采用与项目列表相同的按压反馈。 */
@Composable
private fun Modifier.homeCardClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressable(interactionSource = interaction, indication = SinkFeedback())
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
private fun RecommendationEntry(tracks: List<OnlineSearchResult>, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().squircleClip(20.dp).background(scheme.surfaceContainerHigh).homeCardClick(onClick)) {
        ExploreCoverBackground(tracks.firstOrNull()?.coverUrl, Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("猜你喜欢", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Text("发现下一首喜欢的歌", fontSize = 11.sp, lineHeight = 17.sp,
                    color = scheme.onBackgroundVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    marqueeEnabled = false)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("查看推荐", fontSize = 11.sp, color = scheme.primary)
                    Icon(TablerIcons.ChevronRight, null, Modifier.size(14.dp), tint = scheme.primary)
                }
            }
            Box(Modifier.size(124.dp, 108.dp)) {
                // 最前面为第一首推荐；缺少推荐时使用标准封面占位，不借用无关歌单封面。
                for (index in 2 downTo 0) {
                    val track = tracks.getOrNull(index)
                    val x = when (index) { 0 -> 22.dp; 1 -> 0.dp; else -> 44.dp }
                    val y = if (index == 0) 10.dp else 20.dp
                    val angle = when (index) { 0 -> 0f; 1 -> -10f; else -> 10f }
                    MusesCover(track?.coverUrl,
                        modifier = Modifier.offset(x, y).rotate(angle).shadow(4.dp, RoundedCornerShape(12.dp)),
                        size = 76.dp, contentDescription = track?.name?.let { "$it 封面" })
                }
            }
        }
    }
}

/** 小尺寸封面提供柔和底色；主题遮罩保证明暗模式下文字清晰。 */
@Composable
private fun ExploreCoverBackground(coverUrl: String?, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val imageContext = LocalPlatformContext.current
    val background = remember(coverUrl, imageContext) {
        ImageRequest.Builder(imageContext).data(coverUrl)
            .size(24, 24).precision(Precision.EXACT).build()
    }
    Box(modifier) {
        AsyncImage(background, null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().blur(28.dp, BlurredEdgeTreatment.Unbounded))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
            scheme.surface.copy(alpha = 0.95f), scheme.surface.copy(alpha = 0.60f)))))
    }
}

@Composable
private fun RecommendedPlaylists(
    state: FeaturedPlaylistState,
    wide: Boolean,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    if (state.items.isEmpty()) {
        Card(Modifier.fillMaxWidth().then(if (!state.loading) Modifier.homeCardClick(onRetry) else Modifier)) {
            Text(if (state.loading) "正在加载推荐歌单…" else "推荐歌单暂时不可用，点击重试",
                modifier = Modifier.padding(20.dp), style = MiuixTheme.textStyles.body2,
                color = scheme.onBackgroundVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.items, key = { it.platform + ":" + it.id }) { playlist ->
                val coverSize = if (wide) 200.dp else 164.dp
                Column(Modifier.width(coverSize)
                    .homeCardClick { onOpenPlaylist(playlist) }) {
                    Box(Modifier.size(coverSize).shadow(3.dp, RoundedCornerShape(12.dp))
                        .squircleClip(12.dp)) {
                        MusesCover(playlist.coverUrl, size = coverSize,
                            contentDescription = playlist.title + "封面")
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.40f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.30f),
                            1f to Color.Black.copy(alpha = 0.80f),
                        )))
                        Text(playlist.title, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                            fontSize = 14.sp, lineHeight = 19.sp,
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            color = Color.White, marqueeEnabled = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    PlaylistMetadata(playlist)
                }
            }
        }
        if (state.error != null && !state.loading) {
            Text("推荐歌单更新失败，点击重试",
                modifier = Modifier.homeCardClick(onRetry).padding(vertical = 4.dp),
                style = MiuixTheme.textStyles.footnote1, color = scheme.onBackgroundVariant)
        }
    }
}

@Composable
private fun PlaylistMetadata(playlist: OnlinePlaylist) {
    val details = listOfNotNull(playlist.trackCount?.let { it.toString() + " 首" }, playlist.creator?.takeIf { it.isNotBlank() })
    Text(details.joinToString(" · ").ifBlank { "推荐歌单" }, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onBackgroundVariant,
        marqueeEnabled = false)
}

/** 首页只加载榜单目录；进入具体榜单后再整榜获取歌曲。 */
@Composable
private fun ExploreCharts(state: ChartSectionState, onRetry: () -> Unit, onOpenChart: (OnlineChart) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val charts = state.charts.take(4)
    if (charts.isEmpty()) {
        Card(Modifier.fillMaxWidth().then(if (!state.loadingCharts && !state.refreshingCharts) Modifier.homeCardClick(onRetry) else Modifier)) {
            Text(if (state.loadingCharts || state.refreshingCharts) "正在加载榜单…" else "榜单暂时不可用，点击重试",
                modifier = Modifier.padding(20.dp), style = MiuixTheme.textStyles.body2, color = scheme.onBackgroundVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        charts.forEach { chart ->
            Box(Modifier.fillMaxWidth().squircleClip(18.dp).background(scheme.surfaceContainerHigh)
                .homeCardClick { onOpenChart(chart) }) {
                ExploreCoverBackground(chart.coverUrl, Modifier.matchParentSize())
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chart.name, fontSize = 17.sp, lineHeight = 23.sp, maxLines = 2,
                            fontWeight = FontWeight.SemiBold, color = scheme.onBackground,
                            overflow = TextOverflow.Ellipsis, marqueeEnabled = false)
                        Text(chart.updateInfo.orEmpty(), fontSize = 11.sp, maxLines = 1,
                            color = scheme.onBackgroundVariant, overflow = TextOverflow.Ellipsis,
                            marqueeEnabled = false)
                    }
                    Box(Modifier.size(78.dp, 76.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(64.dp).offset(x = 4.dp).rotate(9f).squircleClip(12.dp)
                            .background(scheme.onBackground.copy(alpha = 0.10f)))
                        MusesCover(chart.coverUrl, size = 64.dp,
                            modifier = Modifier.rotate(-4f).shadow(4.dp, RoundedCornerShape(12.dp)),
                            contentDescription = chart.name + "封面")
                    }
                    Icon(TablerIcons.ChevronRight, null, Modifier.size(16.dp), tint = scheme.onBackgroundVariant)
                }
            }
        }
    }
}
