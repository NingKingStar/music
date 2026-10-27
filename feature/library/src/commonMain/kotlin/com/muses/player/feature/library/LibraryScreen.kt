package com.muses.player.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import org.koin.compose.viewmodel.koinViewModel

/** 曲库页的 Tab 文案（顺序即索引，勿随意调整） */
private val LibraryTabs = listOf("歌曲", "专辑", "艺术家")

/**
 * 曲库容器：顶部「曲库」标题与右侧的**歌曲 / 专辑 / 艺术家**切换，内容复用三个既有页面。
 *
 * 为什么需要它：窄屏底栏精简为「探索 / 曲库 / 设置」三项后，专辑与艺术家在窄屏失去了入口
 * （原先各自是顶层 Tab）；改由曲库页内部切换，既补回入口，也符合「曲库」这个命名。
 *
 * 三个子页以 `showTopBar = false` 嵌入：顶栏与切换由本容器统一提供，避免出现
 * 「曲库 + 歌曲」两层标题；歌曲页的随机播放工具条由本容器紧凑呈现。
 */
@Composable
fun LibraryScreen(
    playback: PlaybackPort?,
    onEnqueueScrape: (List<String>) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onScanSources: suspend () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showTabPopup by remember { mutableStateOf(false) }
    val songsViewModel: SongsViewModel = koinViewModel()
    val songs by songsViewModel.songs.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = "本地与云端音乐",
                onBack = onBack,
                actions = {
                    // 顶栏右侧显示当前曲库分类，点按后通过 Miuix 列表弹层切换。
                    Box(contentAlignment = Alignment.CenterEnd) {
                        IconButton(
                            onClick = { showTabPopup = true },
                            minWidth = 40.dp,
                            minHeight = 40.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = LibraryTabs[selectedTab],
                                    style = MiuixTheme.textStyles.body1,
                                    color = scheme.onBackground,
                                )
                                Icon(
                                    imageVector = TablerIcons.ChevronDown,
                                    contentDescription = "切换曲库分类",
                                    modifier = Modifier.size(20.dp),
                                    tint = scheme.onBackground,
                                )
                            }
                        }
                        WindowListPopup(
                            show = showTabPopup,
                            alignment = PopupPositionProvider.Align.End,
                            enableWindowDim = true,
                            onDismissRequest = { showTabPopup = false },
                        ) {
                            ListPopupColumn {
                                LibraryTabs.forEachIndexed { index, title ->
                                    DropdownImpl(
                                        text = title,
                                        optionSize = LibraryTabs.size,
                                        isSelected = selectedTab == index,
                                        index = index,
                                        onSelectedIndexChange = {
                                            selectedTab = index
                                            showTabPopup = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (selectedTab == 0 && songs.isNotEmpty()) {
                val shuffleAll: () -> Unit = {
                    playback?.apply {
                        play(songs.random().id, songs)
                        setShuffleEnabled(true)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> SongsPage(
                        playback = playback,
                        onEnqueueScrape = onEnqueueScrape,
                        showTopBar = false,
                        viewModel = songsViewModel,
                        onRefresh = {
                            onScanSources()
                            songsViewModel.refresh()
                        },
                    )
                    1 -> AlbumsPage(
                        onAlbumClick = onAlbumClick,
                        showTopBar = false,
                        onScanSources = onScanSources,
                    )
                    else -> ArtistsPage(
                        onArtistClick = onArtistClick,
                        showTopBar = false,
                        onScanSources = onScanSources,
                    )
                }
            }
        }
    }
}
