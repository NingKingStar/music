package com.muses.player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.SettingsBlockTitle
import com.muses.player.core.ui.theme.LocalBottomChromePadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MineScreen(
    onOpenSources: () -> Unit,
    onOpenScrape: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MusesTopBar(title = "我的") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(top = 8.dp),
        ) {
            SettingsBlockTitle("曲库工具")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(title = "本地与云端音乐", onClick = onOpenLibrary)
                ArrowPreference(title = "音源", onClick = onOpenSources)
                ArrowPreference(title = "刮削", onClick = onOpenScrape)
                ArrowPreference(title = "下载", onClick = onOpenDownloads)
                ArrowPreference(title = "统计", onClick = onOpenStatistics)
                ArrowPreference(title = "历史记录", onClick = onOpenHistory)
            }
            SettingsBlockTitle("应用")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(title = "设置", onClick = onOpenSettings)
            }
            Spacer(Modifier.height(16.dp + LocalBottomChromePadding.current))
        }
    }
}
