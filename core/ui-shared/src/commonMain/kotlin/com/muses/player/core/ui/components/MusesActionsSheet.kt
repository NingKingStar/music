package com.muses.player.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部弹出的操作单（miuix OverlayBottomSheet；原 SaltActionsSheet 的 Konsta Actions 语义）。
 *
 * label → sheet 标题；选项区使用默认 Card 布局。
 * 调用方的 `items` 不要传「取消」。
 * 渲染宿主默认根 Scaffold 全屏（根 Scaffold 由壳层统一提供）。
 */
data class MusesActionItem(
    val label: String,
    /** 危险动作（如删除）：红色文字 */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun MusesActionsSheet(
    opened: Boolean,
    onDismiss: () -> Unit,
    label: String,
    items: List<MusesActionItem>,
    songInfo: MusesActionSongInfo? = null,
    renderInRootScaffold: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    OverlayBottomSheet(
        show = opened,
        title = if (songInfo == null) label else null,
        onDismissRequest = onDismiss,
        renderInRootScaffold = renderInRootScaffold,
        content = {
            Column {
                songInfo?.let { MusesActionSongHeader(it) }
                Card(modifier = Modifier.padding(bottom = 16.dp)) {
                    items.forEach { item ->
                        if (item.destructive) {
                            BasicComponent(
                                title = item.label,
                                titleColor = BasicComponentColors(color = scheme.error, disabledColor = scheme.error),
                                onClick = item.onClick,
                            )
                        } else {
                            BasicComponent(title = item.label, onClick = item.onClick)
                        }
                    }
                }
            }
        },
    )
}
