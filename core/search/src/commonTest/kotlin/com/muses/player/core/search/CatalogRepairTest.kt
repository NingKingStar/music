package com.muses.player.core.search

import com.muses.player.core.search.provider.*
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.model.online.OnlineTrackRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogRepairTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun song(id: String = "1", title: String = "晴天", artist: String = "周杰伦", original: Boolean = false) =
        OnlineSearchResult("wy", id, title, artist, "叶惠美", 269_000, "https://cdn.test/cover.jpg",
            buildMusicInfo("id" to id, name = title, singer = artist, album = "叶惠美", durationSec = 269,
                extra = if (original) mapOf("originCoverType" to "1") else emptyMap()))

    @Test fun `只在同等相关时原唱靠前且歌手意图更优先`() {
        assertTrue(searchResultRank(song(original = true), "晴天") > searchResultRank(song(artist = "其他歌手"), "晴天"))
        assertTrue(searchResultRank(song(artist = "其他歌手"), "晴天 其他歌手") > searchResultRank(song(original = true), "晴天 其他歌手"))
        assertTrue(searchResultRank(song(title = "晴天 Live"), "晴天 Live") > searchResultRank(song(original = true), "晴天 Live"))
        assertNull(song(title = "晴天 原唱版").performanceLabel)
    }

    @Test fun `网易新旧详情解析保留同一身份和原唱音质专辑字段`() {
        val old = parseWyTrack(obj("""{"id":1,"name":"晴天","artists":[{"name":"周杰伦"}],"album":{"id":9,"name":"叶惠美","picUrl":"https://cdn.test/cover.jpg"},"duration":269000,"originCoverType":1,"sqMusic":{"size":100}}"""))!!
        val current = parseWyTrack(obj("""{"id":1,"name":"晴天","ar":[{"name":"周杰伦"}],"al":{"id":9,"name":"叶惠美","picUrl":"https://cdn.test/cover.jpg"},"dt":269000,"originCoverType":1,"sq":{"size":100}}"""))!!
        assertEquals(old, current)
        assertEquals("原唱", old.performanceLabel)
        assertEquals("无损", old.qualityLabel)
        assertEquals("9", obj(old.musicInfoJson)["albumId"]!!.jsonPrimitive.content)
    }

    @Test fun `QQ数字编号不冒充mid且专辑编号单独保存`() {
        val result = parseTxTrack(obj("""{"songInfo":{"mid":"mid-1","id":42,"name":"歌曲","singer":[{"name":"歌手"}],"album":{"id":7,"mid":"album-mid","name":"专辑"},"interval":240}}"""))!!
        val info = obj(result.musicInfoJson)
        assertEquals("mid-1", info["songmid"]!!.jsonPrimitive.content)
        assertEquals("42", info["id"]!!.jsonPrimitive.content)
        assertEquals("7", info["albumId"]!!.jsonPrimitive.content)
        assertEquals(240_000L, result.durationMs)
    }

    @Test fun `补全只填空缺不改手工内容音质和歌词`() {
        val detail = song(original = true)
        val original = detail.toSong("online", "flac").copy(title = "我改的标题", album = "我的专辑", artist = null, coverUri = null,
            durationMs = 0, durationSec = 0, lyrics = "我保存的歌词")
        val repaired = fillMissingSongMetadata(original, detail)
        assertEquals("我改的标题", repaired.title)
        assertEquals("我的专辑", repaired.album)
        assertEquals("周杰伦", repaired.artist)
        assertEquals("我保存的歌词", repaired.lyrics)
        assertEquals(original.id, repaired.id)
        assertEquals("flac", OnlineTrackRef.parse(repaired.path)!!.quality)
        assertEquals(269_000L, repaired.durationMs)
        assertEquals(original, fillMissingSongMetadata(original, song(id = "another")))
    }

    @Test fun `详情不可用不把原歌单写空也不按名字换成其他歌`() = runTest {
        val original = song().toSong("online").copy(coverUri = null)
        val client = HttpClient(MockEngine { respond("""{"songs":[{"id":99,"name":"同名另一首"}]}""") })
        try {
            val result = PlaylistMetadataRepair(SearchHttp(client), OnlineSearchService(emptyList())).inspect(listOf(original))
            assertEquals(listOf(original), result.songs)
        } finally { client.close() }
    }
}
