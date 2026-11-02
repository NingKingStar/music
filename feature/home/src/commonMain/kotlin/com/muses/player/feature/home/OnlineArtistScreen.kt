package com.muses.player.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.muses.player.core.model.Song
import com.muses.player.core.search.qualityLabel
import com.muses.player.core.search.performanceLabel
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 歌手主页专用布局，资料、作品及选择层采用同一视觉层级。 */
@Composable
fun OnlineArtistScreen(vm: OnlineArtistViewModel, onBack: () -> Unit, onDownload: (List<Song>) -> Unit) {
    val state by vm.state.collectAsState()
    val colors = MiuixTheme.colorScheme
    val list = rememberLazyListState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedBio by rememberSaveable(state.artist?.id) { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val current = LocalSongMarqueeState.current.songId
    val favorites = LocalFavoriteSongs.current
    val setLiked = LocalSetSongLiked.current
    val add = LocalAddSongToPlaylist.current
    val download = LocalDownloadSong.current
    val visible = remember(state.songs, query) { state.songs.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.artist.orEmpty().contains(query.trim(), true) } }
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 140 } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f)
    LaunchedEffect(state.candidates, state.artist) { if (state.artist == null && state.candidates.isNotEmpty()) choosing = true }
    LaunchedEffect(state.artist?.id) { list.scrollToItem(0); query = ""; search = false }
    LaunchedEffect(state.songs.size, state.hasMore, state.loading, state.error, query) {
        if (state.artist != null && state.songs.isNotEmpty() && state.hasMore && !state.loading && state.error == null && query.isBlank()) {
            snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
                .distinctUntilChanged().collect { if (it) vm.load() }
        }
    }
    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtistControl(TablerIcons.ArrowBack, "返回", onClick = onBack)
            Text(state.profile?.artist?.name ?: state.artist?.name ?: "歌手主页", Modifier.weight(1f).alpha(if (state.artist == null) 1f else titleAlpha),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.candidates.size > 1) ArtistControl(TablerIcons.Person, "选择歌手", onClick = { choosing = true })
            ArtistControl(TablerIcons.Refresh, "刷新", enabled = !state.loading, onClick = { if (state.artist == null) vm.resolve() else vm.refresh() })
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp + LocalBottomChromePadding.current)) {
            state.artist?.let { artist ->
                val profile = state.profile
                val avatar = profile?.artist?.avatar ?: artist.avatar
                item("profile") {
                    Box(Modifier.fillMaxWidth()) {
                        if (avatar != null) {
                            AsyncImage(avatar, null, Modifier.fillMaxWidth().height(240.dp).alpha(.16f), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxWidth().height(240.dp).background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))))
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ArtistPortrait(avatar, 100.dp, profile?.artist?.name ?: artist.name)
                            Text(profile?.artist?.name ?: artist.name, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp)
                            profile?.aliases?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = colors.onSurface.copy(alpha = .6f), fontSize = 13.sp) }
                            val counts = listOfNotNull(profile?.songCount?.let { "$it 首歌曲" }, profile?.albumCount?.let { "$it 张专辑" })
                            if (counts.isNotEmpty()) Text(counts.joinToString("  ·  "), color = colors.onSurface.copy(alpha = .6f), fontSize = 13.sp)
                            if (!state.confirmed) Text("请确认是你要找的演唱者", color = colors.onSurface.copy(alpha = .6f), fontSize = 12.sp)
                        }
                    }
                }
                item("biography") {
                    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (profile?.biography?.isNotBlank() == true) {
                            Text(profile.biography, fontSize = 14.sp, lineHeight = 22.sp, color = colors.onSurface.copy(alpha = .72f),
                                maxLines = if (expandedBio) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { expandedBio = !expandedBio }.padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (expandedBio) "收起简介" else "歌手简介", fontSize = 12.sp, color = colors.primary)
                                Icon(if (expandedBio) TablerIcons.ChevronUp else TablerIcons.ChevronDown, null, Modifier.size(14.dp), tint = colors.primary)
                            }
                        } else if (state.profileLoading) Text("正在加载歌手资料…", fontSize = 12.sp, color = colors.onSurface.copy(alpha = .5f))
                        if (state.profileError) Text("资料暂未加载 · 点击重试", Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.loadProfile(true) }.padding(vertical = 6.dp), fontSize = 12.sp, color = colors.onSurface.copy(alpha = .6f))
                    }
                }
                item("actions") {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(enabled = visible.isNotEmpty()) { vm.play(visible, 0) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) { Icon(TablerIcons.PlayFill, null, Modifier.size(18.dp), tint = colors.onPrimary) }
                            Spacer(Modifier.width(10.dp))
                            Column { Text("播放全部", fontSize = 15.sp, fontWeight = FontWeight.Medium); Text("热门歌曲 · 已加载 ${state.songs.size} 首", fontSize = 11.sp, color = colors.onSurface.copy(alpha = .5f)) }
                        }
                        ArtistControl(TablerIcons.Search, "搜索已加载歌曲", active = search, onClick = { search = !search; if (!search) query = "" })
                        ArtistControl(TablerIcons.Download, "选择歌曲下载", enabled = visible.isNotEmpty(), onClick = { onDownload(visible.map { it.toSong("online") }) })
                    }
                    AnimatedVisibility(search) {
                        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.onSurface.copy(alpha = .05f)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(TablerIcons.Search, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .5f))
                            BasicTextField(query, { query = it }, Modifier.weight(1f).padding(12.dp), singleLine = true,
                                textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                                decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索已加载歌曲", fontSize = 14.sp, color = colors.onSurface.copy(alpha = .4f)); inner() } })
                            ArtistControl(TablerIcons.Close, "关闭搜索", onClick = { query = ""; search = false })
                        }
                    }
                }
            }
            if (state.artist == null) item("resolve") {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
                    Text(if (state.loading) "正在识别歌手…" else if (state.candidates.isNotEmpty()) "请选择这首歌的演唱者" else "暂未找到歌手", color = colors.onSurface.copy(alpha = .6f), fontSize = 14.sp)
                    if (state.candidates.isNotEmpty()) ArtistLabel("选择歌手") { choosing = true }
                }
            }
            itemsIndexed(visible, key = { _, song -> "song:${song.platform}:${song.songId}" }) { index, result ->
                val song = remember(result) { result.toSong("online") }
                val liked = favorites.isLiked(song)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (song.id == current) colors.primary.copy(alpha = .07f) else Color.Transparent)
                    .clickable { vm.play(visible, index) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)).background(colors.onSurface.copy(alpha = .05f)), contentAlignment = Alignment.Center) {
                        Icon(TablerIcons.MusicNote, null, Modifier.size(20.dp), tint = colors.onSurface.copy(alpha = .2f))
                        AsyncImage(result.coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (song.id == current) colors.primary else colors.onSurface,
                            fontWeight = if (song.id == current) FontWeight.Medium else FontWeight.Normal)
                        Text(listOfNotNull(result.artist, result.album).joinToString(" · "), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = .55f))
                        listOfNotNull(result.qualityLabel, result.performanceLabel).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontSize = 10.sp, color = colors.onSurface.copy(alpha = .45f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (setLiked != null) ArtistControl(if (liked) TablerIcons.HeartFill else TablerIcons.Heart, if (liked) "取消喜欢" else "喜欢", active = liked, enabled = favorites.loaded, onClick = { setLiked(song, !liked) })
                    ArtistControl(TablerIcons.MoreHorizontal, "歌曲操作", onClick = { menuSong = song })
                }
            }
            item("footer") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        state.error != null -> { Text(state.error!!, fontSize = 13.sp, color = colors.onSurface.copy(alpha = .6f)); ArtistLabel("重试", vm::retry) }
                        state.loading && state.artist != null -> CircularProgressIndicator(Modifier.size(22.dp))
                        state.artist != null && visible.isEmpty() -> Text(if (query.isBlank()) "暂无歌曲" else "已加载歌曲中没有匹配结果", fontSize = 13.sp, color = colors.onSurface.copy(alpha = .45f))
                        state.artist != null && state.hasMore -> ArtistLabel("加载更多", { vm.load() })
                        state.songs.isNotEmpty() -> Text("已显示全部歌曲", fontSize = 12.sp, color = colors.onSurface.copy(alpha = .4f))
                    }
                }
            }
        }
    }
    if (choosing) OverlayBottomSheet(show = true, onDismissRequest = { choosing = false }, title = "选择演唱者") {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.candidates, key = { it.platform + it.id }) { artist ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { choosing = false; vm.select(artist) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtistPortrait(artist.avatar, 48.dp, artist.name); Spacer(Modifier.width(14.dp)); Text(artist.name, Modifier.weight(1f), fontSize = 16.sp)
                    Icon(TablerIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .4f))
                }
            }
        }
    }
    menuSong?.let { song ->
        OverlayBottomSheet(show = true, onDismissRequest = { menuSong = null }, title = song.title) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                if (add != null) ArtistMenuRow(TablerIcons.QueueMusic, "添加到歌单") { menuSong = null; add(song) }
                if (download != null) ArtistMenuRow(TablerIcons.Download, "下载歌曲") { menuSong = null; download(song) }
            }
        }
    }
}

@Composable
private fun ArtistPortrait(url: String?, size: Dp, name: String) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.size(size).clip(CircleShape).background(colors.onSurface.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
        Icon(TablerIcons.Person, null, Modifier.size(size * .42f), tint = colors.onSurface.copy(alpha = .25f))
        if (url != null) AsyncImage(url, "$name 的头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ArtistControl(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f)
    val colors = MiuixTheme.colorScheme
    Box(Modifier.size(44.dp).scale(scale).clip(CircleShape).clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = (if (active) colors.primary else colors.onSurface).copy(alpha = if (enabled) .85f else .25f))
    }
}

@Composable
private fun ArtistLabel(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
}

@Composable
private fun ArtistMenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp)); Spacer(Modifier.width(16.dp)); Text(label, fontSize = 16.sp)
    }
}
