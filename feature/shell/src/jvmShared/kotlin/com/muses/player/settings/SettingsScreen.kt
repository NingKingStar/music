package com.muses.player.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import com.muses.player.core.appupdate.checkLatestRelease
import com.muses.player.core.data.log.ErrorLogStore
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.ui.components.SettingsAboutFeedbackContent
import com.muses.player.core.ui.components.SettingsBlockTitle
import com.muses.player.core.ui.components.SettingsScreen
import com.muses.player.feature.shell.platform.AppVersionProvider
import com.muses.player.feature.shell.platform.InAppUpdateSection
import com.muses.player.feature.shell.platform.rememberShellPlatformActions
import com.muses.player.feature.shell.platform.supportsInAppUpdate

import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel constructor(
    private val errorLogStore: ErrorLogStore,
) : ViewModel() {

    /**
     * 复制用日志全文：文件头（版本 + 导出时间）+ 缓冲正文（含上次会话崩溃段）。
     * 无任何日志时返回 null，由 UI 层提示「暂无可复制的日志」。
     */
    suspend fun dumpLogs(): String? {
        val body = errorLogStore.dump() ?: return null
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "[Muses 错误日志] v${versionName()} @ $time\n$body"
    }

    /** 版本号经 Koin [AppVersionProvider] 注入（原 BuildConfig 仅安卓可读） */
    private fun versionName(): String =
        org.koin.core.context.GlobalContext.get().get<AppVersionProvider>().versionName
}

/**
 * 设置页（U22 双端共享）：ui-shared 共享容器 + 共享「关于/反馈」扩展区块；
 * 平台动作（浏览器/剪贴板）经 rememberShellPlatformActions 注入，版本号经
 * Koin AppVersionProvider，报错日志同源 ErrorLogStore（桌面绑定 RingBufferErrorLogStore）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onBack: () -> Unit,
    // AI 服务配置二级页入口（设置页「AI 推荐」→「AI 服务」箭头进入）。
    onOpenAiSettings: () -> Unit,
    onOpenSyncSettings: () -> Unit,
) {
    val actions = rememberShellPlatformActions()
    val versionProvider = koinInject<AppVersionProvider>()
    val settingsRepository = koinInject<SettingsRepository>()
    val coroutineScope = rememberCoroutineScope()
    val lyricsEnabled by settingsRepository.miniPlayerLyricsEnabled.collectAsState(initial = false)
    val notificationLyricsEnabled by settingsRepository.notificationLyricsEnabled.collectAsState(initial = false)
    val coverContentColorEnabled by settingsRepository.coverContentColorEnabled.collectAsState(initial = true)


    // U15：设置页共享组件（音源区块已移除，独立音源页承载）；「关于/反馈」扩展区为
    // 双端共享实现（SettingsAboutFeedbackContent），平台动作经回调注入。
    // 视觉：官方 Settings 范式——每个分组一张 Card，开关/入口行用 miuix Preference 系列。
    SettingsScreen(
        modifier = modifier,
        onBack = onBack,
        extraContent = {
                // ---- 播放设置 ----
                SettingsBlockTitle("播放")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    DiagnosticsPreference()
                    AudioFocusPreference()
                    SwitchPreference(
                        title = "播放控件歌词",
                        checked = lyricsEnabled,
                        onCheckedChange = { coroutineScope.launch { settingsRepository.setMiniPlayerLyricsEnabled(it) } },
                    )
                    SwitchPreference(
                        title = "通知栏歌词",
                        checked = notificationLyricsEnabled,
                        onCheckedChange = { coroutineScope.launch { settingsRepository.setNotificationLyricsEnabled(it) } },
                    )
                    DesktopLyricsPreference()
                    DesktopLyricsLockPreference()
                    SwitchPreference(
                        title = "封面强调色",
                        summary = "播放页文字与图标跟随封面取色",
                        checked = coverContentColorEnabled,
                        onCheckedChange = { coroutineScope.launch { settingsRepository.setCoverContentColorEnabled(it) } },
                    )
                }

                // ---- 下载与 AI 推荐 ----
                com.muses.player.download.DownloadPreferencesSection()
                SettingsBlockTitle("数据同步")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "WebDAV 同步",
                        summary = "同步应用数据",
                        onClick = onOpenSyncSettings,
                    )
                }
                AiRecommendSettingSection(onOpenAiSettings = onOpenAiSettings)

                // ---- 应用更新（两端使用各自下载安装流程，隐藏重复外链检查入口） ----
                if (supportsInAppUpdate) {
                    InAppUpdateSection(
                        versionName = versionProvider.versionName,
                        onOpenUrl = actions.openUrl,
                    )
                }

                // ---- 关于 ----
                SettingsAboutFeedbackContent(
                    versionName = versionProvider.versionName,
                    onOpenUrl = actions.openUrl,
                    onCopyToClipboard = actions.copyToClipboard,
                    onCheckUpdate = { current -> checkLatestRelease(current) },

                    onDumpLogs = { viewModel.dumpLogs() },
                    showCheckUpdate = !supportsInAppUpdate,
                )
            },
        )
}
