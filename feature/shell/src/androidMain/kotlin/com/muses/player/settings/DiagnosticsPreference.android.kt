package com.muses.player.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.muses.player.core.data.diagnostics.PersistentDiagnostics
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
actual fun DiagnosticsPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            exporting = true
            scope.launch {
                try {
                    runInterruptible(Dispatchers.IO) {
                        val store = org.koin.core.context.GlobalContext.get().get<PersistentDiagnostics>()
                        checkNotNull(context.contentResolver.openOutputStream(uri)).use { store.export(it) }
                    }
                    MusesSnackbar.show("诊断包已保存，可自行回传分析")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Throwable) { MusesSnackbar.show("诊断包导出失败，请重试") }
                finally { exporting = false }
            }
        }
    }
    ArrowPreference(title = "导出播放诊断", summary = if (exporting) "正在导出…" else "本地脱敏记录，不会自动上传",
        onClick = { if (!exporting) launcher.launch("Muses-播放诊断.zip") })
}
