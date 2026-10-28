package com.muses.player.core.model.online

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [CachedOnlineTrackMetadataResolver] 测试：命中/不缓存失败/有界 LRU 三条语义 */
class CachedOnlineTrackMetadataResolverTest {
    @kotlin.test.Test
    fun preferenceChangeDoesNotReusePreviousSourceMetadata() = kotlinx.coroutines.runBlocking {
        var preference = "first"
        val resolver = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef) = "https://test.invalid/$preference.jpg"
            override suspend fun resolveLyrics(ref: OnlineTrackRef) = OnlineTrackLyrics(lyric = preference)
        }
        val cached = CachedOnlineTrackMetadataResolver(resolver, namespaceProvider = { preference })
        val ref = OnlineTrackRef("kw", "{}", "online")
        kotlin.test.assertEquals("first", cached.resolveLyrics(ref)?.lyric)
        kotlin.test.assertEquals("https://test.invalid/first.jpg", cached.resolveCover(ref))
        preference = "second"
        kotlin.test.assertEquals("second", cached.resolveLyrics(ref)?.lyric)
        kotlin.test.assertEquals("https://test.invalid/second.jpg", cached.resolveCover(ref))
    }

    @Test fun `跨平台回退后不用原平台已缓存的歌词`() = runTest {
        OnlineTrackSession.clear()
        try {
            val fake = FakeResolver()
            val cached = CachedOnlineTrackMetadataResolver(fake)
            val original = OnlineTrackRef("wy", "original", "s1")
            val actual = OnlineTrackRef("tx", "actual", "s1")
            cached.resolveLyrics(original)
            OnlineTrackSession.rememberResolution(original, actual)
            fake.lyrics = OnlineTrackLyrics(lyric = "[00:01]实际音频歌词")
            assertEquals("[00:01]实际音频歌词", cached.resolveLyrics(original)?.lyric)
            assertEquals(listOf("original", "actual"), fake.lyricCalls)
        } finally { OnlineTrackSession.clear() }
    }

    private class FakeResolver : OnlineTrackMetadataResolver {
        val coverCalls = mutableListOf<String>()
        val lyricCalls = mutableListOf<String>()
        var cover: String? = "http://cdn.test/cover.jpg"
        var lyrics: OnlineTrackLyrics? = OnlineTrackLyrics(lyric = "[00:01.000]hi")

        override suspend fun resolveCover(ref: OnlineTrackRef): String? {
            coverCalls += ref.musicInfoJson
            return cover
        }

        override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? {
            lyricCalls += ref.musicInfoJson
            return lyrics
        }
    }

    private fun ref(id: String) = OnlineTrackRef(platform = "kw", musicInfoJson = id, sourceId = "s1")

    @Test
    fun `同一曲目第二次请求命中缓存`() = runTest {
        val fake = FakeResolver()
        val cached = CachedOnlineTrackMetadataResolver(fake)

        assertEquals("http://cdn.test/cover.jpg", cached.resolveCover(ref("a")))
        assertEquals("http://cdn.test/cover.jpg", cached.resolveCover(ref("a")))
        assertEquals(1, fake.coverCalls.size)

        val lyric = cached.resolveLyrics(ref("a"))
        assertEquals("[00:01.000]hi", lyric?.lyric)
        assertEquals(1, fake.lyricCalls.size)
    }

    @Test
    fun `不同曲目各自请求`() = runTest {
        val fake = FakeResolver()
        val cached = CachedOnlineTrackMetadataResolver(fake)

        cached.resolveLyrics(ref("a"))
        cached.resolveLyrics(ref("b"))
        cached.resolveLyrics(ref("a"))
        assertEquals(listOf("a", "b"), fake.lyricCalls)
    }

    @Test
    fun `失败结果不缓存（网络恢复后可重试）`() = runTest {
        val fake = FakeResolver().apply { lyrics = null }
        val cached = CachedOnlineTrackMetadataResolver(fake)

        assertNull(cached.resolveLyrics(ref("a")))
        fake.lyrics = OnlineTrackLyrics(lyric = "[00:02.000]retry")
        // 上一次失败未被缓存 → 第二次直接拿到新结果
        assertEquals("[00:02.000]retry", cached.resolveLyrics(ref("a"))?.lyric)
        assertEquals(2, fake.lyricCalls.size)
    }

    @Test
    fun `空歌词与空封面视为未命中不入缓存`() = runTest {
        val fake = FakeResolver().apply {
            lyrics = OnlineTrackLyrics(lyric = "  ")
            cover = "  "
        }
        val cached = CachedOnlineTrackMetadataResolver(fake)

        assertNull(cached.resolveLyrics(ref("a")))
        assertNull(cached.resolveCover(ref("a")))
        // 第二次仍走实请求（空值未入缓存）
        assertNull(cached.resolveLyrics(ref("a")))
        assertNull(cached.resolveCover(ref("a")))
        assertEquals(2, fake.lyricCalls.size)
        assertEquals(2, fake.coverCalls.size)
    }

    @Test
    fun `超出容量淘汰最久未使用条目`() = runTest {
        val fake = FakeResolver()
        val cached = CachedOnlineTrackMetadataResolver(fake, maxEntries = 1)

        cached.resolveLyrics(ref("a"))
        cached.resolveLyrics(ref("b")) // 淘汰 a
        cached.resolveLyrics(ref("b")) // 命中
        assertEquals(listOf("a", "b"), fake.lyricCalls)

        cached.resolveLyrics(ref("a")) // a 已淘汰 → 重新请求
        assertEquals(listOf("a", "b", "a"), fake.lyricCalls)
    }
}
