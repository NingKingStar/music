package com.muses.player.download

import com.muses.player.core.model.download.*
import kotlin.test.*

class SavedDownloadIdentityTest {
    @Test fun `设备同名文件自动改名后不合并不同URI`() {
        val a = SavedDownload("content://downloads/1", "/Downloads/歌曲.mp3")
        val b = SavedDownload("content://downloads/2", "/Downloads/歌曲.mp3")
        assertNotEquals(savedDownloadFileId(DownloadTarget(), a), savedDownloadFileId(DownloadTarget(), b))
        assertEquals(savedDownloadFileId(DownloadTarget(), a), savedDownloadFileId(DownloadTarget(), a))
    }
    @Test fun `本地音源保持与扫描器一致的来源路径哈希`() {
        val target = DownloadTarget(DownloadTargetKind.LOCAL, "source", "/music")
        val file = SavedDownload("content://tree/file", "/music/歌曲.flac")
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest("source|/music/歌曲.flac".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, savedDownloadFileId(target, file))
    }
}
