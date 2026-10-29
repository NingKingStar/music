package com.muses.player.core.model.online

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [OnlineTrackSession] 测试：只登记在线曲目 + 回退查询语义 */
class OnlineTrackSessionTest {
    @Test fun `替代后仍可恢复原歌曲引用且队列身份不变`() = runTest {
        OnlineTrackSession.clear()
        try {
            val original = OnlineTrackRef("wy", """{"songmid":"1"}""", "online")
            val actual = OnlineTrackRef("tx", """{"songmid":"2"}""", "online")
            val song = Song("online:wy:1", "online", original.encode(), "歌曲", sourceType = SourceType.ONLINE)
            OnlineTrackSession.remember(listOf(song))
            OnlineTrackSession.rememberResolution(original, actual)
            assertEquals(song, OnlineTrackSession.originalSong(song.id))
            OnlineTrackSession.restoreOriginal(song.id)
            assertEquals(song, OnlineTrackSession.find(song.id))
            assertEquals(original, OnlineTrackSession.resolvedReference(original))
        } finally { OnlineTrackSession.clear() }
    }

    @Test fun `跨平台播放成功后保留队列 ID 并更新歌词引用`() = runTest {
        OnlineTrackSession.clear()
        try {
            val original = OnlineTrackRef("wy", """{"songmid":123}""", "source")
            val actual = OnlineTrackRef("tx", """{"songmid":"qq-mid"}""", "source")
            OnlineTrackSession.remember(listOf(Song("online:wy:123", "source", original.encode(), "嗜好",
                sourceType = SourceType.ONLINE, lyrics = "原平台歌词")))
            OnlineTrackSession.rememberResolution(original, actual)
            val song = OnlineTrackSession.observe("online:wy:123").first()!!
            assertEquals("online:wy:123", song.id)
            assertEquals(actual, OnlineTrackRef.parse(song.path))
            assertNull(song.lyrics)
            assertEquals(actual, OnlineTrackSession.resolvedReference(original))
            OnlineTrackSession.remember(listOf(song.copy(path = original.encode())))
            assertEquals(original, OnlineTrackSession.resolvedReference(original))
        } finally { OnlineTrackSession.clear() }
    }

    private fun song(id: String, type: SourceType) = Song(
        id = id,
        sourceId = "s",
        path = if (type == SourceType.ONLINE) "muslx://kw/abc" else "/local/$id.mp3",
        title = "标题$id",
        sourceType = type,
    )

    @Test
    fun `只登记在线曲目`() = runTest {
        OnlineTrackSession.clear()
        OnlineTrackSession.remember(
            listOf(
                song("online:kw:1", SourceType.ONLINE),
                song("local:1", SourceType.LOCAL),
                song("dav:1", SourceType.WEBDAV),
            ),
        )
        assertEquals(1, OnlineTrackSession.size())
        assertTrue(OnlineTrackSession.find("online:kw:1") != null)
        assertNull(OnlineTrackSession.find("local:1"))
        assertNull(OnlineTrackSession.find("dav:1"))
        OnlineTrackSession.clear()
    }

    @Test
    fun `未登记返回 null`() = runTest {
        OnlineTrackSession.clear()
        assertNull(OnlineTrackSession.find("不存在"))
    }

    @Test
    fun `批量查询只返回已登记的`() = runTest {
        OnlineTrackSession.clear()
        OnlineTrackSession.remember(listOf(song("a", SourceType.ONLINE)))
        val found = OnlineTrackSession.findAll(listOf("a", "b"))
        assertEquals(setOf("a"), found.keys)
        OnlineTrackSession.clear()
    }

    @Test
    fun `重复登记同一 id 覆盖旧值`() = runTest {
        OnlineTrackSession.clear()
        OnlineTrackSession.remember(listOf(song("dup", SourceType.ONLINE)))
        OnlineTrackSession.remember(
            listOf(song("dup", SourceType.ONLINE).copy(title = "新标题")),
        )
        assertEquals(1, OnlineTrackSession.size())
        assertEquals("新标题", OnlineTrackSession.find("dup")?.title)
        OnlineTrackSession.clear()
    }

    @Test
    fun `clear 清空全部`() = runTest {
        OnlineTrackSession.remember(listOf(song("x", SourceType.ONLINE)))
        OnlineTrackSession.clear()
        assertEquals(0, OnlineTrackSession.size())
    }

    @Test
    fun `observe 立即发出已登记条目`() = runTest {
        OnlineTrackSession.clear()
        OnlineTrackSession.remember(listOf(song("online:kw:9", SourceType.ONLINE)))
        assertEquals("标题online:kw:9", OnlineTrackSession.observe("online:kw:9").first()?.title)
        OnlineTrackSession.clear()
    }

    @Test
    fun `先观察后登记也会收到补发`() = runTest {
        // 消除「播放页先观察当前曲、播放链路后登记」的竞态（StateFlow 推送语义）
        OnlineTrackSession.clear()
        val flow = OnlineTrackSession.observe("late")
        assertNull(flow.first())
        OnlineTrackSession.remember(listOf(song("late", SourceType.ONLINE)))
        assertEquals("标题late", flow.first()?.title)
        OnlineTrackSession.clear()
    }
}
