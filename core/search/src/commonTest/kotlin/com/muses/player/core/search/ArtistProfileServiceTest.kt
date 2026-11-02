package com.muses.player.core.search

import com.muses.player.core.model.online.OnlineArtistRef
import com.muses.player.core.search.http.SearchHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ArtistProfileServiceTest {
    @Test fun 歌手资料补简介并复用缓存() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            respond(if (request.url.encodedPath.contains("introduction")) """{"briefDesc":"<p>介绍 &amp; 生平</p>"}"""
                else """{"code":200,"artist":{"id":6452,"name":"周杰伦","img1v1Url":"https://image.test/artist.jpg","alias":["Jay Chou"],"musicSize":500,"albumSize":40}}""")
        })
        try {
            val service = OnlineArtistService(SearchHttp(client))
            val artist = OnlineArtistRef("wy", "6452", "周杰伦")
            val profile = service.profile(artist)
            assertEquals("介绍 & 生平", profile.biography)
            assertEquals("https://image.test/artist.jpg", profile.artist.avatar)
            assertEquals(500L, profile.songCount)
            assertEquals(listOf("Jay Chou"), profile.aliases)
            assertEquals(profile, service.profile(artist)); assertEquals(2, calls)
        } finally { client.close() }
    }
    @Test fun 身份不符的资料不缓存且可重试() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            val id = if (calls == 1) 999 else 6452
            respond("""{"code":200,"artist":{"id":$id,"name":"歌手","briefDesc":"简介"}}""")
        })
        try {
            val service = OnlineArtistService(SearchHttp(client)); val artist = OnlineArtistRef("wy", "6452", "歌手")
            assertFailsWith<IllegalArgumentException> { service.profile(artist) }
            assertEquals("简介", service.profile(artist).biography); assertEquals(2, calls)
        } finally { client.close() }
    }
    @Test fun QQ歌手资料不使用歌曲封面且保留真实简介() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"req":{"code":0,"data":{"singer_info":{"mid":"a1","name":"歌手","other_name":"别名"},"singer_brief":"真实简介","total_song":31,"total_album":2}}}""") })
        try {
            val result = OnlineArtistService(SearchHttp(client)).profile(OnlineArtistRef("tx","a1","歌手"))
            assertTrue(result.artist.avatar!!.contains("T001")); assertEquals("真实简介", result.biography)
            assertEquals(31L, result.songCount); assertEquals(listOf("别名"), result.aliases)
        } finally { client.close() }
    }
}
