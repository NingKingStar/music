package com.muses.player.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 内容页动作：有明确边界和触摸区域，沿用应用当前明暗主题。 */
@Composable
fun MusesContentAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val colors = MiuixTheme.colorScheme
    val foreground = if (primary) colors.surface else colors.onSurface
    Row(modifier.clip(RoundedCornerShape(14.dp))
        .background(if (primary) colors.onSurface else colors.surface)
        .border(1.dp, colors.onSurface.copy(alpha = if (primary) 0f else .12f), RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, contentDescription = null, tint = foreground.copy(alpha = if (enabled) 1f else .35f), modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text, color = foreground.copy(alpha = if (enabled) 1f else .35f), fontWeight = FontWeight.Medium)
    }
}

/** 表单专用底部弹层；确认在底部，内容可滚动且为键盘留空间。 */
@Composable
fun MusesFormSheet(title: String, onDismiss: () -> Unit, message: String? = null,
    confirmText: String, onConfirm: () -> Unit, confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false, dismissText: String = "取消",
    content: @Composable ColumnScope.() -> Unit) {
    MusesBottomSheet(title = title, onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .88f).padding(horizontal = 20.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message?.let { Text(it, color = MiuixTheme.colorScheme.onBackgroundVariant) }
                    content()
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MusesContentAction(dismissText, onDismiss, Modifier.weight(1f), enabled = !confirmLoading)
                    MusesContentAction(if (confirmLoading) "$confirmText…" else confirmText, onConfirm, Modifier.weight(1f), primary = true, enabled = confirmEnabled && !confirmLoading)
                }
            }
        }
    }
}
