package com.muses.player.core.ui.components

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.icons.TablerIcons

/**
 * `.controls` —— 播放三键（上一曲/播放暂停/下一曲），lg 档（48/28）。
 *
 * 纯 UI + 回调：[isPlaying] 决定播放键图标，[compact] 为平板底部条收紧间距。
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    // gap clamp(24,10vw,44)：此处以简化档位表达，调用方如需精确 vw 可传 gap。
    gap: androidx.compose.ui.unit.Dp = if (compact) 12.dp else 24.dp,
) {
    val contentColor = LocalPlayerContentColor.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusesIconButton(
            onClick = onPrevious,
            imageVector = TablerIcons.SkipPreviousFill,
            contentDescription = "上一曲",
            size = MusesIconButtonSize.LG,
            tint = contentColor.copy(alpha = 0.9f),
        )
        MusesIconButton(
            onClick = onPlayPause,
            imageVector = if (isPlaying) TablerIcons.PauseFill else TablerIcons.PlayFill,
            contentDescription = if (isPlaying) "暂停" else "播放",
            size = MusesIconButtonSize.LG,
            tint = contentColor.copy(alpha = 0.92f),
        )
        MusesIconButton(
            onClick = onNext,
            imageVector = TablerIcons.SkipNextFill,
            contentDescription = "下一曲",
            size = MusesIconButtonSize.LG,
            tint = contentColor.copy(alpha = 0.9f),
        )
    }
}

/** 单曲循环哨兵：与 `androidx.media3.common.Player.REPEAT_MODE_ONE` 同值（=1），commonMain 不引 Media3。 */
const val PLAYER_REPEAT_ONE = 1

/**
 * `.mode-bar` —— 循环/随机/队列/更多四键，max-width 320，无 is-active（仅图标对 + aria-label）。
 *
 * 纯 UI + 回调：[repeatMode] 用 Media3 repeatMode 整型（1=单曲循环，见 [PLAYER_REPEAT_ONE]）。
 */
@Composable
fun PlayerModeBar(
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEditMeta: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 320.dp,
) {
    val contentColor = LocalPlayerContentColor.current
    Row(
        modifier
            .fillMaxWidth()
            .widthIn(max = maxWidth),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackModeButton(repeatMode, shuffleEnabled, onToggleRepeat)
        MusesIconButton(
            onClick = onOpenQueue,
            imageVector = TablerIcons.QueueMusic,
            contentDescription = "播放队列",
            tint = contentColor.copy(alpha = 0.8f),
        )
        MusesIconButton(
            onClick = onOpenEditMeta,
            imageVector = TablerIcons.MoreHorizontal,
            contentDescription = "更多",
            tint = contentColor.copy(alpha = 0.8f),
        )
    }
}
