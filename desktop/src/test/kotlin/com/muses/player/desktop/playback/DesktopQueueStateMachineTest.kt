package com.muses.player.desktop.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 队列状态机单测（纯逻辑，无 VLCJ/Room/网络依赖）。
 */
class DesktopQueueStateMachineTest {

    @Test
    fun 入队建立三序与当前曲() {
        val q = DesktopQueueStateMachine()
        q.enqueue(listOf("a", "b", "c"), 1, shuffleEnabled = false)
        val s = q.state()
        assertEquals(listOf("a", "b", "c"), s.snapshot.items.map { it.songId })
        assertEquals(listOf("a", "b", "c"), s.snapshot.originalOrder.map { it.songId })
        assertNull(s.snapshot.shuffleOrder)
        assertEquals(1, s.currentIndex)
        assertEquals("b", s.currentSongId)
    }

    @Test
    fun 索引越界钳制() {
        val q = DesktopQueueStateMachine()
        q.enqueue(listOf("a", "b"), 99, shuffleEnabled = false)
        assertEquals(1, q.state().currentIndex)
        q.enqueue(listOf("a", "b"), -5, shuffleEnabled = false)
        assertEquals(0, q.state().currentIndex)
    }

    @Test
    fun 洗牌开关保持当前曲() {
        val q = DesktopQueueStateMachine()
        q.shuffleRandom = Random(42)
        q.enqueue(listOf("a", "b", "c", "d"), 0, shuffleEnabled = false)
        q.setShuffleEnabled(true)
        val s = q.state()
        assertNotNull(s.snapshot.shuffleOrder)
        assertEquals(4, s.snapshot.items.size)
        assertEquals("a", s.currentSongId)
        assertEquals("a", s.snapshot.items[s.currentIndex].songId)
        q.setShuffleEnabled(false)
        val back = q.state()
        assertNull(back.snapshot.shuffleOrder)
        assertEquals(listOf("a", "b", "c", "d"), back.snapshot.items.map { it.songId })
        assertEquals("a", back.snapshot.items[back.currentIndex].songId)
    }

    @Test
    fun 步进首尾回绕() {
        val q = DesktopQueueStateMachine()
        q.enqueue(listOf("a", "b"), 1, shuffleEnabled = false)
        assertEquals("a", q.step(next = true)?.songId)
        assertEquals("b", q.step(next = false)?.songId)
    }

    @Test
    fun 删歌过滤三序() {
        val q = DesktopQueueStateMachine()
        q.shuffleRandom = Random(7)
        q.enqueue(listOf("a", "b", "c"), 0, shuffleEnabled = false)
        q.setShuffleEnabled(true)
        val cur = q.state().currentSongId!!
        q.removeSongs(setOf("b", cur))
        val s = q.state()
        assertTrue(s.snapshot.items.none { it.songId == "b" || it.songId == cur })
        assertTrue(s.snapshot.originalOrder.none { it.songId == "b" || it.songId == cur })
        assertTrue(s.snapshot.shuffleOrder?.none { it.songId == "b" || it.songId == cur } == true)
        // 当前曲被删 → -1/null 由调用方决定下一步
        assertEquals(-1, s.currentIndex)
        assertNull(s.currentSongId)
    }

    @Test
    fun 恢复候选沿序回绕并跳过已尝试() {
        val q = DesktopQueueStateMachine()
        val order = listOf("a", "b", "c")
        assertEquals(1, q.selectNextCandidate(order, 0, emptySet()))
        assertEquals(2, q.selectNextCandidate(order, 0, setOf("b")))
        // b/c 均尝试过 → 回绕到 a
        assertEquals(0, q.selectNextCandidate(order, 1, setOf("b", "c")))
        // 全部尝试过 → null
        assertNull(q.selectNextCandidate(order, 1, setOf("a", "b", "c")))
        assertNull(q.selectNextCandidate(emptyList(), 0, emptySet()))
    }

    @Test
    fun 恢复重建快照() {
        val q = DesktopQueueStateMachine()
        val items = listOf("a", "b").map { com.muses.player.core.model.playback.QueueItem(it) }
        q.restore(items, items, null, 1, "b")
        assertEquals(1, q.state().currentIndex)
        assertEquals("b", q.state().currentSongId)
    }

    @Test
    fun repeat整型互转() {
        assertEquals(com.muses.player.core.model.playback.RepeatMode.ONE, repeatModeFromInt(1))
        assertEquals(com.muses.player.core.model.playback.RepeatMode.ALL, repeatModeFromInt(2))
        assertEquals(com.muses.player.core.model.playback.RepeatMode.NONE, repeatModeFromInt(0))
        assertEquals(1, repeatModeToInt(com.muses.player.core.model.playback.RepeatMode.ONE))
        assertEquals(2, repeatModeToInt(com.muses.player.core.model.playback.RepeatMode.ALL))
    }
}
