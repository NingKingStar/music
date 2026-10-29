package com.muses.player.core.media.playback

import com.muses.player.core.model.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackConfigWriterTest {
    @Test fun `顺序模式不会被序列化为列表循环`() {
        assertEquals(PlayerConfig(RepeatMode.NONE, false), playbackConfigFromState(0, false))
        assertEquals(PlayerConfig(RepeatMode.ONE, false), playbackConfigFromState(1, false))
    }
    @Test fun `连续切换时最终配置最后写入`() = runTest {
        val saved = mutableListOf<PlayerConfig>()
        val writer = PlaybackConfigWriter(backgroundScope, { delay(100); saved += it })
        writer.save(PlayerConfig(RepeatMode.ALL, false)); runCurrent()
        writer.save(PlayerConfig(RepeatMode.ONE, false))
        writer.save(PlayerConfig(RepeatMode.ALL, true))
        writer.save(PlayerConfig(RepeatMode.NONE, false))
        advanceTimeBy(500); runCurrent()
        assertEquals(PlayerConfig(RepeatMode.NONE, false), saved.last())
    }
}
