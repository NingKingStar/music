package com.muses.player.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 歌单、榜单共用紧凑操作栏；批量下载的选歌流程由上层提供。 */
@Composable
fun SongListActions(count: Int, searching: Boolean, onPlay: () -> Unit, onSearch: () -> Unit,
    onDownload: (() -> Unit)? = null, downloadEnabled: Boolean = true,
    onManageDownloads: (() -> Unit)? = null, filtered: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = count > 0, onClick = onPlay)
            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(colors.onSurface), contentAlignment = Alignment.Center) {
                Icon(TablerIcons.PlayFill, null, tint = colors.surface, modifier = Modifier.size(18.dp))
            }
            Text(if (filtered) "播放结果" else "播放全部", Modifier.padding(start = 9.dp), fontWeight = FontWeight.Medium)
            Text(" · $count", color = colors.onBackgroundVariant)
        }
        MusesIconButton(onClick = onSearch, imageVector = TablerIcons.Search,
            contentDescription = if (searching) "收起列表搜索" else "搜索当前列表")
        onDownload?.let { MusesIconButton(onClick = it, enabled = downloadEnabled, imageVector = TablerIcons.Download, contentDescription = "选择歌曲下载") }
        onManageDownloads?.let { MusesIconButton(onClick = it, imageVector = TablerIcons.Download, contentDescription = "下载管理") }
    }
}
