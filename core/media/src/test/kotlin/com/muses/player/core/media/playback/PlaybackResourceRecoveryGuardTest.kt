package com.muses.player.core.media.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 换源恢复守卫：只重建一次、换歌放行、冷却后再允许。 */
class PlaybackResourceRecoveryGuardTest {
    @Test
    fun `同一首在冷却窗口内只恢复一次`() {
        val guard = PlaybackResourceRecoveryGuard(cooldownMs = 30_000L)
        assertEquals(true, guard.shouldRecover("song-a", 1_000L))
        assertEquals(false, guard.shouldRecover("song-a", 1_001L))
        assertEquals(false, guard.shouldRecover("song-a", 30_999L))
    }

    @Test
    fun `切换歌曲立即可恢复且冷却过后同曲可再恢复`() {
        val guard = PlaybackResourceRecoveryGuard(cooldownMs = 30_000L)
        assertEquals(true, guard.shouldRecover("song-a", 1_000L))
        assertEquals(true, guard.shouldRecover("song-b", 1_500L))
        assertEquals(true, guard.shouldRecover("song-a", 40_000L))
    }

    @Test
    fun `没有媒体项标识时不重建播放器`() {
        val guard = PlaybackResourceRecoveryGuard()
        assertEquals(false, guard.shouldRecover(null, 1_000L))
        assertEquals(false, guard.shouldRecover("", 1_000L))
    }
}
