package com.muses.player.core.model

import com.muses.player.core.model.playback.*
import kotlin.test.*

class UnifiedQualityAndModeTest {
    @Test fun `旧下载规格只缩短展示不把请求母带当成实测`() {
        assertEquals("Hi-Res", shortAudioQualityLabel("FLAC · 无损 · 24 bit / 192000 Hz"))
        assertEquals("无损", shortAudioQualityLabel("FLAC · 无损 · 16 bit / 44100 Hz"))
        assertEquals("无损", shortAudioQualityLabel("SQ"))
        assertEquals("HQ", shortAudioQualityLabel("MP3 · 有损 · 320 kbps"))
        assertEquals("标准", shortAudioQualityLabel("AAC · 有损 · 128 kbps"))
        assertEquals("FLAC", shortAudioQualityLabel("FLAC（音质未核实）"))
    }
    @Test fun `四种模式明确区分且随机不叠加单曲循环`() {
        assertEquals(RepeatMode.NONE, PlaybackMode.SEQUENTIAL.repeat)
        assertEquals(RepeatMode.ALL, PlaybackMode.LOOP.repeat)
        assertEquals(RepeatMode.ONE, PlaybackMode.SINGLE.repeat)
        PlaybackMode.entries.forEach { mode -> assertEquals(mode, PlaybackMode.from(PlayerConfig(mode.repeat, mode.shuffle))) }
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.from(PlayerConfig(RepeatMode.ONE, true)))
    }
}
