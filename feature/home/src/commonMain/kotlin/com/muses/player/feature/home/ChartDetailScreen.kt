package com.muses.player.feature.home

import com.muses.player.core.search.performanceLabel
import com.muses.player.core.search.qualityLabel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Column
import com.muses.player.core.ui.components.LibrarySearchField
import com.muses.player.core.ui.components.SongListActions
import com.muses.player.core.model.Song
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesTextButton
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesPullToRefresh
import com.muses.player.core.ui.components.MusesRefreshablePlaceholder
import com.muses.player.core.ui.components.SongItem
import com.muses.player.core.ui.components.SongListItem
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.LocalSongMarqueeState
import com.muses.player.core.ui.components.SongListJumpFab
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FabPosition
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ChartDetailScreen(
    platform: String,
    chartId: String,
    chartName: String,
    onBack: () -> Unit,
    isPlaylist: Boolean = false,
    onDownloadSongs: ((List<Song>) -> Unit)? = null,
    downloadEnabled: Boolean = true,
    viewModel: ChartDetailViewModel = koinViewModel(
        parameters = { parametersOf(platform, chartId) },
    ),
) {
    val state by viewModel.state.collectAsState()
    val scheme = MiuixTheme.colorScheme
    val currentSongId = LocalSongMarqueeState.current.songId
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(platform, chartId) { mutableStateOf("") }
    var searching by rememberSaveable(platform, chartId) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    if (more) com.muses.player.core.ui.components.MusesActionsSheet(opened = true, onDismiss = { more = false },
        label = chartName, items = listOf(com.muses.player.core.ui.components.MusesActionItem("随机播放") {
            more = false; viewModel.shuffle()
        }))
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(searching) { if (!searching) { focus.clearFocus(); keyboard?.hide() } }
    val visible = remember(state.songs, query) { state.songs.withIndex().filter {
        query.isBlank() || it.value.name.contains(query.trim(), true) || it.value.artist.orEmpty().contains(query.trim(), true)
    } }
    val currentSongIndex = visible.indexOfFirst { it.value.toSong("online").id == currentSongId }


    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = chartName,
                onBack = onBack,
                actions = {
                    MusesIconButton(onClick = { more = true }, imageVector = TablerIcons.MoreHorizontal,
                        contentDescription = "榜单更多操作", enabled = state.songs.isNotEmpty())
                    if (isPlaylist) {
                        MusesIconButton(
                            onClick = viewModel::refresh,
                            imageVector = TablerIcons.Refresh,
                            contentDescription = "刷新歌单",
                            enabled = !state.loading && !state.refreshing,
                        )
                    }
                },
                bottomContent = {
                    if (state.songs.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            SongListActions(count = visible.size, searching = searching, filtered = query.isNotBlank(),
                                onPlay = { viewModel.playResults(visible.map { it.value }) },
                                onSearch = { searching = !searching; if (!searching) query = "" },
                                onDownload = onDownloadSongs?.let { action -> { action(visible.map { it.value.toSong("online") }) } },
                                downloadEnabled = downloadEnabled && visible.isNotEmpty())
                            AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                LibrarySearchField(query, { query = it }, "搜索当前列表",
                                    onCancel = { query = ""; searching = false }, autoFocus = true)
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            SongListJumpFab(listState, currentSongId.takeIf { currentSongIndex >= 0 }) {
                scope.launch { listState.animateScrollToItem(currentSongIndex) }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        MusesPullToRefresh(
            isRefreshing = state.loading || state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding),
        ) {
            if (state.songs.isEmpty()) {
                MusesRefreshablePlaceholder {
                    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator()
                            state.error != null -> androidx.compose.foundation.layout.Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(state.error.orEmpty(), color = scheme.onSurfaceVariantSummary)
                                MusesTextButton(onClick = viewModel::retry, text = "重试")
                            }
                            else -> MusesEmpty(
                                modifier = Modifier.fillMaxSize(),
                                bottomInset = LocalBottomChromePadding.current,
                            )
                        }
                    }
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = SongListLayout.contentHorizontalPadding,
                    end = SongListLayout.contentHorizontalPadding,
                    bottom = 16.dp + LocalBottomChromePadding.current,
                ),
                verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
            ) {
                if (visible.isEmpty()) item {
                    Text("没有找到匹配歌曲", modifier = Modifier.padding(24.dp), color = scheme.onBackgroundVariant)
                }
                itemsIndexed(
                    items = visible,
                    key = { _, entry -> entry.value.toSong("online").id },
                ) { index, entry ->
                    val song = entry.value
                    val playableSong = song.toSong("online")
                    SongListItem(
                        isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(playableSong),
                        showCover = true,
                        song = SongItem(
                            id = playableSong.id,
                            title = song.name,
                            artist = song.artist,
                            albumTitle = song.album,
                            coverUri = song.coverUrl,
                        ),
                        isCurrent = playableSong.id == currentSongId,
                        titleBadgeLabel = song.performanceLabel,
                        qualityBadgeLabel = song.qualityLabel,
                        onClick = { viewModel.playResults(visible.map { it.value }, index) },
                        trailingContent = { com.muses.player.core.ui.components.OnlineSongDownloadAction(playableSong) },
                    )
                }
            }
        }
        }
    }
}
