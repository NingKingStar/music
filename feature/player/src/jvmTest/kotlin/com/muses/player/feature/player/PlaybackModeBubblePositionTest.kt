package com.muses.player.feature.player

import androidx.compose.ui.unit.*
import com.muses.player.core.ui.components.PlaybackModeBubblePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeBubblePositionTest {
    private val provider = PlaybackModeBubblePosition(margin = 12, gap = 8)

    @Test fun `左侧按钮的气泡显示在其上方并避开左边缘`() {
        assertEquals(IntOffset(12, 512), provider.calculatePosition(IntRect(4, 600, 48, 644), IntSize(320, 700), LayoutDirection.Ltr, IntSize(292, 80)))
    }

    @Test fun `右侧按钮的气泡不会溢出窄屏`() {
        val point = provider.calculatePosition(IntRect(280, 600, 324, 644), IntSize(320, 700), LayoutDirection.Ltr, IntSize(292, 80))
        assertEquals(16, point.x)
        assertTrue(point.x + 292 <= 320 - 12)
    }

    @Test fun `顶部空间不足时移动到按钮下方`() {
        assertEquals(52, provider.calculatePosition(IntRect(20, 0, 64, 44), IntSize(320, 700), LayoutDirection.Ltr, IntSize(292, 80)).y)
    }

    @Test fun `窗口缩小时仍返回有效位置`() {
        val point = provider.calculatePosition(IntRect(280, 500, 324, 544), IntSize(240, 100), LayoutDirection.Rtl, IntSize(292, 80))
        assertEquals(0, point.x)
        assertTrue(point.y in 0..20)
    }
}
