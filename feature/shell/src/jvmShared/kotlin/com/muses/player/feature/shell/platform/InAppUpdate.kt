package com.muses.player.feature.shell.platform

import androidx.compose.runtime.Composable

/**
 * 平台应用内更新抽口：
 * - 桌面 Windows（jvmMain actual）：完整的检查→下载→安装卡片；
 * - 安卓（androidMain actual）：系统托管下载、包信息与签名校验、用户确认安装。
 */
expect val supportsInAppUpdate: Boolean

/**
 * 更新宿主：负责前台刷新下载进度，以及「发现新版本」的一次性提示。
 *
 * [onOpenUpdate] 由调用方接到更新页；提示里的「查看更新」走它，不直接开始下载。
 * 桌面端没有启动提示，只保留设置页里的入口。
 */
@Composable
expect fun AppUpdateHost(onOpenUpdate: () -> Unit = {})

@Composable
expect fun appUpdateHint(): String?

@Composable
expect fun InAppUpdateSection(    versionName: String,
    onOpenUrl: (String) -> Unit,
    onOpenUpdate: () -> Unit,
)

@Composable
expect fun AppUpdateScreen(versionName: String, onBack: () -> Unit, onOpenUrl: (String) -> Unit)
