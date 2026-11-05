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
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.model.Song
import com.muses.player.core.model.download.DownloadTrack
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlinePlaylist
import com.muses.player.core.search.provider.PlaylistAddress
import com.muses.player.core.search.provider.PlaylistImportService
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.components.MusesCoverRadius
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.desktop.DesktopRuntime
import com.muses.player.desktop.update.DesktopUpdater
import com.muses.player.desktop.update.DesktopUpdateInfo
import com.muses.player.feature.home.HomeViewModel
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

private enum class DesktopSection { DISCOVER, PLAYLISTS, LOCAL, ONLINE }

@Composable
fun DesktopShell(onExit: () -> Unit = {}) {
    val scheme = MiuixTheme.colorScheme
    val playlistStore = koinInject<UserPlaylistStore>()
    val songRepository = koinInject<SongRepository>()
    val homeViewModel: HomeViewModel = koinViewModel()
    val playlists by playlistStore.playlists.collectAsState(initial = emptyList())
    val localSongs by songRepository.observeSongs().collectAsState(initial = emptyList())
    val homeState by homeViewModel.state.collectAsState()

    var section by remember { mutableStateOf(DesktopSection.DISCOVER) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var onlineTitle by remember { mutableStateOf<String?>(null) }
    var onlineSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var updateOpen by remember { mutableStateOf(false) }
    var updateLoading by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
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
    val knownSongs = remember(playlists, localSongs) {
        (playlists.flatMap { playlist -> playlist.tracks.map { it.toSong() } } + localSongs + onlineSongs)
            .distinctBy { it.id }
    }
    val visibleSongs = when {
        section == DesktopSection.PLAYLISTS && selectedPlaylist != null -> selectedTracks.filterByQuery(query)
        section == DesktopSection.LOCAL -> localSongs.filterByQuery(query)
        section == DesktopSection.ONLINE -> onlineSongs.filterByQuery(query)
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

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Row(Modifier.fillMaxSize()) {
            DesktopSidebar(
                section = section,
                playlists = playlists,
                selectedPlaylistId = selectedPlaylistId,
                localCount = localSongs.size,
                onDiscover = ::goDiscover,
                onPlaylists = { section = DesktopSection.PLAYLISTS; selectedPlaylistId = null; query = "" },
                onLocal = { section = DesktopSection.LOCAL; selectedPlaylistId = null },
                onPlaylist = ::openPlaylist,
                onCreatePlaylist = { showCreatePlaylist = true },
                onCheckUpdate = ::checkUpdates,
                version = DesktopRuntime.appVersion(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.onBackground.copy(alpha = 0.07f)))
            Column(Modifier.weight(1f)) {
                DesktopTopBar(
                    query = query,
                    onQueryChange = {
                        query = it
                        if (it.isNotBlank() && section == DesktopSection.DISCOVER) section = DesktopSection.LOCAL
                    },
                    canGoBack = section != DesktopSection.DISCOVER || selectedPlaylistId != null,
                    onBack = ::goDiscover,
                    playlistCount = playlists.size,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                onOnline = { toastMessage("在线歌单的桌面详情将在下一版接入") },
                                onChart = { toastMessage("在线榜单的桌面详情将在下一版接入") },
                            )
                            DesktopSection.PLAYLISTS -> {
                                val playlist = playlists.firstOrNull { it.id == targetPlaylist }
                                if (playlist == null) {
                                    DesktopPlaylistGrid(
                                        playlists = playlists,
                                        onOpen = ::openPlaylist,
                                        onCreate = { showCreatePlaylist = true },
                                    )
                                } else {
                                    DesktopPlaylistDetail(
                                        playlistName = playlist.name,
                                        tracks = playlist.tracks.map { it.toSong() },
                                        query = query,
                                        onQueryChange = { query = it },
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
                            DesktopSection.LOCAL -> DesktopLocalMusic(
                                songs = visibleSongs,
                                query = query,
                                onQueryChange = { query = it },
                                onPlay = { song -> playSong(song, visibleSongs) },
                                onPlayAll = { visibleSongs.firstOrNull()?.let { playSong(it, visibleSongs) } },
                            )
                        }
                    }
                }
                DesktopPlayerBar(knownSongs)
            }
        }
        toast?.let {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 98.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.onBackground.copy(alpha = 0.88f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(it, color = scheme.background, fontSize = 12.sp)
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
    onDiscover: () -> Unit,
    onPlaylists: () -> Unit,
    onLocal: () -> Unit,
    onPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onCheckUpdate: () -> Unit,
    version: String,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        Modifier.width(224.dp).fillMaxHeight().background(scheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text("音乐库", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        DesktopNavItem("发现音乐", TablerIcons.Compass, section == DesktopSection.DISCOVER, onDiscover)
        DesktopNavItem("我的歌单", TablerIcons.QueueMusic, section == DesktopSection.PLAYLISTS && selectedPlaylistId == null, onPlaylists)
        DesktopNavItem("本地音乐", TablerIcons.Folder, section == DesktopSection.LOCAL, onLocal, trail = localCount.takeIf { it > 0 }?.toString())

        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 22.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的歌单", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1f))
            DesktopIconButton(TablerIcons.Add, "新建歌单", 26.dp, onClick = onCreatePlaylist)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                val active = selectedPlaylistId == playlist.id
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().height(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) scheme.primary.copy(alpha = 0.11f) else if (hovered) scheme.onBackground.copy(alpha = 0.05f) else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onPlaylist(playlist.id) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        playlist.name,
                        fontSize = 12.sp,
                        color = if (active) scheme.primary else scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(playlist.tracks.size.toString(), fontSize = 10.sp, color = scheme.onBackgroundVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TablerIcons.Settings, null, tint = scheme.onBackgroundVariant, modifier = Modifier.size(15.dp))
            Text("Muses $version", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
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
        Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) scheme.primary.copy(alpha = 0.11f) else if (hovered) scheme.onBackground.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) scheme.primary else scheme.onBackgroundVariant, modifier = Modifier.size(18.dp))
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
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(scheme.background)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopIconButton(TablerIcons.ArrowBack, "返回", 32.dp, enabled = canGoBack, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        DesktopIconButton(TablerIcons.ArrowForward, "前进", 32.dp, enabled = false, onClick = {})
        Spacer(Modifier.width(22.dp))
        Row(
            Modifier.widthIn(min = 260.dp, max = 420.dp).weight(1f).height(36.dp)
                .clip(RoundedCornerShape(9.dp)).background(scheme.surfaceContainerHigh)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TablerIcons.Search, null, tint = scheme.onBackgroundVariant, modifier = Modifier.size(17.dp))
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (query.isEmpty()) Text("搜索歌曲、歌手、专辑", fontSize = 12.sp, color = scheme.onBackgroundVariant)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                            onQueryChange("")
                            true
                        } else false
                    },
                )
            }
            if (query.isNotEmpty()) DesktopIconButton(TablerIcons.Close, "清空搜索", 24.dp, onClick = { onQueryChange("") })
        }
        Spacer(Modifier.weight(1f))
        Text("$playlistCount 个歌单", fontSize = 11.sp, color = scheme.onBackgroundVariant)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.size(30.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
            Text("M", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = scheme.onPrimary)
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
    onOnline: (OnlinePlaylist) -> Unit,
    onChart: () -> Unit,
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
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = 32.dp, end = 44.dp, top = 8.dp, bottom = 34.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(greeting, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                    Text("为你准备今天的声音 · $date", fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 5.dp))
                }
                Text("${localSongs.size} 首本地音乐", fontSize = 11.sp, color = scheme.onBackgroundVariant)
            }

            val hero = playlists.firstOrNull()
            val onlineHero = if (hero == null) featured.firstOrNull() else null
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusesCover(
                    uri = hero?.tracks?.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri ?: onlineHero?.coverUrl,
                    size = 132.dp,
                    radius = MusesCoverRadius.MD,
                )
                Column(Modifier.padding(start = 20.dp).weight(1f)) {
                    Text(if (onlineHero != null) "今日推荐" else "继续聆听", fontSize = 11.sp, color = scheme.primary, fontWeight = FontWeight.Medium)
                    Text(hero?.name ?: onlineHero?.title ?: "从第一张歌单开始", fontSize = 25.sp, fontWeight = FontWeight.Bold,
                        color = scheme.onBackground, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            hero != null -> "${hero.tracks.size} 首 · 保存在本机"
                            onlineHero != null -> "${onlineHero.trackCount ?: 0} 首 · 来自网易云音乐"
                            else -> "建立你的桌面音乐库"
                        },
                        fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(if (onlineHero != null) "打开推荐" else "播放全部") {
                            if (onlineHero != null) {
                                onOnline(onlineHero)
                            } else {
                                val songs = playlists.firstOrNull()?.tracks?.map { it.toSong() }.orEmpty()
                                songs.firstOrNull()?.let { playSong(it, songs) }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        SecondaryButton(if (hero != null) "打开歌单" else "新建歌单") {
                            if (hero != null) onOpenPlaylist(hero.id) else onCreatePlaylist()
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                SectionHeader("我的歌单", "${playlists.size} 个")
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    playlists.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { playlist ->
                                PlaylistCard(playlist, onOpenPlaylist, Modifier.weight(1f))
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (featured.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                SectionHeader("在线推荐", "来自网易云")
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    featured.take(10).chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { item -> OnlineCard(item, { onOnline(item) }, Modifier.weight(1f)) }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (charts.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                SectionHeader("排行榜", "网易云")
                Column(Modifier.fillMaxWidth()) {
                    charts.take(4).forEachIndexed { index, chart ->
                        ChartRow(index, chart, onChart)
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
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground, modifier = Modifier.weight(1f))
        trail?.let { Text(it, fontSize = 11.sp, color = scheme.onBackgroundVariant) }
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
            .padding(8.dp),
    ) {
        SquareCover(playlist.tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri)
        Text(playlist.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text("${playlist.tracks.size} 首", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 2.dp))
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
            .padding(8.dp),
    ) {
        SquareCover(item.coverUrl)
        Text(item.title, fontSize = 12.sp, color = scheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        item.trackCount?.let { Text("$it 首", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun ChartRow(index: Int, chart: OnlineChart, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(7.dp))
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontSize = 12.sp, color = if (index < 3) scheme.primary else scheme.onBackgroundVariant, modifier = Modifier.width(22.dp))
        MusesCover(chart.coverUrl, size = 40.dp, radius = MusesCoverRadius.SM)
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(chart.name, fontSize = 13.sp, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            chart.updateInfo?.let { Text(it, fontSize = 10.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(TablerIcons.Play, null, tint = scheme.onBackgroundVariant, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun DesktopPlaylistGrid(
    playlists: List<com.muses.player.core.data.repository.UserPlaylist>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的歌单", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground, modifier = Modifier.weight(1f))
            SecondaryButton("新建歌单", onCreate)
        }
        if (playlists.isEmpty()) {
            EmptyPrompt("还没有自建歌单。", "新建歌单", onCreate)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(playlists.chunked(4)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { PlaylistCard(it, onOpen, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPrompt(message: String, action: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(message, fontSize = 12.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1f))
        SecondaryButton(action, onClick)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(7.dp)).background(scheme.primary)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimary) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(7.dp)).background(scheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, color = scheme.onBackground) }
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
    onQueryChange: (String) -> Unit,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val filtered = tracks.filterByQuery(query)
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(
                uri = tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri,
                size = 104.dp,
                radius = MusesCoverRadius.MD,
            )
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text(playlistName, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tracks.size} 首 · 保存在本机", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("播放全部", onPlayAll)
                    Spacer(Modifier.width(8.dp))
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
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(
                uri = tracks.firstOrNull { !it.coverUri.isNullOrBlank() }?.coverUri,
                size = 104.dp,
                radius = MusesCoverRadius.MD,
            )
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text(
                    if (loading && tracks.isEmpty()) "正在读取在线歌单…" else title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${tracks.size} 首 · 来自网易云音乐", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("播放全部", onPlayAll)
                    Spacer(Modifier.width(8.dp))
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
private fun DesktopLocalMusic(
    songs: List<Song>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本地音乐", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                Text("${songs.size} 首 · 来自已添加的音乐文件夹", fontSize = 11.sp, color = scheme.onBackgroundVariant, modifier = Modifier.padding(top = 3.dp))
            }
            if (songs.isNotEmpty()) PrimaryButton("播放全部", onPlayAll)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.08f)))
        TrackTable(
            songs = songs,
            emptyText = if (query.isBlank()) "还没有本地音乐，请在音源页添加文件夹" else "没有找到匹配歌曲",
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
        )
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
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(34.dp))
            Text("标题", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(2.4f))
            Text("歌手", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1.2f))
            Text("专辑", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.weight(1.5f))
            Text("音质", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(62.dp))
            Text("时长", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(56.dp))
            Spacer(Modifier.width(30.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, fontSize = 12.sp, color = scheme.onBackgroundVariant)
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(46.dp)
            .background(if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPlay(song) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(34.dp))
        Row(Modifier.weight(2.4f), verticalAlignment = Alignment.CenterVertically) {
            MusesCover(song.coverUri, size = 30.dp, radius = MusesCoverRadius.SM)
            Column(Modifier.padding(start = 9.dp)) {
                Text(song.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(song.artist ?: "—", fontSize = 11.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
        Text(song.album ?: "—", fontSize = 11.sp, color = scheme.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.5f))
        Text(song.audioQuality ?: "—", fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(62.dp))
        Text(formatDuration(song.durationMs), fontSize = 10.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(56.dp))
        Icon(TablerIcons.MoreHorizontal, "更多", tint = scheme.onBackgroundVariant, modifier = Modifier.size(18.dp))
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

