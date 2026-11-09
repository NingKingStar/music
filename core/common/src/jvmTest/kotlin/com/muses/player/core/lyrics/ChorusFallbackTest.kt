package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChorusFallbackTest {
    private fun lines(vararg pairs: Pair<Long, String>) = pairs.map { LyricLine(timeMs = it.first, text = it.second) }

    @Test
    fun `典型流行歌能找到重复副歌段`() {
        val song = lines(
            1_000L to "街灯亮起",
            6_000L to "我沿着河走",
            11_000L to "风把话说了一半",
            16_000L to "剩下的交给夜色",
            21_000L to "副歌一句",
            26_000L to "副歌二句",
            31_000L to "副歌三句",
            36_000L to "第二段主歌",
            41_000L to "换了说法",
            46_000L to "副歌一句",
            51_000L to "副歌二句",
            56_000L to "副歌三句",
        )
        val ranges = detectChorusRanges(song)
        assertEquals(1, ranges.size)
        assertEquals(21_000L, ranges.single().startMs)
        assertTrue(ranges.single().endMs > ranges.single().startMs)
    }

    @Test
    fun `单句 hook 重复三次也算副歌`() {
        val song = lines(
            1_000L to "第一句", 5_000L to "第二句", 9_000L to "第三句",
            13_000L to "爱你", 17_000L to "别的句子", 21_000L to "爱你",
            25_000L to "别的句子二", 29_000L to "爱你",
        )
        val ranges = detectChorusRanges(song)
        assertEquals(1, ranges.size)
        assertEquals(13_000L, ranges.single().startMs)
    }

    @Test
    fun `歌词太短或没有重复时不给区间`() {
        assertEquals(0, detectChorusRanges(lines(1_000L to "A", 2_000L to "B")).size)
        val unique = (1..10).map { LyricLine(timeMs = it * 1_000L, text = "第 $it 句") }
        assertEquals(0, detectChorusRanges(unique).size)
    }
}
