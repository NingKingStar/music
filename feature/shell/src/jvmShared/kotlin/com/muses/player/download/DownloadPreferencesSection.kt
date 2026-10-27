package com.muses.player.download

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.model.download.downloadTargetAvailable
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.SettingsBlockTitle
import com.muses.player.feature.sources.rememberLocalFolderPicker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 设置页打开中的下载设置弹窗（null = 未打开）。 */
private enum class DownloadSettingSheet { TARGET, QUALITY, PLAY_QUALITY, DOWNLOAD_MODE, DEVICE_DIRECTORY }

/**
 * 设置页「下载」分组：保存位置、下载品质、设备下载目录。
 *
 * 下载队列页只负责任务推进，这三项作为全局默认集中在这里，避免同一个设置在两个页面各说一套。
 */
@Composable
fun DownloadPreferencesSection() {
    val settings = koinInject<SettingsRepository>()
    val manager = koinInject<DownloadManager>()
    val sources by manager.availableSources.collectAsState()
    val defaultTarget by manager.defaultTarget.collectAsState()
    val deviceDirectory by settings.downloadDeviceDirectory.collectAsState("")
    val quality by settings.downloadPreferredQuality.collectAsState("320k")
    val playQuality by settings.onlinePreferredQuality.collectAsState("320k")
    val chooseQuality by settings.chooseDownloadQuality.collectAsState(true)
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<DownloadSettingSheet?>(null) }
    val targets = remember(sources) { downloadTargetOptions(sources) }
    // 安卓走 SAF 目录选择并持久化授权；桌面走系统目录对话框
    val pickDeviceDirectory = rememberLocalFolderPicker { path ->
        scope.launch { settings.setDownloadDeviceDirectory(path) }
    }

    SettingsBlockTitle("下载")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = "在线播放优先音质",
            summary = qualityLabel(playQuality),
            onClick = { sheet = DownloadSettingSheet.PLAY_QUALITY },
        )
        ArrowPreference(
            title = "单曲下载方式",
            summary = if (chooseQuality) "下载前选择音质" else "按默认音质直接下载",
            onClick = { sheet = DownloadSettingSheet.DOWNLOAD_MODE },
        )
        ArrowPreference(
            title = "保存位置",
            // 保存位置指向已删除的音源时，提示下载会自动回退到设备下载目录
            summary = defaultTarget.displayLabel(sources) +
                if (downloadTargetAvailable(defaultTarget, sources)) "" else "（音源已删除）",
            onClick = { sheet = DownloadSettingSheet.TARGET },
        )
        ArrowPreference(
            title = "下载品质",
            summary = qualityLabel(quality),
            onClick = { sheet = DownloadSettingSheet.QUALITY },
        )
        ArrowPreference(
            title = "设备下载目录",
            summary = deviceDirectory.ifBlank { "系统下载目录 / Muses" },
            onClick = { sheet = DownloadSettingSheet.DEVICE_DIRECTORY },
        )
    }

    MusesActionsSheet(
        opened = sheet == DownloadSettingSheet.TARGET,
        onDismiss = { sheet = null },
        label = "保存位置",
        items = targets.map { target ->
            MusesActionItem(target.label) {
                manager.setDefaultTarget(target)
                sheet = null
            }
        },
    )
    MusesActionsSheet(
        opened = sheet == DownloadSettingSheet.QUALITY,
        onDismiss = { sheet = null },
        label = "下载品质",
        items = LxQuality.entries.map { option ->
            MusesActionItem(option.label) {
                scope.launch { settings.setDownloadPreferredQuality(option.key) }
                sheet = null
            }
        },
    )
    MusesActionsSheet(
        opened = sheet == DownloadSettingSheet.PLAY_QUALITY,
        onDismiss = { sheet = null },
        label = "在线播放优先音质",
        items = LxQuality.entries.map { option ->
            MusesActionItem(option.label) {
                scope.launch { settings.setOnlinePreferredQuality(option.key) }
                sheet = null
            }
        },
    )
    MusesActionsSheet(
        opened = sheet == DownloadSettingSheet.DOWNLOAD_MODE,
        onDismiss = { sheet = null },
        label = "单曲下载方式",
        items = listOf(
            MusesActionItem("下载前选择音质") { scope.launch { settings.setChooseDownloadQuality(true) }; sheet = null },
            MusesActionItem("按默认音质直接下载") { scope.launch { settings.setChooseDownloadQuality(false) }; sheet = null },
        ),
    )
    MusesActionsSheet(
        opened = sheet == DownloadSettingSheet.DEVICE_DIRECTORY,
        onDismiss = { sheet = null },
        label = "设备下载目录",
        items = buildList {
            add(MusesActionItem("选择目录") { sheet = null; pickDeviceDirectory() })
            if (deviceDirectory.isNotBlank()) {
                add(MusesActionItem("恢复为系统下载目录") {
                    scope.launch { settings.setDownloadDeviceDirectory("") }
                    sheet = null
                })
            }
        },
    )
}
