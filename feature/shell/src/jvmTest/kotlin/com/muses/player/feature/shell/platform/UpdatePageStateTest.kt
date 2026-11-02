package com.muses.player.feature.shell.platform

import kotlin.test.*

class UpdatePageStateTest {
    @Test fun 下载失败清理旧进度与忙状态() {
        val failed = AndroidUpdateState(downloadId = 9, progress = 78, status = "正在下载", verifying = true)
            .failed(UpdateWork.DOWNLOAD, "断网")
        assertEquals(-1L, failed.downloadId); assertNull(failed.progress)
        assertFalse(failed.ready); assertFalse(failed.verifying); assertFalse(failed.checking)
        assertEquals("断网", failed.error); assertEquals("下载失败，可重新下载", failed.status)
    }
    @Test fun 安装权限错误保留可再次安装的文件状态() {
        val failed = AndroidUpdateState(downloadId = 7, progress = 100, ready = true, installing = true)
            .failed(UpdateWork.INSTALL, "请暂停音乐")
        assertEquals(7L, failed.downloadId); assertTrue(failed.ready); assertFalse(failed.installing)
    }
    @Test fun 检查失败不伪装成检查中() {
        val failed = AndroidUpdateState(checking = true, status = "正在检查…").failed(UpdateWork.CHECK, "网络不可用")
        assertFalse(failed.checking); assertEquals("检查失败，可重新检查", failed.status)
    }
    @Test fun 更新说明移除标记并保留标题列表层级() {
        val blocks = releaseNoteBlocks("# 新版本\n\n## 改进\n- **歌手主页**\n- [更新说明](https://example.com)\n\n普通段落\n下一行")
        assertEquals(listOf(ReleaseNoteKind.TITLE, ReleaseNoteKind.TITLE, ReleaseNoteKind.BULLET, ReleaseNoteKind.BULLET, ReleaseNoteKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals("歌手主页", blocks[2].text); assertEquals("更新说明", blocks[3].text)
        assertEquals("普通段落 下一行", blocks.last().text)
    }
}
