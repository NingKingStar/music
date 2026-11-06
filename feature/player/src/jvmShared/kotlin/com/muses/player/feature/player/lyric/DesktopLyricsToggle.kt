package com.muses.player.feature.player.lyric

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 桌面歌词的快捷开关（悬浮在其他应用上层的歌词）。
 *
 * 桌面版有自己独立的「设置 → 歌词 → 桌面悬浮歌词」入口和悬浮窗口，这里的实现为空，
 * 避免 PC 端出现第二个重复入口。
 */
@Composable
expect fun DesktopLyricsActionButton(tint: Color, modifier: Modifier = Modifier)

/**
 * 歌词显示弹层里的桌面歌词分区（手机端专用）。
 *
 * 「译 / 音 / 词」是内容维度的开关，桌面歌词是环境维度的能力，混在一列里看不出来；
 * 而悬浮条本身不能放太多控件（而且要能锁定后仍可调），所以开关、锁定、字号、颜色都收在这里。
 */
@Composable
expect fun DesktopLyricsSettingSection(ink: Color, secondary: Color, modifier: Modifier = Modifier)
