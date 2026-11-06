package com.muses.player.feature.shell.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateHintTextTest {
    @Test
    fun `没有新版本时不给提示`() {
        assertNull(updateHintText(null, ready = false, ignored = null))
        assertNull(updateHintText("", ready = false, ignored = null))
    }

    @Test
    fun `正常提示新版本`() {
        assertEquals("有新版本 0.9.18", updateHintText("0.9.18", ready = false, ignored = null))
    }

    @Test
    fun `已下载待安装优先于版本号`() {
        assertEquals("新版已下载，等待安装", updateHintText("0.9.18", ready = true, ignored = null))
    }

    @Test
    fun `关闭提醒后入口仍要说有新版本`() {
        assertEquals("0.9.18 有新版本（已关闭提醒）", updateHintText("0.9.18", ready = false, ignored = "0.9.18"))
    }

    @Test
    fun `忽略的是旧版本时按正常提示处理`() {
        assertEquals("有新版本 0.9.19", updateHintText("0.9.19", ready = false, ignored = "0.9.18"))
    }
}
