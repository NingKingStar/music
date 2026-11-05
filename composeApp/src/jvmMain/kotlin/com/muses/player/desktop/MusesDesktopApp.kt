package com.muses.player.desktop

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.muses.player.core.ui.theme.MusesTheme
import com.muses.player.desktop.ui.DesktopShell
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

/**
 * 桌面主界面（U23 切共享壳）：标题栏（jvmMain 桌面专属：无边框窗口拖拽/控制）
 * + 双端共享 [MusesApp]（CMP Navigation + TabsLayout，1280 宽窗口天然落 aside
 * 260px 侧栏形态；全局短提示经 MusesSnackbar 挂在共享壳根 Scaffold 槽）。
 *
 * 历史：S3b 自绘 220dp 侧栏 + DesktopDestination enum 切屏（无返回栈）——U23 废弃，
 * 桌面获得与安卓一致的完整路由（曲库/详情/WebDAV/播放队列/刮削审核流）。
 */
@Composable
fun WindowScope.MusesDesktopApp(
    windowState: WindowState,
    onClose: () -> Unit,
) {
    KoinApplication(configuration = koinConfiguration { modules(desktopAppModules) }) {
        MusesTheme {
            DesktopLyricsWindow()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    DesktopTitleBar(windowState, onClose)
                    // 共享导航壳（hazeState 由 TabsLayout 内部 provide，磨砂导航/迷你条真磨砂）
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                                DesktopShell(onExit = onClose)
                    }
                }
            }
        }
    }
}
