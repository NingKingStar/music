package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.PlaylistAddress
import com.muses.player.core.search.provider.PlaylistImportService
import com.muses.player.core.search.provider.parsePlaylistAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistImportTest {
    @Test fun `识别分享链接且不识别无关网站`() {
        assertEquals(PlaylistAddress("wy", "42"), parsePlaylistAddress("分享 https://music.163.com/#/playlist?id=42&x=1"))
        assertEquals(PlaylistAddress("tx", "567"), parsePlaylistAddress("https://y.qq.com/n/ryqq/playlist/567"))
        assertFailsWith<IllegalArgumentException> { parsePlaylistAddress("https://example.com/playlist?id=42") }
    }

    @Test fun `网易保留完整身份顺序且详情缺失交给自动补全`() = runTest {
        var missing = false
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.contains("playlist"))
                """{"playlist":{"name":"歌单","trackCount":2,"trackIds":[{"id":2},{"id":1}]}}"""
            else if (missing) """{"songs":[{"id":1,"name":"一"}]}"""
            else """{"songs":[{"id":1,"name":"一"},{"id":2,"name":"二"}]}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val service = PlaylistImportService(SearchHttp(client))
            assertEquals(listOf("2", "1"), service.load(PlaylistAddress("wy", "3")).songs.map { it.songId })
            missing = true
            assertEquals(listOf("2", "1"), service.load(PlaylistAddress("wy", "3")).songs.map { it.songId })
        } finally { client.close() }
    }

    @Test fun `QQ 分页完整读取且不把缺页当成功`() = runTest {
        var calls = 0
        var missing = false
        val client = HttpClient(MockEngine {
            calls++
            val page = if (calls % 2 == 1) (1..50).joinToString(",") { """{"mid":"$it","name":"歌$it"}""" }
                else if (missing) "" else """{"mid":"51","name":"歌51"}"""
            respond("""{"req":{"code":0,"data":{"dirinfo":{"title":"QQ歌单","songnum":51},"songlist":[$page]}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val service = PlaylistImportService(SearchHttp(client))
            val result = service.load(PlaylistAddress("tx", "4"))
            assertEquals("QQ歌单", result.name)
            assertEquals((1..51).map(Int::toString), result.songs.map { it.songId })
            missing = true
            assertFailsWith<IllegalArgumentException> { service.load(PlaylistAddress("tx", "4")) }
        } finally { client.close() }
    }
}
