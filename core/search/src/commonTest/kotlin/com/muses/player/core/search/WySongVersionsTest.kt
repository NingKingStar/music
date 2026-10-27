package com.muses.player.core.search

import com.muses.player.core.search.http.SearchHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WySongVersionsTest {
    private fun song(id: String, name: String = "歌曲") =
        OnlineSearchResult("wy", id, name, "歌手", null, null, null, """{"id":"$id"}""")

    @Test fun `按编号只标记原唱且不凭歌名推断`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("[1,2,3]", request.url.parameters["ids"])
            respond("""{"songs":[
                {"id":2,"originCoverType":2,"al":{"picUrl":"https://example.com/new.jpg"},"hr":{"br":1500000},"sq":{"br":900000}},
                {"id":1,"originCoverType":1,"album":{"picUrl":"https://example.com/old.jpg"},"sqMusic":{"size":123456},"hMusic":{"bitrate":320000}},
                {"id":3,"originCoverType":0,"h":{"br":320000}}
            ]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                enrichWySongVersions(SearchHttp(client), listOf(song("1"), song("2"), song("3", "歌曲（原唱 某歌手）")))
            }
            assertEquals("原唱", results[0].performanceLabel)
            assertNull(results[1].performanceLabel)
            assertNull(results[2].performanceLabel)
            assertEquals("SQ", results[0].qualityLabel)
            assertEquals("Hi-Res", results[1].qualityLabel)
            assertEquals("HQ", results[2].qualityLabel)
            assertEquals("320k,flac", kotlinx.serialization.json.Json.parseToJsonElement(results[0].musicInfoJson).jsonObject["catalogQualityTiers"]?.toString()?.trim('"'))
            assertEquals("flac,hires", kotlinx.serialization.json.Json.parseToJsonElement(results[1].musicInfoJson).jsonObject["catalogQualityTiers"]?.toString()?.trim('"'))
            assertEquals("https://example.com/old.jpg", results[0].coverUrl)
            assertEquals("https://example.com/new.jpg", results[1].coverUrl)
        } finally { client.close() }
    }

    @Test fun `详情接口失败不影响原搜索结果`() = runTest {
        val client = HttpClient(MockEngine { respond("error", HttpStatusCode.ServiceUnavailable) })
        try {
            val songs = listOf(song("1"))
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                enrichWySongVersions(SearchHttp(client), songs)
            }
            assertEquals(songs, results)
        } finally { client.close() }
    }

    @Test fun `缺少音质数据不标注且空的高音质字段不算可用`() {
        fun quality(raw: String) = wyCatalogQualityLabel(kotlinx.serialization.json.Json.parseToJsonElement(raw).let {
            it as kotlinx.serialization.json.JsonObject
        })
        assertNull(quality("{}"))
        assertEquals("标准", quality("""{"hr":{"br":0,"size":0},"sq":null,"l":{"br":128000}}"""))
    }
}
