package com.muses.player.download

import com.muses.player.core.model.download.DownloadStatus
import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.download.DownloadTask

internal data class DownloadProgressPresentation(
    val stage: String,
    val fraction: Float?,
    val amount: String,
)

/** 网络下载与保存/上传分别展示真实进度；未知总大小不伪造百分比。 */
internal fun downloadProgressPresentation(task: DownloadTask): DownloadProgressPresentation {
    val failedTransfer = task.status == DownloadStatus.FAILED &&
        (task.failureStage == DownloadStatus.UPLOADING || task.failureStage == DownloadStatus.SAVING ||
            (task.failureStage == null && task.transferTotalBytes != null))
    val transfer = task.status == DownloadStatus.UPLOADING || task.status == DownloadStatus.SAVING ||
        task.status == DownloadStatus.PENDING_UPLOAD || failedTransfer
    val bytes = (if (transfer) task.transferredBytes else task.downloadedBytes).coerceAtLeast(0)
    val total = (if (transfer) task.transferTotalBytes else task.totalBytes)?.takeIf { it > 0 }
    val indeterminate = task.status == DownloadStatus.PREPARING || task.status == DownloadStatus.METADATA ||
        (task.status == DownloadStatus.UPLOADING && task.transferMessage != null)
    val fraction = if (indeterminate) null else total?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val stage = when {
        failedTransfer -> if (task.target?.kind == DownloadTargetKind.WEBDAV) "上传失败 · 本地文件已保留" else "保存失败"
        task.skippedExisting -> "已有同名歌曲，已跳过"
        task.status == DownloadStatus.UPLOADING -> task.transferMessage ?: "正在上传"
        else -> statusLabel(task.status)
    }
    val amount = when {
        indeterminate -> when (task.status) {
            DownloadStatus.PREPARING -> "正在获取音频地址，请稍候"
            DownloadStatus.METADATA -> "音频已下载，正在整理歌曲信息"
            else -> "音频已下载，正在确认保存结果"
        }
        total != null -> "${formatBytes(bytes)} / ${formatBytes(total)}"
        bytes > 0 -> "已${if (transfer) "传输" else "下载"} ${formatBytes(bytes)} · 总大小未知"
        task.status == DownloadStatus.DOWNLOADING -> "正在接收音频 · 总大小未知"
        task.status == DownloadStatus.WAITING -> "等待开始下载"
        else -> ""
    }
    return DownloadProgressPresentation(stage, fraction, amount)
}
