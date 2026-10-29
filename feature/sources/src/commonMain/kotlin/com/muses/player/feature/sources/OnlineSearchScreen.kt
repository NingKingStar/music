package com.muses.player.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.performanceLabel
import com.muses.player.core.search.qualityLabel
import com.muses.player.core.model.libraryQualityLabel
import com.muses.player.core.ui.components.SongSubtitleWithQuality
import com.muses.player.core.ui.components.SongTitleWithBadge
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesRefreshableContent
import com.muses.player.core.ui.components.SongListLayout
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.icons.TablerIcons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import com.muses.player.core.ui.components.MarqueeText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 搜索页（曲库与在线结果）。
 *
 * 交互：输入关键词 → 5 平台并行搜索 → 按平台分组展示 → 点结果直接播放。
 * 部分平台失败不影响其它平台（失败原因就地展示在该平台分组下）。
 */
@Composable
fun OnlineSearchScreen(
    onBack: (() -> Unit)? = null,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    /** 外部传入的搜索词；非空时进入页面自动搜索。 */
    initialKeyword: String = "",
    viewModel: OnlineSearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val libraryResults by viewModel.libraryResults.collectAsState()
    val lyricResults by viewModel.lyricResults.collectAsState()
    val playlistStore = koinInject<com.muses.player.core.data.repository.UserPlaylistStore>()
    val playlists by playlistStore.playlists.collectAsState(emptyList())
    val scheme = MiuixTheme.colorScheme
    var selectedSourceSong by remember { mutableStateOf<OnlineSearchResult?>(null) }
    val searchListState = rememberLazyListState()
    SearchBackHandler(enabled = selectedSourceSong != null) { selectedSourceSong = null }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank() && state.keyword != initialKeyword) {
            viewModel.prefillKeyword(initialKeyword)
            viewModel.search()
        }
    }

    selectedSourceSong?.let { selected ->
        val sources = groupSearchResults(state.platforms, state.searchedKeyword, state.originalFirst).firstOrNull { selected in it }.orEmpty()
        MoreSourcesScreen(
            song = selected,
            sources = sources,
            platforms = state.platforms,
            playlists = playlists,
            librarySongs = (libraryResults.songs + lyricResults.matches.map { it.song }).distinctBy { it.id },
            onBack = { selectedSourceSong = null },
            onPlay = viewModel::playGroupedResult,
            onPlayLocal = viewModel::playLibrarySong,
        )
        return
    }

    Scaffold(
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = "搜索",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ── 搜索输入行 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    modifier = Modifier.weight(1f),
                    inputField = {
                        InputField(
                            query = state.keyword,
                            onQueryChange = viewModel::updateKeyword,
                            onSearch = { viewModel.search() },
                            expanded = false,
                            onExpandedChange = {},
                            label = "搜索歌曲、专辑、歌手或歌词",
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                ) {}
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlatformPill("歌曲", state.category == SearchCategory.ALL, { viewModel.setCategory(SearchCategory.ALL) })
                PlatformPill("专辑", state.category == SearchCategory.ALBUMS, { viewModel.setCategory(SearchCategory.ALBUMS) })
                PlatformPill("歌手", state.category == SearchCategory.ARTISTS, { viewModel.setCategory(SearchCategory.ARTISTS) })
            }

            if (state.category == SearchCategory.ALL && state.searched) {
                Text(if (state.originalFirst) "排序：相关性 · 原唱优先 ⇄" else "排序：平台默认 ⇄",
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleOriginalFirst() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 12.sp, color = scheme.primary)
            }

            // 提示横幅（如「该平台无可用脚本」）：点按可关闭
            state.message?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.errorContainer)
                        .clickable { viewModel.clearMessage() }
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
                        text = msg,
                        fontSize = 12.sp,
                        color = scheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = TablerIcons.Close,
                        contentDescription = "关闭提示",
                        tint = scheme.onErrorContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            when {
                state.keyword.isNotBlank() && state.keyword.trim() != state.searchedKeyword -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在更新搜索…", color = scheme.onSurfaceVariantSummary)
                    }
                }
                !state.searched -> {
                    RecentSearches(recentSearches, viewModel::searchHistory, viewModel::removeHistory,
                        viewModel::clearHistory, Modifier.weight(1f))
                }
                else -> {
                    MusesRefreshableContent(onRefresh = viewModel::refresh) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = searchListState,
                        contentPadding = PaddingValues(
                            start = SongListLayout.contentHorizontalPadding,
                            end = SongListLayout.contentHorizontalPadding,
                            bottom = 16.dp + com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
                        ),
                        verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
                    ) {
                        val matching = libraryResults.keyword == state.searchedKeyword
                        if (state.category == SearchCategory.ALBUMS) {
                            item(key = "albums-scope") { Text("已有专辑", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary) }
                            val albums = if (matching) libraryResults.albums else emptyList()
                            if (albums.isEmpty()) item(key = "albums-empty") { Text("没有找到专辑", color = scheme.onSurfaceVariantSummary) }
                            items(albums, key = { "album-${it.id}" }) { album ->
                                LibraryResultRow(album.title, album.artist.orEmpty(), "${album.songCount} 首") { onAlbumClick(album.id) }
                            }
                        } else if (state.category == SearchCategory.ARTISTS) {
                            item(key = "artists-scope") { Text("已有歌手", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary) }
                            val artists = if (matching) libraryResults.artists else emptyList()
                            if (artists.isEmpty()) item(key = "artists-empty") { Text("没有找到歌手", color = scheme.onSurfaceVariantSummary) }
                            items(artists, key = { "artist-${it.id}" }) { artist ->
                                LibraryResultRow(artist.name, "${artist.songCount} 首歌曲", "查看") { onArtistClick(artist.id) }
                            }
                        } else {
                        val groups = groupSearchResults(state.platforms, state.searchedKeyword, state.originalFirst)
                        val lyricMatches = if (lyricResults.keyword == state.searchedKeyword) lyricResults.matches else emptyList()
                        val localSongs = ((if (matching) libraryResults.songs else emptyList()) + lyricMatches.map { it.song }).distinctBy { it.id }
                        val mergedIds = groups.flatMap { matchingLibrarySongs(it, localSongs) }.map { it.id }.toSet()
                        val remainingSongs = localSongs.filterNot { it.id in mergedIds }
                        items(remainingSongs, key = { "song-${it.id}" }) { song ->
                            LibraryResultRow(song.title, listOfNotNull(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), "",
                                coverUri = song.coverUri, showCover = true, song = song,
                                lyric = lyricMatches.firstOrNull { it.song.id == song.id },
                                membership = playlistMembership(listOf(song.id), playlists),
                                qualityBadgeLabel = song.libraryQualityLabel,
                                isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(song),
                                marqueeEnabled = com.muses.player.core.ui.components.LocalSongMarqueeState.current.matches(song.id)) {
                                viewModel.playLibrarySong(song.id, remainingSongs)
                            }
                        }
                        if (state.searching) item(key = "online-searching") {
                            Text("正在搜索…", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
                        }
                        if (state.platforms.any { it.error != null }) item(key = "online-partial-error") {
                            Text("部分搜索结果暂不可用，可下拉刷新", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
                        }
                        if (!state.searching && !lyricResults.loading && remainingSongs.isEmpty() && state.totalResults == 0 && state.platforms.none { it.error != null }) {
                            item(key = "online-empty") {
                                Text("空空如也~", color = scheme.onSurfaceVariantSummary)
                            }
                        }
                        items(groups, key = { group -> "group-${group.first().platform}-${group.first().songId}" }) { group ->
                            val first = group.first()
                            val matchedLocal = matchingLibrarySongs(group, localSongs)
                            Column {
                                SearchResultRow(first, "", membership = searchMembership(group, localSongs, playlists),
                                    lyric = lyricMatches.firstOrNull { it.song in matchedLocal },
                                    onClick = { viewModel.playGroupedResult(first) })
                                if (group.size + matchedLocal.size > 1) {
                                    Text(
                                        text = "更多音源 · ${group.size + matchedLocal.size} 个  ›",
                                        fontSize = 12.sp, color = scheme.primary,
                                        modifier = Modifier.fillMaxWidth().clickable { selectedSourceSong = first }
                                            .padding(start = SongListLayout.horizontalPadding + SongListLayout.coverSize + SongListLayout.coverGap, bottom = 8.dp, top = 4.dp),
                                    )
                                }
                            }
                        }
                        val morePlatforms = state.platforms.filter { it.hasMore }
                        if (morePlatforms.isNotEmpty()) item(key = "online-more") {
                            LoadMoreRow(morePlatforms.any { it.loadingMore }) {
                                morePlatforms.filterNot { it.loadingMore }.forEach { viewModel.loadMore(it.platform) }
                            }
                        }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSourcesScreen(
    song: OnlineSearchResult,
    sources: List<OnlineSearchResult>,
    platforms: List<PlatformSearchState>,
    playlists: List<com.muses.player.core.data.repository.UserPlaylist>,
    librarySongs: List<com.muses.player.core.model.Song>,
    onBack: () -> Unit,
    onPlay: (OnlineSearchResult) -> Unit,
    onPlayLocal: (String, List<com.muses.player.core.model.Song>) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Scaffold(
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar(title = "更多音源", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = SongListLayout.contentHorizontalPadding,
                end = SongListLayout.contentHorizontalPadding,
                bottom = 16.dp + com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
            ),
            verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
        ) {
            item(key = "song-heading") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp)) {
                    Text(song.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(song.artist.orEmpty(), fontSize = 13.sp, color = scheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    Text("选择音源播放，或通过右侧菜单下载", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
                }
            }
            if (sources.isEmpty()) item(key = "sources-empty") {
                Text("搜索结果已更新，请返回后重试", color = scheme.onSurfaceVariantSummary)
            }
            items(sources, key = { "${it.platform}-${it.songId}" }) { source ->
                val platform = platforms.firstOrNull { it.platform == source.platform }
                SearchResultRow(source, platform?.displayName.orEmpty(),
                    membership = searchMembership(listOf(source), librarySongs, playlists), onClick = { onPlay(source) })
            }
            val localSources = matchingLibrarySongs(sources, librarySongs)
            items(localSources, key = { "local-${it.id}" }) { local ->
                LibraryResultRow(local.title, (listOf("本地") + listOfNotNull(local.artist, local.album).filter { it.isNotBlank() }).joinToString(" · "), "",
                    coverUri = local.coverUri, showCover = true, song = local,
                    qualityBadgeLabel = local.libraryQualityLabel,
                    membership = playlistMembership(listOf(local.id), playlists),
                    isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(local)) {
                    onPlayLocal(local.id, localSources)
                }
            }
        }
    }
}

@Composable
private fun LyricSnippet(match: LibraryLyricMatch) {
    val scheme = MiuixTheme.colorScheme
    Spacer(Modifier.height(SongListLayout.textGap))
    top.yukonga.miuix.kmp.basic.Text(
        text = buildAnnotatedString {
            append("词  ")
            append(match.snippet)
            addStyle(SpanStyle(color = scheme.primary), match.matchStart + 3, match.matchEnd + 3)
        },
        color = scheme.onSurfaceVariantSummary,
        fontSize = SongListLayout.subtitleSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LyricResultRow(match: LibraryLyricMatch, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val song = match.song
    Column(Modifier.fillMaxWidth()) {
        com.muses.player.core.ui.components.SongListItem(
            song = com.muses.player.core.ui.components.SongItem(
                id = song.id, title = song.title, artist = song.artist,
                albumTitle = song.album, coverUri = song.coverUri, qualityLabel = song.audioQuality,
            ),
            isCurrent = com.muses.player.core.ui.components.LocalSongMarqueeState.current.matches(song.id),
            isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(song),
            onClick = onClick,
        )
        top.yukonga.miuix.kmp.basic.Text(
            text = buildAnnotatedString {
                append(match.snippet)
                addStyle(SpanStyle(color = scheme.primary), match.matchStart, match.matchEnd)
            },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(
                start = SongListLayout.horizontalPadding + SongListLayout.coverSize + SongListLayout.coverGap,
                end = SongListLayout.horizontalPadding,
                bottom = 8.dp,
            ),
            color = scheme.onSurfaceVariantSummary,
            fontSize = SongListLayout.subtitleSize,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun LibraryResultRow(title: String, subtitle: String, detail: String,
    coverUri: String? = null, showCover: Boolean = false, isLiked: Boolean = false, qualityBadgeLabel: String? = null,
    membership: String? = null,
    song: com.muses.player.core.model.Song? = null, lyric: LibraryLyricMatch? = null,
    marqueeEnabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        showIndication = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SongListLayout.horizontalPadding, vertical = SongListLayout.verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCover) {
                com.muses.player.core.ui.components.MusesCover(uri = coverUri, size = SongListLayout.coverSize,
                    radius = com.muses.player.core.ui.components.MusesCoverRadius.SM)
                Spacer(Modifier.width(SongListLayout.coverGap))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = SongListLayout.titleSize, lineHeight = SongListLayout.titleLineHeight,
                    marqueeEnabled = marqueeEnabled,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank() || qualityBadgeLabel != null || isLiked) {
                    Spacer(Modifier.height(SongListLayout.textGap))
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.muses.player.core.ui.components.LocalTextMarqueeEnabled provides marqueeEnabled,
                    ) {
                    SongSubtitleWithQuality(
                        subtitle = subtitle,
                        label = qualityBadgeLabel,
                        isLiked = isLiked,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = SongListLayout.subtitleSize,
                            lineHeight = SongListLayout.subtitleLineHeight),
                        color = scheme.onSurfaceVariantSummary,
                    )
                    }
                }
                if (membership != null) {
                    Spacer(Modifier.height(SongListLayout.textGap))
                    Text(membership, fontSize = 11.sp, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                lyric?.let { LyricSnippet(it) }
            }
            if (song != null) com.muses.player.core.ui.components.OnlineSongDownloadAction(song)
            else Text(detail, fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
        }
    }
}

/** 平台筛选胶囊 */
@Composable
private fun PlatformPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (badge != null) "$label $badge" else label,
            fontSize = 13.sp,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 平台分组标题（含加载/错误态） */
@Composable
private fun PlatformSectionHeader(platform: PlatformSearchState) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = platform.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        when {
            platform.loading -> {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(13.dp))
            }
            platform.error != null -> {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = platform.error,
                    fontSize = 12.sp,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            platform.results.isNotEmpty() -> {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${platform.results.size} 首",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 单条搜索结果 */
@Composable
private fun SearchResultRow(
    result: OnlineSearchResult,
    sourceName: String,
    membership: String? = null,
    lyric: LibraryLyricMatch? = null,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val isLiked = com.muses.player.core.ui.components.LocalFavoriteSongs.current.isLiked(result.toSong("online"))
    androidx.compose.runtime.CompositionLocalProvider(
        com.muses.player.core.ui.components.LocalTextMarqueeEnabled provides
            com.muses.player.core.ui.components.LocalSongMarqueeState.current.matches("${result.platform}-${result.songId}"),
    ) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        showIndication = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SongListLayout.horizontalPadding, vertical = SongListLayout.verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.muses.player.core.ui.components.MusesCover(
                uri = result.coverUrl,
                size = SongListLayout.coverSize,
                radius = com.muses.player.core.ui.components.MusesCoverRadius.SM,
            )
            Spacer(Modifier.width(SongListLayout.coverGap))
            Column(modifier = Modifier.weight(1f)) {
                SongTitleWithBadge(
                    title = result.name,
                    label = result.performanceLabel,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = SongListLayout.titleSize,
                        lineHeight = SongListLayout.titleLineHeight),
                )
                val sub = listOfNotNull(sourceName, result.artist, result.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (sub.isNotBlank() || result.qualityLabel != null || isLiked) {
                    Spacer(Modifier.height(SongListLayout.textGap))
                    SongSubtitleWithQuality(
                        subtitle = sub,
                        label = result.qualityLabel,
                        isLiked = isLiked,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = SongListLayout.subtitleSize,
                            lineHeight = SongListLayout.subtitleLineHeight),
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                if (membership != null) {
                    Spacer(Modifier.height(SongListLayout.textGap))
                    Text(membership, fontSize = 11.sp, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                lyric?.let { LyricSnippet(it) }
            }
            Spacer(Modifier.width(SongListLayout.actionGap))
            com.muses.player.core.ui.components.OnlineSongDownloadAction(result.toSong("online"))
        }
    }
    }
}

/** 加载更多 */
@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text(
                text = "加载更多",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

