package com.muses.player.core.ui.components

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import top.yukonga.miuix.kmp.basic.Icon
import com.muses.player.core.ui.components.MarqueeText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 曲库主页共用组件（曲库主页共用化）。
 *
 * 背景：安卓 `feature:library` 的 SongsScreen/AlbumsScreen/ArtistsScreen（`Screens.kt`，
 * 旧列表屏）与桌面 `LibraryScreen`（复刻最小版）各自为政。本文件把三者 UI 结构中的
 * 平台无关部分上收：标签页导航、自适应网格、搜索框、曲目行复用。
 *
 * 范围（对照任务）：
 * - 在内：LibraryTab 数据类 + tab 栏、SongGrid（自适应网格，复用 SongListItem/SongItem）、
 *   搜索框（平台无关的 BasicTextField 实现，随 SettingsScreen 的 SaltTextField 同策略）。
 * - 在外：播放页、刮削页、多选/删除/刮削入队等业务逻辑（留平台侧经回调注入）；
 *   排序下拉——两端现状均无排序 UI，不新增。
 *
 * 约束：commonMain 零安卓 import；纯 UI + 回调，不接触 Room/DAO/Repository。
 * 调用方映射：安卓从 `core:model` Song/Album/Artist 映射而来；
 * 桌面从 `core:common` 的 SongEntity/AlbumEntity/ArtistEntity 映射而来。
 */

// ---------------------------------------------------------------------------
// 标签页
// ---------------------------------------------------------------------------

/** 曲库主页标签页（对照安卓 NavDestination.Primary 的曲库三项：歌曲/专辑/艺术家） */
enum class LibraryTab(val label: String) {
    Songs("歌曲"),
    Albums("专辑"),
    Artists("艺术家"),
}

/**
 * 曲库标签栏 —— 纯 UI 横排三按钮 + 底部 2dp 选中指示条。
 *
 * 两端均无 Web 直接对照（Web/安卓用侧边栏路由切换，桌面侧边栏只有「曲库」一项），
 * 按 Salt 风格自创：选中 primary 色 SemiBold，未选中 text2。
 *
 * @param selected 当前选中页
 * @param onTabSelect 切换回调（调用方管理状态）
 */
@Composable
fun LibraryTabBar(
    selected: LibraryTab,
    onTabSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTabSelect(tab) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.label,
                    color = if (isSelected) scheme.primary else scheme.onBackgroundVariant,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 2.dp)
                        .background(
                            color = if (isSelected) scheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索框
// ---------------------------------------------------------------------------

/**
 * 曲库搜索框 —— 对照安卓 SongsPage `.songs-page__searchbar`
 * （搜索图标 + BasicTextField + 取消按钮，同 navbar 玻璃无分界）。
 *
 * 纯 UI，过滤逻辑由调用方持有（安卓 SongsViewModel.updateSearchQuery / 桌面本地过滤）。
 */
@Composable
fun LibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(scheme.onBackground.copy(alpha = .045f))
            .clickable { focusRequester.requestFocus(); keyboard?.show() }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MiuixTheme.textStyles.body1,
                    color = scheme.onBackgroundVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onBackground),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) MusesIconButton(onClick = { onValueChange("") },
            imageVector = TablerIcons.Close, contentDescription = "清空搜索", size = MusesIconButtonSize.SM)
        }
        AnimatedVisibility(visible = focused || value.isNotEmpty(), enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            MusesTextButton(text = "取消", onClick = {
                onCancel()
                focusManager.clearFocus()
                keyboard?.hide()
            })
        }
    }
}

// ---------------------------------------------------------------------------
// 歌曲列表（复用 SongListItem/SongItem）
// ---------------------------------------------------------------------------

/**
 * 曲库歌曲列表 —— LazyColumn + 共用 [SongListItem]。
 *
 * @param songs 调用方映射后的展示数据（安卓 Song→SongItem / 桌面 SongEntity→SongItem）
 * @param currentSongId 当前播放曲 id；null = 无高亮（安卓旧 Screens.kt 即无此概念）
 * @param onPlay 点播回调（调用方注入播放逻辑，传 songId）
 * @param onLongClick 长按回调（null = 不支持长按）
 * @param contentPadding 列表内边距（调用方按需叠加底部悬浮件避让，见 BottomChrome）
 */
@Composable
fun LibrarySongList(
    songs: List<SongItem>,
    currentSongId: String?,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = SongListLayout.contentHorizontalPadding),
) {
    if (songs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MusesEmpty(
                title = "空空如也~",
                bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(SongListLayout.itemSpacing),
        ) {
            items(songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    showCover = true,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlay(song.id) },
                    onLongClick = onLongClick?.let { cb -> { cb(song.id) } },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 专辑/艺术家网格（自适应网格）
// ---------------------------------------------------------------------------

/** 专辑卡片展示数据（安卓 AlbumCard / 桌面 AlbumEntity + covers 映射而来） */
data class LibraryAlbumItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    val songCount: Int = 0,
    val coverUri: String? = null,
)

/** 艺术家卡片展示数据（安卓 ArtistCard / 桌面 ArtistEntity + covers 映射而来） */
data class LibraryArtistItem(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val coverUri: String? = null,
)

/**
 * 自适应网格列数口径（R4 导航/断点收敛：与安卓 LibraryGridPages 同口径）。
 *
 * - 平板/桌面（调用方判定宽 ≥768dp）：`GridCells.Adaptive(180.dp)`，对齐 Web auto-fill；
 * - 手机：恒两列。
 * 判定权在调用方（commonMain 无窗口信息）：安卓用 `screenWidthDp >= 768`，桌面恒 true。
 */
@Composable
fun LibraryAlbumGrid(
    albums: List<LibraryAlbumItem>,
    isTablet: Boolean,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 16.dp,
        bottom = 16.dp,
    ),
) {
    val scheme = MiuixTheme.colorScheme
    if (albums.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MusesEmpty(
                title = "空空如也~",
                bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = if (isTablet) GridCells.Adaptive(180.dp) else GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                // __card：surface-1 圆角卡（对照 LibraryGridPages：radius-card + padding sub(12)）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .squircleClip(12.dp)
                        .background(scheme.surfaceVariant)
                        .clickable { onAlbumClick(album.id) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // __cover：满宽 1:1，专辑 radius-sm
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .squircleClip(8.dp)
                            .background(scheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        LibraryGridCover(uri = album.coverUri, modifier = Modifier.fillMaxSize())
                    }
                    // __info：标题 17/600 两行省略 + meta 13 text2
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = album.title,
                            style = MiuixTheme.textStyles.main,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = (17 * 1.3).sp,
                            color = scheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${album.songCount} 首歌曲",
                            style = MiuixTheme.textStyles.footnote1,
                            lineHeight = (13 * 1.35).sp,
                            color = scheme.onBackgroundVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!album.artist.isNullOrBlank()) {
                            Text(
                                text = album.artist,
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onBackgroundVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 艺术家网格 —— 圆形封面 + 居中排版（艺术家特有，对照 LibraryGridPages.ArtistsPage） */
@Composable
fun LibraryArtistGrid(
    artists: List<LibraryArtistItem>,
    isTablet: Boolean,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 16.dp,
        bottom = 16.dp,
    ),
) {
    val scheme = MiuixTheme.colorScheme
    if (artists.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MusesEmpty(
                title = "空空如也~",
                bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = if (isTablet) GridCells.Adaptive(180.dp) else GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .squircleClip(12.dp)
                        .background(scheme.surfaceVariant)
                        .clickable { onArtistClick(artist.id) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // __cover：圆形（艺术家特有）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(scheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        LibraryGridCover(uri = artist.coverUri, modifier = Modifier.fillMaxSize())
                    }
                    // __info：居中排版（艺术家特有）
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = artist.name,
                            style = MiuixTheme.textStyles.main,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = (17 * 1.3).sp,
                            color = scheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${artist.songCount} 首歌曲",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 网格卡片封面：满宽裁切图；无封面时 surface-2 底 + 音符占位
 * （对照 LibraryGridPages.GridCover：Web 版 getAlbumCoverSrc 空值回退 + MCover 占位）。
 */
@Composable
private fun LibraryGridCover(uri: String?, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Box(modifier.background(scheme.surface), contentAlignment = Alignment.Center) {
        if (!uri.isNullOrBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                TablerIcons.MusicNote,
                contentDescription = null,
                tint = scheme.onBackgroundVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
