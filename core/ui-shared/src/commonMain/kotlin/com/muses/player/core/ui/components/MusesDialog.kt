package com.muses.player.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 对话框封装（miuix OverlayDialog；替换 M3 AlertDialog）。
 *
 * 设计说明：
 * - 调用方保持原有 `?.let { MusesDialog(...) }` 条件组合写法，内部固定 `show = true`，
 *   与 [MusesActionsSheet] 的 `opened` 模式区分：对话框由是否组合决定显隐。
 * - `title` 对应原 `AlertDialog.title` 的纯文本部分，原先 `Text(title)` 直接传字符串即可。
 * - `message` 对应原 `text` 为纯文本的场景，走 `summary` 槽位；富文本或表单场景走 [content]。
 * - 双按钮确认统一为左侧「取消」、右侧「确定」，左右等宽；危险确认走 `destructive = true`（红色）。
 * - 不可取消场景（如扫描进度）：传 `onDismiss = {}` 且不传 `dismissText`，与原来保持一致。
 * - 渲染宿主：默认 `renderInRootScaffold = true`，经根 Scaffold（MusesApp）全屏呈现，
 *   根 Scaffold 由壳层统一提供，调用方无需关心。
 */
@Composable
fun MusesDialog(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmText: String? = null,
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    confirmLoading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val hasTwoActions = dismissText != null && confirmText != null
    OverlayDialog(
        show = true,
        title = title,
        summary = message,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                content()
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (hasTwoActions) {
                        Arrangement.spacedBy(8.dp)
                    } else {
                        Arrangement.spacedBy(8.dp, Alignment.End)
                    },
                ) {
                    if (dismissText != null) {
                        MusesTextButton(
                            text = dismissText,
                            onClick = onDismiss,
                            modifier = if (hasTwoActions) Modifier.weight(1f) else Modifier,
                        )
                    }
                    if (confirmText != null) {
                        MusesTextButton(
                            text = confirmText,
                            onClick = onConfirm,
                            enabled = confirmEnabled && !confirmLoading,
                            primary = true,
                            loading = confirmLoading,
                            modifier = if (hasTwoActions) Modifier.weight(1f) else Modifier,
                        )
                    }
                }
            }
        },
    )
}
