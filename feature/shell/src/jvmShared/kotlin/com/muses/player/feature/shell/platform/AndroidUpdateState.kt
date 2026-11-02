package com.muses.player.feature.shell.platform

import com.muses.player.core.appupdate.AndroidReleaseInfo

internal enum class UpdateWork { CHECK, DOWNLOAD, VERIFY, INSTALL }

internal data class AndroidUpdateState(
    val release: AndroidReleaseInfo? = null, val checking: Boolean = false, val status: String = "尚未检查更新",
    val error: String? = null, val downloadId: Long = -1, val progress: Int? = null, val ready: Boolean = false,
    val autoCheck: Boolean = true, val wifiDownload: Boolean = false, val checkedAt: Long = 0, val ignored: String? = null,
    val verifying: Boolean = false, val installing: Boolean = false, val errorWork: UpdateWork? = null,
) {
    fun failed(work: UpdateWork, message: String): AndroidUpdateState {
        val discardDownload = work == UpdateWork.DOWNLOAD || work == UpdateWork.VERIFY
        return copy(checking = false, verifying = false, installing = false, error = message, errorWork = work,
            downloadId = if (discardDownload) -1 else downloadId,
            ready = if (discardDownload) false else ready,
            progress = if (discardDownload || work == UpdateWork.CHECK) null else progress,
            status = when(work) {
                UpdateWork.CHECK -> "检查失败，可重新检查"
                UpdateWork.DOWNLOAD -> "下载失败，可重新下载"
                UpdateWork.VERIFY -> "安装包校验失败，请重新下载"
                UpdateWork.INSTALL -> "安装尚未完成，可重试"
            })
    }
}
