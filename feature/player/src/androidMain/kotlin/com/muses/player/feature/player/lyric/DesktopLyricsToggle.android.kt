package com.muses.player.feature.player.lyric

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.composables.icons.tabler.outline.Minus
import com.composables.icons.tabler.outline.PictureInPicture
import com.composables.icons.tabler.outline.PictureInPictureOn
import com.composables.icons.tabler.outline.Plus
import com.composables.icons.tabler.outline.Refresh
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.platform.DesktopLyricsGate
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Icon
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
        onClick = { toggleOverlayLyrics(context, scope, enabled, DesktopLyricsGate.TARGET_DESKTOP, settings::setDesktopLyricsEnabled) },
        imageVector = if (enabled) Tabler.Outline.PictureInPictureOn else Tabler.Outline.PictureInPicture,
        contentDescription = if (enabled) "关闭桌面歌词" else "开启桌面歌词",
        modifier = modifier,
        size = MusesIconButtonSize.MD,
        tint = tint,
    )
}

/**
 * 「词」弹层里的桌面歌词分区：开关、锁定、字号、颜色都收在这里。
 *
 * 悬浮条只保留播放与锁定/关闭，样式调节放在这里，锁定之后也仍然能调回来。
 */
@Composable
actual fun DesktopLyricsSettingSection(ink: Color, secondary: Color, modifier: Modifier) {
    val context = LocalContext.current
    val settings = koinInject<SettingsRepository>()
    val enabled by settings.desktopLyricsEnabled.collectAsState(false)
    val locked by settings.desktopLyricsLocked.collectAsState(false)
    val statusBar by settings.statusBarLyricsEnabled.collectAsState(false)
    val size by settings.desktopLyricsFontSize.collectAsState(22L)
    val color by settings.desktopLyricsColor.collectAsState(0L)
    val scope = rememberCoroutineScope()
    DesktopLyricsPermissionGuard(settings, enabled)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                    else toggleOverlayLyrics(context, scope, false, DesktopLyricsGate.TARGET_DESKTOP, settings::setDesktopLyricsEnabled)
                },
                colors = lyricsSwitchColors(ink),
                modifier = Modifier.semantics { contentDescription = "桌面歌词" },
            )
        }
        SettingLine(
            title = "状态栏歌词",
            summary = if (statusBar) "已开启 · 顶部状态栏显示当前一句，不挡操作" else "顶部状态栏显示当前一句，不拦截触摸",
            ink = ink, secondary = secondary,
        ) {
            Switch(
                checked = statusBar,
                onCheckedChange = { value ->
                    if (!value) scope.launch { settings.setStatusBarLyricsEnabled(false) }
                    else toggleOverlayLyrics(context, scope, false, DesktopLyricsGate.TARGET_STATUS_BAR, settings::setStatusBarLyricsEnabled)
                },
                colors = lyricsSwitchColors(ink),
                modifier = Modifier.semantics { contentDescription = "状态栏歌词" },
            )
        }
        if (enabled) {
            SettingLine(
                title = "锁定点击穿透",
                summary = if (locked) "已锁定：悬浮条不再响应触摸，在这里解锁" else "锁定后悬浮条不挡触摸，需要时回到这里解锁",
                ink = ink, secondary = secondary,
            ) {
                Switch(
                    checked = locked,
                    onCheckedChange = { value -> scope.launch { settings.setDesktopLyricsLocked(value) } },
                    colors = lyricsSwitchColors(ink),
                    modifier = Modifier.semantics { contentDescription = "锁定点击穿透" },
                )
            }
            SettingLine(title = "歌词字号", summary = "当前 ${size}px", ink = ink, secondary = secondary) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    DotGlyph(Tabler.Outline.Minus, "缩小歌词", ink) { scope.launch { settings.setDesktopLyricsFontSize((size - 2).coerceAtLeast(14)) } }
                    Text("$size", color = ink, fontSize = 13.sp)
                    DotGlyph(Tabler.Outline.Plus, "放大歌词", ink) { scope.launch { settings.setDesktopLyricsFontSize((size + 2).coerceAtMost(40)) } }
                }
            }
            SettingLine(title = "歌词颜色", summary = if (color == 0L) "跟随封面自动配色" else "已自选颜色", ink = ink, secondary = secondary) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0xFF007AFFL, 0xFF00C853L, 0xFFE53935L, 0xFFFFFFFFL).forEach { argb ->
                        val chosen = color == argb
                        Box(Modifier.size(24.dp).clip(CircleShape).clickable(
                            onClickLabel = "歌词颜色") { scope.launch { settings.setDesktopLyricsColor(argb) } }
                            .semantics { contentDescription = "歌词颜色" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(18.dp).background(Color(argb.toInt()), CircleShape)
                                .border(if (chosen) 2.dp else 1.dp,
                                    if (chosen) ink else ink.copy(alpha = .28f), CircleShape))
                        }
                    }
                    DotGlyph(Tabler.Outline.Refresh, "跟随封面自动配色", ink, active = color == 0L) {
                        scope.launch { settings.setDesktopLyricsColor(0L) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingLine(title: String, summary: String, ink: Color, secondary: Color,
    trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = ink, fontSize = 13.sp)
            Text(summary, color = secondary, fontSize = 11.sp)
        }
        trailing()
    }
}

@Composable
private fun lyricsSwitchColors(ink: Color) = SwitchDefaults.switchColors(
    checkedThumbColor = Color(0xFF20222A),
    checkedTrackColor = ink,
    uncheckedThumbColor = ink.copy(alpha = .8f),
    uncheckedTrackColor = ink.copy(alpha = .18f),
)

/** 弹层里的小圆钮：按压轻微缩放，颜色跟随歌词墨色而不是系统主题。 */
@Composable
private fun DotGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color,
    active: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(30.dp).scale(if (pressed) 0.9f else 1f).clip(CircleShape)
        .background(if (pressed) ink.copy(alpha = .10f) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null,
            tint = ink.copy(alpha = if (active) 1f else .62f), modifier = Modifier.size(16.dp))
    }
}

/**
 * 开启/关闭桌面歌词；没有悬浮窗权限时直接把系统授权页拉起来，并用「待开启」标记
 * 交给播放服务在授权返回后自动补开——不要求用户自己回设置里再点一次。
 */
private fun toggleOverlayLyrics(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
    target: String,
    apply: suspend (Boolean) -> Unit,
) {
    when {
        enabled -> scope.launch { apply(false) }
        DesktopLyricsGate.canOverlay(context) -> scope.launch { apply(true) }
        else -> {
            DesktopLyricsGate.markPending(context, target)
            if (DesktopLyricsGate.openPermissionPage(context)) {
                val name = if (target == DesktopLyricsGate.TARGET_STATUS_BAR) "状态栏歌词" else "桌面歌词"
                MusesSnackbar.show("允许「显示在其他应用上层」后会自动开启$name")
            } else {
                DesktopLyricsGate.clearPending(context)
                MusesSnackbar.show("无法打开悬浮窗授权页，请在系统设置中授权")
            }
        }
    }
}

/**
 * 权限被系统撤销时兜底：把开关落回关闭并说一句，避免歌词静默消失、用户以为功能失效。
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
            scope.launch {
                if (settings.statusBarLyricsEnabled.first()) settings.setStatusBarLyricsEnabled(false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
