package com.muses.player.feature.player.lyric

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 桌面端的桌面歌词入口保持原样（设置页「歌词」分组），这里不渲染任何东西，
 * 以免同一功能出现两个入口。
 */
@Composable
actual fun DesktopLyricsActionButton(tint: Color, modifier: Modifier) = Unit

@Composable
actual fun DesktopLyricsSettingRow(ink: Color, secondary: Color, modifier: Modifier) = Unit
