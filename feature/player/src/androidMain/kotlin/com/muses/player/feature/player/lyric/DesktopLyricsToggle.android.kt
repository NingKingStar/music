package com.muses.player.feature.player.lyric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.outline.PictureInPicture
import com.composables.icons.tabler.outline.PictureInPictureOn
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.platform.DesktopLyricsGate
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * 播放页右上角的快捷开关：图案是「小窗叠在屏幕上」，已开启时换成 on 变体，
 * 因此不用文字也能看出当前状态。
 */
@Composable
actual fun DesktopLyricsActionButton(tint: Color, modifier: Modifier) {
    val context = LocalContext.current
    val settings = koinInject<SettingsRepository>()
    val enabled by settings.desktopLyricsEnabled.collectAsState(false)
    val scope = rememberCoroutineScope()
    DesktopLyricsPermissionGuard(settings, enabled)
    MusesIconButton(
        onClick = { toggleDesktopLyrics(context, settings, scope, enabled) },
        imageVector = if (enabled) Tabler.Outline.PictureInPictureOn else Tabler.Outline.PictureInPicture,
        contentDescription = if (enabled) "关闭桌面歌词" else "开启桌面歌词",
        modifier = modifier,
        size = MusesIconButtonSize.MD,
        tint = tint,
    )
}

/** 歌词显示弹层里的整行开关，文案直接写清"在其他应用上层显示歌词"。 */
@Composable
actual fun DesktopLyricsSettingRow(ink: Color, secondary: Color, modifier: Modifier) {
    val context = LocalContext.current
    val settings = koinInject<SettingsRepository>()
    val enabled by settings.desktopLyricsEnabled.collectAsState(false)
    val scope = rememberCoroutineScope()
    DesktopLyricsPermissionGuard(settings, enabled)
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("桌面歌词", color = ink, fontSize = 14.sp)
            Text(
                if (enabled) "已开启 · 在其他应用上层显示歌词" else "在其他应用上层显示歌词，离开应用也能看",
                color = secondary, fontSize = 11.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { value ->
                if (!value) scope.launch { settings.setDesktopLyricsEnabled(false) }
                else toggleDesktopLyrics(context, settings, scope, false)
            },
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = Color(0xFF20222A),
                checkedTrackColor = ink,
                uncheckedThumbColor = ink.copy(alpha = .8f),
                uncheckedTrackColor = ink.copy(alpha = .18f),
            ),
            modifier = Modifier.semantics { contentDescription = "桌面歌词" },
        )
    }
}

/**
 * 开启/关闭桌面歌词；没有悬浮窗权限时直接把系统授权页拉起来，并用「待开启」标记
 * 交给播放服务在授权返回后自动补开——不要求用户自己回设置里再点一次。
 */
private fun toggleDesktopLyrics(
    context: android.content.Context,
    settings: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
) {
    when {
        enabled -> scope.launch { settings.setDesktopLyricsEnabled(false) }
        DesktopLyricsGate.canOverlay(context) -> scope.launch { settings.setDesktopLyricsEnabled(true) }
        else -> {
            DesktopLyricsGate.markPending(context)
            if (DesktopLyricsGate.openPermissionPage(context)) {
                MusesSnackbar.show("允许「显示在其他应用上层」后会自动开启桌面歌词")
            } else {
                DesktopLyricsGate.clearPending(context)
                MusesSnackbar.show("无法打开悬浮窗授权页，请在系统设置中授权")
            }
        }
    }
}

/**
 * 权限被系统撤销时兜底：把开关落回关闭并说一句，避免歌词静默消失、用户以为功能失效。
 * 设置页原有同样的检测，这里覆盖播放页与歌词弹层。
 */
@Composable
private fun DesktopLyricsPermissionGuard(settings: SettingsRepository, enabled: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (DesktopLyricsGate.canOverlay(context)) return@LifecycleEventObserver
            DesktopLyricsGate.clearPending(context)
            if (latestEnabled) {
                scope.launch { settings.setDesktopLyricsEnabled(false) }
                MusesSnackbar.show("桌面歌词已关闭：缺少悬浮窗权限")
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
