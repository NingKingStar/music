package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChorusDetectorTest {
    private fun line(timeMs: Long, text: String) = LyricLine(timeMs = timeMs, text = text)

    @Test
    fun `找出重复次数最多的段落起点`() {
        val lines = listOf(
            line(1_000, "街灯亮起"), line(6_000, "我沿着河走"),
            line(11_000, "风把话说了一半"), line(16_000, "剩下的交给夜色"),
            line(21_000, "副歌一句"), line(26_000, "副歌二句"), line(31_000, "副歌三句"),
            line(36_000, "第二段主歌"), line(41_000, "换了说法"),
            line(46_000, "副歌一句"), line(51_000, "副歌二句"), line(56_000, "副歌三句"),
        )
        assertEquals(21_000L, detectChorusStartMs(lines))
    }

    @Test
    fun `标点与大小写不影响匹配`() {
        val lines = listOf(
            line(1_000, "Intro A"), line(2_000, "Intro B"), line(3_000, "Intro C"),
            line(4_000, "We light it up!"), line(5_000, "we light it up"), line(6_000, "WE LIGHT IT UP"),
            line(7_000, "outro"),
        )
        assertEquals(4_000L, detectChorusStartMs(lines))
    }

    @Test
    fun `没有重复时不给节点`() {
        val lines = (1..10).map { line(it * 1_000L, "第 $it 句") }
        assertNull(detectChorusStartMs(lines))
    }

    @Test
    fun `行数太少不做推断`() {
        val lines = listOf(line(1_000, "同一句"), line(2_000, "同一句"), line(3_000, "同一句"))
        assertNull(detectChorusStartMs(lines))
    }

    @Test
    fun `空白与占位行不参与匹配`() {
        val lines = listOf(
            line(1_000, "A1"), line(2_000, "A2"), line(3_000, "  "),
            line(4_000, "B1"), line(5_000, "B2"), line(6_000, "B3"),
            line(7_000, "A1"), line(8_000, "A2"), line(9_000, "B1"), line(10_000, "B2"), line(11_000, "B3"),
        )
        assertEquals(1_000L, detectChorusStartMs(lines))
    }
}
