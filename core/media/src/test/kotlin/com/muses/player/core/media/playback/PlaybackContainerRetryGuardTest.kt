package com.muses.player.core.media.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 解封装失败的同曲重试必须有界：同一首冷却窗口内只一次，不同歌曲互不影响。 */
class PlaybackContainerRetryGuardTest {
    @Test
    fun `同一首冷却窗口内只重试一次`() {
        val guard = PlaybackContainerRetryGuard(cooldownMs = 60_000L)
        assertEquals(true, guard.shouldRetry("song-a", 1_000L))
        assertEquals(false, guard.shouldRetry("song-a", 1_001L))
        assertEquals(false, guard.shouldRetry("song-a", 60_999L))
        assertEquals(true, guard.shouldRetry("song-a", 62_000L))
    }

    @Test
    fun `不同歌曲各自允许一次`() {
        val guard = PlaybackContainerRetryGuard()
        assertEquals(true, guard.shouldRetry("song-a", 1_000L))
        assertEquals(true, guard.shouldRetry("song-b", 1_001L))
    }

    @Test
    fun `没有媒体项标识时不重试`() {
        val guard = PlaybackContainerRetryGuard()
        assertEquals(false, guard.shouldRetry(null, 1_000L))
        assertEquals(false, guard.shouldRetry("", 1_000L))
    }
}
