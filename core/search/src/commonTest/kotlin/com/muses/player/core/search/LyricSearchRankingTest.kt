package com.muses.player.core.search

import com.muses.player.core.search.provider.*
import com.muses.player.core.search.http.SearchHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LyricSearchRankingTest {
    @Test fun `只有歌词片段证实命中才优先原唱`() = runTest {
        val client = HttpClient(MockEngine { request ->
            val text = if (request.url.parameters["type"] == "1006")
                """{"result":{"songs":[{"id":1,"name":"晴天","lyrics":["故事的<b>小黄花</b>从出生那年就飘着"]},{"id":2,"name":"另一首","lyrics":["无关歌词"]}]}}"""
            else """{"songs":[{"id":1,"originCoverType":1}]}"""
            respond(text, headers = headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                WySearchProvider(SearchHttp(client)).searchLyrics("小黄花从出生那年")
            }
            assertEquals(listOf("1"), results.map { it.songId })
            assertTrue(results.single().lyricMatched)
            assertEquals("原唱", results.single().performanceLabel)
            val cover = results.single().copy(musicInfoJson = "{}")
            assertTrue(searchResultRank(results.single(), "小黄花从出生那年") > searchResultRank(cover, "小黄花从出生那年"))
            assertFalse(lyricTextMatches("无关片段", "小黄花从出生那年"))
        } finally { client.close() }
    }
}
