package com.muses.player.core.search

import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineArtistRef
import com.muses.player.core.search.provider.*
import com.muses.player.core.search.http.SearchHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class OnlineArtistServiceTest {
    private val jay = OnlineArtistRef("wy", "6452", "周杰伦")
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun `结构化歌手保留合作成员和带斜杠的乐队名`() {
        val result = parseWyTrack(obj("""{"id":1,"name":"歌曲","ar":[{"id":2,"name":"AC/DC"},{"id":3,"name":"合作歌手"}]}"""))!!
        assertEquals(listOf("AC/DC", "合作歌手"), OnlineArtistService.readArtists(result.musicInfoJson).map { it.name })
        assertEquals("online:wy:1", result.toSong("online").id)
    }
    @Test fun `QQ歌手mid与歌曲mid严格分开`() {
        val result = parseTxTrack(obj("""{"mid":"song1","name":"歌曲","singer":[{"mid":"artist2","name":"歌手"}]}"""))!!
        assertEquals("artist2", OnlineArtistService.readArtists(result.musicInfoJson).single().id)
        assertEquals("song1", result.songId)
    }
    @Test fun `有可靠歌手身份无需网络补查`() = runBlocking {
        val client = HttpClient(MockEngine { error("不应请求网络") })
        try {
            val song = parseWyTrack(obj("""{"id":1,"name":"歌","ar":[{"id":6452,"name":"周杰伦"}]}"""))!!.toSong("online")
            val result = OnlineArtistService(SearchHttp(client)).resolve(song)
            assertTrue(result.confirmed); assertEquals(listOf(jay), result.artists)
        } finally { client.close() }
    }
    @Test fun `旧导入歌曲按原曲目编号补查合作歌手`() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { req ->
            calls++; assertTrue(req.url.encodedPath.contains("song/detail"))
            respond("""{"songs":[{"id":1,"name":"歌曲","artists":[{"id":2,"name":"A"},{"id":3,"name":"B"}]}]}""")
        })
        try {
            val song = OnlineSearchResult("wy", "1", "歌曲", "A/B", null, null, null, """{"id":"1"}""").toSong("online")
            val result = OnlineArtistService(SearchHttp(client)).resolve(song)
            assertTrue(result.confirmed); assertEquals(listOf("A", "B"), result.artists.map { it.name }); assertEquals(1,calls)
        } finally { client.close() }
    }
    @Test fun `只有名称时即使同名也交给用户确认`() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"result":{"artists":[{"id":6452,"name":"周杰伦"},{"id":99,"name":"同名候选"}]}}""") })
        try {
            val result = OnlineArtistService(SearchHttp(client)).resolve(Song("local", "local", "/song.flac", "歌", "周杰伦"))
            assertFalse(result.confirmed); assertEquals(2,result.artists.size)
        } finally { client.close() }
    }
    @Test fun `分页偏移隔离缓存且同页复用`() = runBlocking {
        val offsets = mutableListOf<String?>()
        val client = HttpClient(MockEngine { req ->
            offsets += req.url.parameters["offset"]
            val id = if (req.url.parameters["offset"] == "0") 1 else 2
            respond("""{"code":200,"songs":[{"id":$id,"name":"歌曲"}],"more":true}""")
        })
        try {
            val svc=OnlineArtistService(SearchHttp(client))
            assertEquals("1",svc.songs(jay).songs.single().songId)
            assertEquals("1",svc.songs(jay).songs.single().songId)
            assertEquals("2",svc.songs(jay,2).songs.single().songId)
            assertEquals(listOf<String?>("0","30"),offsets)
        } finally { client.close() }
    }
    @Test fun `接口失败不缓存为空曲库`() = runBlocking {
        var calls=0
        val client=HttpClient(MockEngine { calls++; respond(if(calls==1) """{"code":500}""" else """{"code":200,"songs":[],"more":false}""") })
        try {
            val svc=OnlineArtistService(SearchHttp(client))
            assertFailsWith<IllegalArgumentException>{ svc.songs(jay) }
            assertTrue(svc.songs(jay).songs.isEmpty()); assertEquals(2,calls)
        } finally { client.close() }
    }
    @Test fun `QQ目录解析分页和歌手身份`() = runBlocking {
        val client=HttpClient(MockEngine { respond("""{"req":{"code":0,"data":{"total_song":31,"songlist":[{"mid":"s1","name":"歌","singer":[{"mid":"a1","name":"歌手"}]}]}}}""") })
        try {
            val result=OnlineArtistService(SearchHttp(client)).songs(OnlineArtistRef("tx","a1","歌手"))
            assertTrue(result.hasMore); assertEquals("s1",result.songs.single().songId)
        } finally { client.close() }
    }
}
