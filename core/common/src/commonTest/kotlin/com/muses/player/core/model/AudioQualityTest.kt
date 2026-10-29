package com.muses.player.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioQualityTest {
    @Test fun `无损高位深与高采样率和有损码率分开判断`() {
        assertEquals("Hi-Res", classifyAudioQuality(true, 24, 96_000, 4608))
        assertEquals("无损", classifyAudioQuality(true, 16, 44_100, 900))
        assertEquals("HQ", classifyAudioQuality(false, 0, 48_000, 320))
        assertEquals("标准", classifyAudioQuality(false, 0, 44_100, 128))
        assertNull(classifyAudioQuality(false, 0, 0, 0))
    }

    @Test fun `旧记录仅显示格式而非猜测音质`() {
        val song = Song("1", "source", "/音乐/歌.flac", "歌曲")
        assertEquals("FLAC", song.libraryQualityLabel)
        assertEquals("Hi-Res", song.copy(audioQuality = "Hi-Res").libraryQualityLabel)
        assertNull(song.copy(path = "content://media/audio/1").libraryQualityLabel)
    }
}
