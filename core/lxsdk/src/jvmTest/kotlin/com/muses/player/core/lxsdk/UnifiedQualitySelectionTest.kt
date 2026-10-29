package com.muses.player.core.lxsdk

import kotlin.test.*

class UnifiedQualitySelectionTest {
    @Test fun `统一菜单保留高档请求并兼容旧偏好`() {
        assertEquals(listOf("自动最佳", "标准", "HQ", "无损", "Hi-Res"), musicQualitySelections.map { it.label })
        listOf("master", "hires", "flac24bit").forEach { assertEquals("Hi-Res", qualitySelectionLabel(it)) }
        assertEquals(LxQuality.MASTER, LxQuality.fromKey("auto_best"))
        assertEquals(listOf(LxQuality.MASTER, LxQuality.HIRES, LxQuality.FLAC_24BIT), fallbackQualities(LxQuality.MASTER).take(3))
    }
    @Test fun `普通音质回退不插入空间混音且空间选择仍可用`() {
        assertFalse(fallbackQualities(LxQuality.MASTER).any { it == LxQuality.ATMOS || it == LxQuality.ATMOS_PLUS })
        assertEquals(LxQuality.ATMOS_PLUS, fallbackQualities(LxQuality.ATMOS_PLUS).first())
    }
}
