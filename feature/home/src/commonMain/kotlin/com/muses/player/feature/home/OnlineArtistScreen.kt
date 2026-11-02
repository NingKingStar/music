package com.muses.player.feature.home

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muses.player.core.model.Song
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import com.muses.player.core.search.performanceLabel
import com.muses.player.core.search.qualityLabel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OnlineArtistScreen(vm: OnlineArtistViewModel, onBack: () -> Unit, onDownload: (List<Song>) -> Unit) {
    val state by vm.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val current = LocalSongMarqueeState.current.songId
    val visible = remember(state.songs, query) { state.songs.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.artist.orEmpty().contains(query.trim(), true) } }
    LaunchedEffect(state.candidates, state.artist) { if (state.artist == null && state.candidates.isNotEmpty()) choosing = true }
    MusesActionsSheet(opened = choosing, onDismiss = { choosing = false }, label = if (state.confirmed) "选择歌手" else "请选择对应歌手",
        items = state.candidates.map { artist -> MusesActionItem(artist.name + if (!state.confirmed) " · " + (if (artist.platform == "wy") "网易曲库" else "QQ 曲库") else "") {
            choosing = false; query = ""; vm.select(artist)
        } })
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0,0,0,0),
        topBar = { MusesTopBar(state.artist?.name ?: "歌手", onBack = onBack, actions = {
            MusesIconButton(onClick = { if (state.artist == null) vm.resolve() else vm.load(true) }, imageVector = TablerIcons.Refresh,
                contentDescription = "刷新歌手歌曲", enabled = !state.loading)
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.artist?.let { artist ->
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MusesCover(artist.avatar ?: state.songs.firstOrNull()?.coverUrl, size = 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(artist.name)
                        Text("已加载 ${state.songs.size} 首 · 热门歌曲", color = MiuixTheme.colorScheme.onBackgroundVariant)
                        if (!state.confirmed) Text("按歌手名查找，请确认演唱者", color = MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    if (state.candidates.size > 1) MusesTextButton(text = "切换", onClick = { choosing = true })
                }
                SongListActions(visible.size, search, onPlay = { vm.play(visible, 0) }, onSearch = { search = !search; if (!search) query = "" },
                    onDownload = { onDownload(visible.map { it.toSong("online") }) }, downloadEnabled = visible.isNotEmpty(), filtered = query.isNotBlank())
                AnimatedVisibility(search) { LibrarySearchField(query, { query = it }, placeholder = "搜索已加载歌曲", onCancel = { query = ""; search = false }) }
            }
            if (state.loading && state.songs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + LocalBottomChromePadding.current)) {
                state.error?.let { error -> item { Column(Modifier.padding(16.dp)) { Text(error); MusesTextButton(text = "重试", onClick = { if (state.artist == null) vm.resolve() else vm.load() }) } } }
                if (state.artist == null && state.candidates.isNotEmpty()) item { MusesTextButton(text = "选择歌手", onClick = { choosing = true }) }
                if (state.artist != null && state.songs.isEmpty() && state.error == null) item { Text("暂无歌曲", Modifier.padding(24.dp)) }
                if (state.songs.isNotEmpty() && visible.isEmpty()) item { Text("已加载歌曲中没有匹配结果", Modifier.padding(24.dp)) }
                itemsIndexed(visible, key = { _, song -> song.platform + ":" + song.songId }) { index, result ->
                    val song = remember(result) { result.toSong("online") }
                    SongListItem(song = SongItem(song.id, result.name, result.artist, result.album, result.coverUrl),
                        isLiked = LocalFavoriteSongs.current.isLiked(song), showCover = true, isCurrent = song.id == current,
                        titleBadgeLabel = result.performanceLabel, qualityBadgeLabel = result.qualityLabel,
                        onClick = { vm.play(visible, index) }, trailingContent = { OnlineSongDownloadAction(song) })
                }
                if (state.artist != null && state.hasMore) item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        MusesTextButton(text = if (state.loading) "加载中…" else "加载更多", enabled = !state.loading, onClick = { vm.load() })
                    }
                }
            }
        }
    }
}
