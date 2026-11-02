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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
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

/** 设置仅保留入口，下载与选项统一在独立页面中。 */
@Composable
actual fun InAppUpdateSection(versionName: String, onOpenUrl: (String) -> Unit, onOpenUpdate: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { AndroidUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(colors.surface).clickable(onClick = onOpenUpdate).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
            Icon(TablerIcons.Refresh, null, Modifier.size(20.dp), tint = colors.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("应用更新", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(if (manager.newer(state.release)) "发现新版 ${state.release?.versionName}" else "当前版本 $versionName", color = colors.onSurface.copy(alpha = .55f), fontSize = 12.sp)
        }
        Icon(TablerIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .4f))
    }
}

@Composable
actual fun AppUpdateScreen(versionName: String, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember { AndroidUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    val actions = remember(manager) { UpdatePageActions(check = { manager.check() }, download = { manager.download() },
        cancel = manager::cancel, install = manager::install, ignore = manager::ignore,
        setAutoCheck = manager::setAutoCheck, setWifiDownload = manager::setWifiDownload, redownload = manager::redownload) }
    UpdateDetailsPage(versionName, state, manager.newer(state.release), onBack, onOpenUrl, actions)
}
