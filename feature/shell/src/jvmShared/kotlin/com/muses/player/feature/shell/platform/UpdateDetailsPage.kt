package com.muses.player.feature.shell.platform

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import com.muses.player.core.appupdate.DOMESTIC_UPDATE_ORIGIN
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class UpdatePageActions(val check: () -> Unit, val download: () -> Unit, val cancel: () -> Unit,
    val install: () -> Unit, val ignore: () -> Unit, val setAutoCheck: (Boolean) -> Unit, val setWifiDownload: (Boolean) -> Unit,
    val redownload: () -> Unit = {}, val restore: () -> Unit = {})

@Composable
internal fun UpdateDetailsPage(versionName: String, state: AndroidUpdateState, newer: Boolean,
    onBack: () -> Unit, onOpenUrl: (String) -> Unit, actions: UpdatePageActions) {
    val colors = MiuixTheme.colorScheme
    val release = state.release
    val downloading = state.downloadId >= 0 && !state.ready
    var more by remember { mutableStateOf(false) }
    val notes = remember(release?.notes) { releaseNoteBlocks(release?.notes.orEmpty()) }
    val primary = when {
        state.installing -> "正在准备安装…"
        state.verifying -> "正在校验…"
        state.checking -> "正在检查…"
        state.ready && newer -> "安装更新"
        downloading -> "正在下载…"
        newer && state.error != null && state.errorWork in listOf(UpdateWork.DOWNLOAD, UpdateWork.VERIFY) -> "重新下载"
        newer -> "下载更新"
        state.error != null -> "重新检查"
        else -> "检查更新"
    }
    val enabled = !state.checking && !state.verifying && !state.installing && !downloading && (!newer || state.ready || release?.sha256 != null)
    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            UpdateIcon(TablerIcons.ArrowBack, "返回", onBack)
            Text("应用更新", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            UpdateIcon(TablerIcons.MoreHorizontal, "更多选项", { more = true })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(colors.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                        Icon(TablerIcons.MusicNote, null, Modifier.size(38.dp), tint = colors.primary)
                    }
                    Text("Muses", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("当前版本 $versionName", color = colors.onSurface.copy(alpha = .5f), fontSize = 13.sp)
                    Text(if (newer) "发现新版本 ${release?.versionName}" else if (state.error != null) "暂未完成检查" else if (state.checking) "正在检查新版本" else if (state.checkedAt > 0L) "当前已是最新版本" else "保持最新，听得更好",
                        fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (newer && release != null) Text("安装包 %.1f MB".format(release.size / 1048576.0), fontSize = 12.sp, color = colors.onSurface.copy(alpha = .5f))
                }
            }
            if (state.error != null || downloading || state.ready) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.onSurface.copy(alpha = .045f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.status, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    state.error?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurface.copy(alpha = .65f)) }
                    if (downloading) {
                        if (state.progress != null) {
                            LinearProgressIndicator(progress = (state.progress!! / 100f).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                            Text(if (state.verifying) "下载完成，正在校验安装包" else "已下载 ${state.progress}%", fontSize = 12.sp, color = colors.onSurface.copy(alpha = .6f))
                        } else Text("等待网络或系统开始下载", fontSize = 12.sp, color = colors.onSurface.copy(alpha = .6f))
                        if (!state.verifying) UpdateTextAction("取消下载", actions.cancel)
                    }
                }
            }
            if (newer && release != null && notes.isNotEmpty()) {
                item { Text("本次更新", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                notes.forEachIndexed { index, block -> item("note:$index") {
                    when (block.kind) {
                        ReleaseNoteKind.TITLE -> Text(block.text, Modifier.padding(top = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        ReleaseNoteKind.BULLET -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("•", color = colors.primary); Text(block.text, fontSize = 14.sp, lineHeight = 22.sp, color = colors.onSurface.copy(alpha = .72f))
                        }
                        ReleaseNoteKind.PARAGRAPH -> Text(block.text, fontSize = 14.sp, lineHeight = 22.sp, color = colors.onSurface.copy(alpha = .72f))
                    }
                } }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("更新选项", fontSize = 13.sp, color = colors.onSurface.copy(alpha = .5f))
                    UpdateOption("自动检查更新", "每天检查一次，不打断播放", state.autoCheck, actions.setAutoCheck)
                    UpdateOption("仅 Wi-Fi 自动下载", "下载完成后，由你确认安装", state.wifiDownload, actions.setWifiDownload)
                    if (state.checkedAt > 0L) Text("上次成功检查：" + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.checkedAt)),
                        Modifier.padding(top = 8.dp), fontSize = 11.sp, color = colors.onSurface.copy(alpha = .4f))
                    if (newer && release?.sha256 == null) Text("此版本缺少校验值，请使用更多选项中的网页下载", fontSize = 12.sp, color = colors.onSurface.copy(alpha = .65f))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp + LocalBottomChromePadding.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) .98f else 1f)
            Box(Modifier.fillMaxWidth().height(50.dp).scale(scale).clip(RoundedCornerShape(17.dp)).background(colors.primary.copy(alpha = if (enabled) 1f else .2f))
                .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled) {
                    when { state.ready && newer -> actions.install(); newer -> actions.download(); else -> actions.check() }
                }, contentAlignment = Alignment.Center) {
                Text(primary, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = .45f))
            }
            Text("安装前会校验安装包，并由系统请求确认", Modifier.align(Alignment.CenterHorizontally), fontSize = 10.sp, color = colors.onSurface.copy(alpha = .4f))
        }
    }
    if (more) OverlayBottomSheet(show = true, title = "更新选项", onDismissRequest = { more = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.downloadId < 0 && !state.checking) UpdateMenuRow(TablerIcons.Refresh, "重新检查") { more = false; actions.check() }
            UpdateMenuRow(TablerIcons.Share, "网页下载") { more = false; onOpenUrl(release?.pageUrl ?: "$DOMESTIC_UPDATE_ORIGIN/") }
            if (release != null) {
                if (newer) UpdateMenuRow(TablerIcons.Close, "忽略本版提醒") { more = false; actions.ignore() }
                if (newer && state.ignored == release.versionName) UpdateMenuRow(TablerIcons.Refresh, "恢复新版本提醒") { more = false; actions.restore() }
                if (state.ready) UpdateMenuRow(TablerIcons.Download, "重新下载安装包") { more = false; actions.redownload() }
            }
        }
    }
}

@Composable
private fun UpdateOption(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp); Text(description, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp))
    }
}

@Composable
private fun UpdateTextAction(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp), color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
}

@Composable
private fun UpdateMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp)); Spacer(Modifier.width(16.dp)); Text(title, fontSize = 16.sp)
    }
}
