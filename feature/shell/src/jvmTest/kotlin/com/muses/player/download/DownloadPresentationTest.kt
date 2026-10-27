package com.muses.player.download

import com.muses.player.core.model.download.*
import kotlin.test.*

class DownloadPresentationTest {
    private fun task(status: DownloadStatus) = DownloadTask("任务", DownloadTrack("歌", "online", "", "歌曲"), status = status)

    @Test fun `下载显示真实比例与大小`() {
        val state = downloadProgressPresentation(task(DownloadStatus.DOWNLOADING).copy(downloadedBytes = 1024, totalBytes = 4096))
        assertEquals(0.25f, state.fraction)
        assertEquals("1 KB / 4 KB", state.amount)
        assertEquals("下载中", state.stage)
    }
    @Test fun `未知大小保留已下载字节且不编造百分比`() {
        for (total in listOf(null, 0L, -1L)) {
            val state = downloadProgressPresentation(task(DownloadStatus.DOWNLOADING).copy(downloadedBytes = 2048, totalBytes = total))
            assertNull(state.fraction)
            assertEquals("已下载 2 KB · 总大小未知", state.amount)
        }
    }
    @Test fun `刚开始接收也有明确阶段提示`() {
        val state = downloadProgressPresentation(task(DownloadStatus.DOWNLOADING))
        assertNull(state.fraction)
        assertEquals("正在接收音频 · 总大小未知", state.amount)
    }
    @Test fun `上传使用传输进度而非音频下载进度`() {
        val state = downloadProgressPresentation(task(DownloadStatus.UPLOADING).copy(
            downloadedBytes = 4096, totalBytes = 4096, transferredBytes = 1024, transferTotalBytes = 4096))
        assertEquals(0.25f, state.fraction)
        assertEquals("1 KB / 4 KB", state.amount)
    }
    @Test fun `整理信息和核验不伪装成百分之百完成`() {
        val metadata = downloadProgressPresentation(task(DownloadStatus.METADATA).copy(downloadedBytes = 4096, totalBytes = 4096))
        assertNull(metadata.fraction)
        assertTrue(metadata.amount.contains("正在整理"))
        val confirming = downloadProgressPresentation(task(DownloadStatus.UPLOADING).copy(
            transferredBytes = 4096, transferTotalBytes = 4096, transferMessage = "核验中"))
        assertNull(confirming.fraction)
        assertEquals("核验中", confirming.stage)
    }
    @Test fun `暂停保留实际进度`() {
        assertEquals(0.5f, downloadProgressPresentation(task(DownloadStatus.PAUSED).copy(downloadedBytes = 2048, totalBytes = 4096)).fraction)
    }
    @Test fun `失败区分上传与网络下载`() {
        val failed = task(DownloadStatus.FAILED).copy(failureStage = DownloadStatus.UPLOADING,
            target = DownloadTarget(DownloadTargetKind.WEBDAV), transferredBytes = 512, transferTotalBytes = 1024)
        assertEquals("上传失败 · 本地文件已保留", downloadProgressPresentation(failed).stage)
        assertEquals(0.5f, downloadProgressPresentation(failed).fraction)
        assertEquals("下载失败", downloadProgressPresentation(task(DownloadStatus.FAILED)).stage)
    }
    @Test fun `异常字节数不越界且小文件不会显示零KB`() {
        assertEquals("12 B", formatBytes(12))
        assertEquals(1f, downloadProgressPresentation(task(DownloadStatus.DOWNLOADING).copy(downloadedBytes = Long.MAX_VALUE, totalBytes = 1)).fraction)
        assertEquals(0f, downloadProgressPresentation(task(DownloadStatus.DOWNLOADING).copy(downloadedBytes = -1, totalBytes = 10)).fraction)
    }
}
