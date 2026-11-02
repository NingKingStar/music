package com.muses.player.feature.shell.platform

import androidx.compose.runtime.Composable

/**
 * 平台应用内更新抽口：
 * - 桌面 Windows（jvmMain actual）：完整的检查→下载→安装卡片；
 * - 安卓（androidMain actual）：系统托管下载、包信息与签名校验、用户确认安装。
 */
expect val supportsInAppUpdate: Boolean

@Composable
expect fun AppUpdateHost()

@Composable
expect fun appUpdateHint(): String?

@Composable
expect fun InAppUpdateSection(
    versionName: String,
    onOpenUrl: (String) -> Unit,
    onOpenUpdate: () -> Unit,
)

@Composable
expect fun AppUpdateScreen(versionName: String, onBack: () -> Unit, onOpenUrl: (String) -> Unit)
