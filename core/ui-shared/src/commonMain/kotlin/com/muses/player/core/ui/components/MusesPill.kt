package com.muses.player.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一的紧凑胶囊按钮（替代旧的 MusesTextButton 文字按钮）。
 *
 * 设计口径：50% 圆角、纯色块、13sp、无描边；选中态用主色实心，未选中用 surfaceContainerHigh。
 * 与搜索页的分类胶囊（歌曲/专辑/歌手）同一套视觉，避免同屏出现两种年代感。
 */
@Composable
fun MusesPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    !enabled -> scheme.surfaceContainerHigh.copy(alpha = .5f)
                    selected -> scheme.primary
                    else -> scheme.surfaceContainerHigh
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = when {
                !enabled -> scheme.onSurface.copy(alpha = .45f)
                selected -> scheme.onPrimary
                else -> scheme.onSurface.copy(alpha = .85f)
            },
            maxLines = 1,
        )
        trailing?.invoke()
    }
}
