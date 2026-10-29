package com.muses.player.core.media.playback

import org.junit.Test
import org.junit.Assert.*

class PlaybackEarlyEndGuardTest {
    @Test fun `截断与正常结尾未知目录时长分别处理`() {
        val guard = PlaybackEarlyEndGuard { 0 }
        assertTrue(guard.isEarlyEnd(90_000, 240_000, 90_000))
        assertFalse(guard.isEarlyEnd(235_000, 240_000, 240_000))
        assertFalse(guard.isEarlyEnd(90_000, 0, 90_000))
        assertFalse(guard.isEarlyEnd(90_000, 240_000, 240_000))
        assertFalse(guard.isEarlyEnd(0, 240_000, 0))
    }
    @Test fun `暂停与音频焦点抑制不触发缓冲恢复`() {
        var now = 0L
        val guard = PlaybackEarlyEndGuard { now }
        assertFalse(guard.stalled("a", true, true, false))
        now = 26_000
        assertTrue(guard.stalled("a", true, true, false))
        assertFalse(guard.stalled("a", true, false, false))
        assertFalse(guard.stalled("a", true, true, true))
        assertFalse(guard.stalled("b", true, true, false))
    }
    @Test fun `同曲恢复次数有界且其他歌曲不受影响`() {
        var now = 0L
        val guard = PlaybackEarlyEndGuard { now }
        assertTrue(guard.allow("a"))
        assertFalse(guard.allow("a"))
        assertTrue(guard.allow("b"))
        now = 120_001
        assertTrue(guard.allow("a"))
    }
}
