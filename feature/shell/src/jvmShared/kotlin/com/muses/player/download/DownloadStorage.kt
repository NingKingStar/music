package com.muses.player.download

import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadTask
import com.muses.player.core.data.repository.SettingsRepository
import java.io.File

data class SavedDownload(val location: String, val physicalPath: String? = null, val warnings: List<String> = emptyList(), val skippedExisting: Boolean = false)

/** 下载先在私有目录完整写入标签，再提交到目标；目标端禁止覆盖已有文件。 */
interface DownloadStorage {
    val cacheDirectory: File
    val stagingDirectory: File get() = cacheDirectory
    suspend fun save(files: List<File>, target: DownloadTarget, progress: suspend (Long, Long) -> Unit): SavedDownload

    /**
     * 已完成任务的目标文件是否还在（本地/设备下载）。
     * 判定不了时返回 true —— 宁可提示「已下载过」，也不要重复下一份文件。
     * WebDAV 是远端地址，判断在 [DownloadManager] 里做。
     */
    suspend fun exists(task: DownloadTask): Boolean

    /** 只处理记录中的单个文件；失败抛出异常，禁止按名称搜索或递归删除。 */
    suspend fun deleteSaved(location: String): Boolean
}

expect fun createDownloadStorage(settings: SettingsRepository): DownloadStorage

/** 设备目录使用保存层返回的真实URI；MediaStore可能自动重命名，不能猜物理文件名。 */
internal fun savedDownloadFileId(target: DownloadTarget, saved: SavedDownload): String {
    val file = if (target.kind == com.muses.player.core.model.download.DownloadTargetKind.DEVICE)
        saved.location else saved.physicalPath ?: saved.location
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest("${target.sourceId ?: "device-downloads"}|$file".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

expect fun keepDownloadsRunning(running: Boolean)
