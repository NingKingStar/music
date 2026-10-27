package com.muses.player.download

import androidx.compose.runtime.*
import com.muses.player.core.model.download.DownloadedSong
import com.muses.player.core.ui.components.MusesDialog
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DeleteDownloadedSongDialog(entry: DownloadedSong, manager: DownloadManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(entry.taskId) { mutableStateOf(false) }
    var missing by remember(entry.taskId) { mutableStateOf(false) }
    MusesDialog(
        title = if (missing) "文件不存在" else "删除下载？",
        message = if (missing) "找不到“${entry.track.title}”的下载文件。是否清除这条失效记录？歌单和喜欢会保留。"
            else "将删除“${entry.track.title}”在${entry.target.label}的音频文件，并移出已下载。歌单和喜欢会保留。",
        dismissText = "取消", confirmText = if (missing) "清除记录" else "删除下载",
        confirmLoading = busy, onDismiss = { if (!busy) onClose() },
        onConfirm = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        if (manager.deleteSaved(entry, clearMissing = missing)) {
                            MusesSnackbar.show(if (missing) "已清除失效记录" else "已删除下载")
                            onClose()
                        } else missing = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { MusesSnackbar.show(e.message ?: "操作失败，请检查保存位置和权限") }
                    finally { busy = false }
                }
            }
        },
    )
}
