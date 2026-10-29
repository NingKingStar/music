package com.muses.player.core.search

import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.search.provider.buildMusicInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossPlatformTrackMatcherTest {
    @Test fun `只移除宣传文字且不自动采用未知时长或不同版本`() = runTest {
        val matcher = CrossPlatformTrackMatcher(OnlineSearchService(listOf(provider("tx") {
            listOf(song("promo", name = "晴天（电视剧某某主题曲）"), song("unknown").copy(durationMs = null),
                song("live", name = "晴天 Live"), song("wrong-artist", artist = "翻唱歌手"),
                song("hidden-live").copy(album = "巡回演唱会"), song("instrumental").copy(album = "伴奏版"))
        })))
        val ref = OnlineTrackRef("wy", buildMusicInfo("id" to "1", name = "晴天", singer = "周杰伦", durationSec = 269), "online")
        val matches = matcher.candidates(ref, listOf("tx"))
        assertEquals(1, matches.size)
        assertTrue(matches.single().musicInfoJson.contains("promo"))
    }
    private fun song(id: String, name: String = "晴天", artist: String = "周杰伦", duration: Long = 269_000) =
        OnlineSearchResult("tx", id, name, artist, "叶惠美", duration, null,
            buildMusicInfo("songmid" to id, name = name, singer = artist, durationSec = duration / 1000))

    private fun provider(platform: String, search: suspend () -> List<OnlineSearchResult>) = object : OnlineSearchProvider {
        override val platform = platform
        override val displayName = platform
        override suspend fun search(keyword: String, page: Int, pageSize: Int) =
            OnlineSearchPage(platform, keyword, page, search(), false)
    }

    @Test fun `拒绝同名翻唱现场版和时长不符的候选`() = runTest {
        var originalQueried = false
        val matcher = CrossPlatformTrackMatcher(OnlineSearchService(listOf(
            provider("wy") { originalQueried = true; emptyList() },
            provider("tx") { listOf(song("cover", artist = "其他歌手"), song("live", name = "晴天 (Live)"),
                song("short", duration = 90_000), song("correct")) },
            provider("kw") { error("平台暂时不可用") },
        )))
        val ref = OnlineTrackRef("wy", buildMusicInfo("id" to "1", name = "晴天", singer = "周杰伦", durationSec = 269), "online", "320k")
        val result = matcher.candidates(ref, listOf("wy", "tx", "kw"))
        assertEquals(1, result.size)
        assertEquals("tx", result.single().platform)
        assertTrue(result.single().musicInfoJson.contains("correct"))
        assertEquals("320k", result.single().quality)
        assertEquals(false, originalQueried)
    }

    @Test fun `缺少歌手时不凭同名歌曲回退`() = runTest {
        var queried = false
        val matcher = CrossPlatformTrackMatcher(OnlineSearchService(listOf(provider("tx") { queried = true; listOf(song("1")) })))
        assertTrue(matcher.candidates(OnlineTrackRef("wy", """{"name":"晴天"}""", "online"), listOf("tx")).isEmpty())
        assertEquals(false, queried)
    }
}
