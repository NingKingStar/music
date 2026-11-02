package com.muses.player.feature.shell.platform

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muses.player.core.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual val supportsInAppUpdate: Boolean = true

@Composable
actual fun AppUpdateHost() {
    val context = LocalContext.current
    val manager = remember { AndroidUpdateManager.get(context) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; manager.foreground() }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(observer)
        if (resumed) manager.foreground()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val state by manager.state.collectAsState()
    LaunchedEffect(resumed, state.downloadId, state.ready) {
        while (resumed && state.downloadId >= 0 && !state.ready) { manager.refresh(); delay(1_000) }
    }
}

@Composable
actual fun appUpdateHint(): String? {
    val context = LocalContext.current
    val manager = remember { AndroidUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    return if (manager.newer(state.release) && state.release?.versionName != state.ignored)
        if (state.ready) "新版已下载，等待安装" else "有新版本 ${state.release?.versionName}" else null
}

@Composable
actual fun InAppUpdateSection(versionName: String, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember { AndroidUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    var details by remember { mutableStateOf(false) }
    val release = state.release
    val newer = manager.newer(release)
    SettingsBlockTitle("应用更新")
    Card(Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(title = if (newer) "发现新版 ${release?.versionName}" else "检查更新",
            summary = state.error ?: state.status, enabled = !state.checking,
            onClick = { if (newer) details = true else manager.check() })
        BasicComponent(title = "当前版本 $versionName", summary = if (state.checkedAt == 0L) "尚未成功检查" else
            "上次检查：" + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.checkedAt)))
        SwitchPreference(title = "自动检查更新", summary = "每天首次打开时检查，不打断播放",
            checked = state.autoCheck, onCheckedChange = manager::setAutoCheck)
        SwitchPreference(title = "仅 Wi-Fi 自动下载安装包", summary = "安装前仍需你确认",
            checked = state.wifiDownload, onCheckedChange = manager::setWifiDownload)
        if (newer || state.error != null) BasicComponent(title = "重新检查", enabled = !state.checking && state.downloadId < 0, onClick = { manager.check() })
    }
    if (details && release != null) {
        MusesFormSheet(title = "Muses ${release.versionName}",
            message = "当前 $versionName · 安装包 %.1f MB".format(release.size / 1048576.0),
            confirmText = when { state.ready -> "立即安装"; state.downloadId >= 0 -> "正在下载…"; else -> "下载更新" },
            confirmEnabled = state.ready || (state.downloadId < 0 && release.sha256 != null),
            dismissText = "稍后", onDismiss = { details = false },
            onConfirm = { if (state.ready) manager.install() else manager.download() },
            content = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(release.notes.ifBlank { "此版本未提供更新说明" },
                        Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                    Text(state.error ?: (state.status + (state.progress?.let { " $it%" } ?: "")))
                    if (release.sha256 == null) Text("此版本缺少校验值，请前往 GitHub 下载")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.downloadId >= 0) MusesTextButton(text = "取消下载", onClick = manager::cancel)
                        MusesTextButton(text = "忽略本版", onClick = { manager.ignore(); details = false })
                        MusesTextButton(text = "GitHub 下载", onClick = { onOpenUrl(release.pageUrl) })
                    }
                }
            })
    }
}
