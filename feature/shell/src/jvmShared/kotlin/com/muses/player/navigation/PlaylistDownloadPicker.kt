package com.muses.player.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PlaylistDownloadPicker(
    selection: PlaylistDownloadSelection,
    onChange: (PlaylistDownloadSelection) -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    qualityContent: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = MiuixTheme.colorScheme
        Scaffold(modifier = Modifier.fillMaxSize().background(colors.surface).systemBarsPadding(),
            containerColor = colors.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        PickerTextAction(if (selection.allSelected) "全不选" else "全选") { onChange(selection.toggleAll()) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("选择下载歌曲", fontWeight = FontWeight.Medium) }
                        PickerTextAction("取消", onDismiss)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selection.selectedIds.size} / ${selection.songs.size} 首", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, color = colors.onBackgroundVariant)
                        Text("已下载自动跳过", style = MiuixTheme.textStyles.footnote1, color = colors.onBackgroundVariant)
                    }
                }
            },
            bottomBar = {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    MusesContentAction("下载（${selection.selectedIds.size} 首）", onDownload, Modifier.fillMaxWidth(),
                        primary = true, enabled = selection.selectedIds.isNotEmpty(), icon = TablerIcons.Download)
                }
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(selection.songs, key = { it.id }) { song ->
                    val checked = song.id in selection.selectedIds
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                        onValueChange = { onChange(selection.toggle(song.id)) }).padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (checked) TablerIcons.CheckCircle else TablerIcons.RadioButtonUnchecked, null,
                            tint = if (checked) colors.onSurface else colors.onBackgroundVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        MusesCover(song.coverUri, size = 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist ?: "未知歌手", color = colors.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            qualityContent()
        }
    }
}

@Composable
private fun PickerTextAction(text: String, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.body2)
    }
}
