package com.muses.player.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentSearches(history: List<String>, onSearch: (String) -> Unit,
    onRemove: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 20.dp,
            bottom = 24.dp + com.muses.player.core.ui.theme.LocalBottomChromePadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近搜索", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.title4)
            if (history.isNotEmpty()) {
                MusesTextButton(text = if (editing) "完成" else "管理", onClick = { editing = !editing })
                if (editing) MusesTextButton(text = "清空", onClick = { confirmClear = true })
            }
        }
        if (history.isEmpty()) {
            Text("搜一首歌，或一位喜欢的歌手", color = colors.onSurfaceVariantSummary)
            Text("最近搜过的内容会留在这里，方便下次再听。", color = colors.onSurfaceVariantSummary)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (if (expanded || editing) history else history.take(8)).forEach { keyword ->
                    Row(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp))
                        .background(colors.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                        Text(keyword, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f, fill = false).clickable(enabled = !editing) { onSearch(keyword) }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                        if (editing) MusesIconButton(onClick = { onRemove(keyword) }, imageVector = TablerIcons.Close,
                            contentDescription = "删除搜索记录：$keyword", size = MusesIconButtonSize.SM)
                    }
                }
            }
            if (history.size > 8 && !editing) MusesTextButton(text = if (expanded) "收起" else "展开全部",
                onClick = { expanded = !expanded })
        }
    }
    if (confirmClear) MusesDialog(title = "清空搜索记录？", message = "清空后无法恢复，不影响歌单和播放记录。",
        confirmText = "清空", dismissText = "取消", onDismiss = { confirmClear = false },
        onConfirm = { onClear(); confirmClear = false; editing = false })
}
