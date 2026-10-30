package com.muses.player.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.data.repository.UserPlaylist
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.model.Song
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val LIKED_COLLECTION = "system:liked"
const val DOWNLOADED_COLLECTION = "system:downloaded"

/** 系统集合与用户歌单并列显示，但不共享成员写入接口。 */
@Composable
fun PlaylistsScreen(store: UserPlaylistStore, likedCount: Int, downloadedCount: Int, onOpen: (String) -> Unit, onImport: () -> Unit = {}) {
    val playlists by store.playlists.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UserPlaylist?>(null) }
    var actions by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleting by remember { mutableStateOf<UserPlaylist?>(null) }
    var deletingBusy by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(), containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar("歌单", actions = {
            MusesIconButton(onClick = { showCreateMenu = true }, imageVector = TablerIcons.Add, contentDescription = "新建或导入歌单")
        }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalBottomChromePadding.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "system") {
                Card {
                    BasicComponent(title = "我喜欢", summary = "$likedCount 首 · 点击心形即可收藏", onClick = { onOpen(LIKED_COLLECTION) })
                    BasicComponent(title = "已下载", summary = "$downloadedCount 首 · 下载成功后自动收录", onClick = { onOpen(DOWNLOADED_COLLECTION) })
                }
            }
            item(key = "heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的歌单", modifier = Modifier.weight(1f))
                    Text("${playlists.size} 个", color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
            }
            if (playlists.isEmpty()) item(key = "empty") {
                Text("还没有自建歌单。新建后，可通过歌曲右侧的三个点选择“添加到歌单”。", color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.background).clickable { onOpen(playlist.id) }
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MusesCover(playlist.tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri, size = 52.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(playlist.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.tracks.size} 首", color = MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    SongMoreButton(onClick = { actions = playlist })
                }
            }
        }
    }
    MusesActionsSheet(opened = showCreateMenu, onDismiss = { showCreateMenu = false }, label = "收集喜欢的音乐",
        items = listOf(
            MusesActionItem("新建歌单") { showCreateMenu = false; creating = true },
            MusesActionItem("导入歌单") { showCreateMenu = false; onImport() },
        ))
    MusesActionsSheet(
        opened = actions != null, onDismiss = { actions = null }, label = actions?.name ?: "歌单操作",
        items = listOf(
            MusesActionItem("重命名", onClick = { editing = actions; actions = null }),
            MusesActionItem("删除歌单", destructive = true, onClick = { deleting = actions; actions = null }),
        ),
    )
    if (creating || editing != null) PlaylistNameDialog(
        initial = editing?.name.orEmpty(), title = if (creating) "新建歌单" else "重命名歌单",
        onDismiss = { creating = false; editing = null },
        onSave = { name ->
            val id = editing?.id
            if (id == null) store.create(name) else store.rename(id, name)
        },
    )
    deleting?.let { playlist ->
        MusesDialog(
            title = "删除歌单？", message = "删除“${playlist.name}”仅移除该歌单，其他歌单、我喜欢和下载文件都会保留。",
            onDismiss = { if (!deletingBusy) deleting = null }, dismissText = "取消", confirmText = "删除",
            confirmLoading = deletingBusy,
            onConfirm = {
                if (!deletingBusy) {
                    deletingBusy = true
                    scope.launch {
                        try { store.delete(playlist.id); deleting = null }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { MusesSnackbar.show("删除歌单失败，请重试") }
                        finally { deletingBusy = false }
                    }
                }
            },
        )
    }
}

@Composable
private fun PlaylistNameDialog(initial: String, title: String, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MusesFormSheet(
        title = title, onDismiss = { if (!saving) onDismiss() },
        dismissText = "取消", confirmText = "保存", confirmEnabled = name.isNotBlank(), confirmLoading = saving,
        onConfirm = {
            if (!saving) {
                saving = true
                scope.launch {
                    try { onSave(name); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
                    finally { saving = false }
                }
            }
        },
        content = {
            MusesTextField(name, { name = it; error = null }, label = "歌单名称（1–40 个字符）", singleLine = true, enabled = !saving)
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        },
    )
}

/** 移除回调由当前集合提供，组件不会推断其他歌单的归属。 */
@Composable
fun CollectionSongsScreen(
    title: String, songs: List<Song>, currentSongId: String?, loaded: Boolean,
    onBack: () -> Unit, onPlay: (Song, List<Song>) -> Unit, onQueue: (Song) -> Unit,
    onRemove: ((Song) -> Unit)?, removeLabel: String = "从当前歌单移除", onManageDownloads: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null, downloadAllEnabled: Boolean = true,
    onRepairMetadata: (() -> Unit)? = null, repairingMetadata: Boolean = false,
    metadataStatus: String? = null,
    songStatus: (Song) -> String? = { null },
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var showSearch by rememberSaveable(title) { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(showSearch) {
        if (!showSearch) { focusManager.clearFocus(); keyboard?.hide() }
    }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var showMetadataActions by remember { mutableStateOf(false) }
    if (showMetadataActions) MusesActionsSheet(opened = true, onDismiss = { showMetadataActions = false },
        label = "歌单信息", items = listOf(MusesActionItem("重新补全缺失信息") {
            showMetadataActions = false; onRepairMetadata?.invoke()
        }))
    val visible = remember(songs, query) {
        songs.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.artist.orEmpty().contains(query.trim(), true) }
    }
    val favorites = LocalFavoriteSongs.current
    val enqueueDownload = LocalDownloadSong.current
    val addPlaylist = LocalAddSongToPlaylist.current
    Scaffold(
        modifier = Modifier.fillMaxSize(), containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar(
            title = title, onBack = onBack,
            actions = {
                if (onRepairMetadata != null) MusesIconButton(onClick = { showMetadataActions = true },
                    imageVector = TablerIcons.MoreHorizontal, contentDescription = "歌单更多操作")

            },
        ) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + LocalBottomChromePadding.current),
        ) {
            item(key = "cover") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        MusesCover(songs.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri, size = 84.dp)
                        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${songs.size} 首", color = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                    }
                    if (metadataStatus != null || repairingMetadata) Text(metadataStatus ?: "正在自动完善信息…", color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
            }
            stickyHeader(key = "actions") {
                Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(horizontal = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = visible.isNotEmpty()) {
                            visible.firstOrNull()?.let { onPlay(it, visible) }
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
                                Icon(TablerIcons.PlayFill, null, tint = MiuixTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
                            }
                            Text(if (query.isNotBlank()) "播放结果" else "播放全部", Modifier.padding(start = 9.dp), fontWeight = FontWeight.Medium)
                            Text(" · ${visible.size}", color = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                        MusesIconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" },
                            imageVector = TablerIcons.Search, contentDescription = if (showSearch) "收起列表搜索" else "搜索当前列表")
                        onDownloadAll?.let { download -> MusesIconButton(onClick = download, enabled = downloadAllEnabled,
                            imageVector = TablerIcons.Download, contentDescription = "选择歌曲下载") }
                        onManageDownloads?.let { manage -> MusesIconButton(onClick = manage,
                            imageVector = TablerIcons.Download, contentDescription = "下载管理") }
                    }
                    AnimatedVisibility(showSearch, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            LibrarySearchField(query, { query = it }, "搜索当前列表", onCancel = { query = ""; showSearch = false }, autoFocus = true)
                            if (query.isNotBlank()) Text("找到 ${visible.size} 首 / 共 ${songs.size} 首", modifier = Modifier.padding(top = 6.dp), color = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                    }
                }
            }
            if (visible.isEmpty()) item {
                Text(
                    if (!loaded) "正在加载…" else if (query.isNotBlank()) "没有找到匹配歌曲" else "这里还没有歌曲",
                    modifier = Modifier.padding(24.dp), color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            items(visible, key = { it.id }) { song ->
                SongListItem(
                    song = SongItem(song.id, song.title, song.artist, listOfNotNull(song.album, songStatus(song)).joinToString(" · "), song.coverUri, song.audioQuality),
                    isCurrent = song.id == currentSongId, isLiked = favorites.isLiked(song),
                    onClick = { onPlay(song, visible) }, onLongClick = { actionSong = song },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongFavoriteButton(song)
                            SongMoreButton(onClick = { actionSong = song })
                        }
                    },
                )
            }
        }
    }
    MusesActionsSheet(
        opened = actionSong != null, onDismiss = { actionSong = null }, label = "歌曲操作",
        songInfo = actionSong?.let { MusesActionSongInfo(it.title, it.artist, it.album, it.coverUri) },
        items = actionSong?.let { song -> buildList {
            addPlaylist?.let { addTo -> add(MusesActionItem("添加到歌单", onClick = { actionSong = null; addTo(song) })) }
            add(MusesActionItem("添加到播放队列", onClick = { actionSong = null; onQueue(song) }))
            if (com.muses.player.core.model.online.OnlineTrackRef.parse(song.path) != null) {
                enqueueDownload?.let { enqueue -> add(MusesActionItem("下载", onClick = { actionSong = null; enqueue(song) })) }
            }
            onRemove?.let { remove -> add(MusesActionItem(removeLabel, destructive = true, onClick = { actionSong = null; remove(song) })) }
        } } ?: emptyList(),
    )
}
