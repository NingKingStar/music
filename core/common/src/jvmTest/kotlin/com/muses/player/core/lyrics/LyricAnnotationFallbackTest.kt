package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.provider.*
import com.muses.player.core.lyrics.parser.LrcLyricsParser
import com.muses.player.core.model.lyrics.*
import com.muses.player.core.model.online.OnlineTrackRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LyricAnnotationFallbackTest {
    private val original = "[00:05.00]君の声が聞こえる\n[00:10.00]僕は歩いてる"
    private val translated = "[00:05.00]听见你的声音\n[00:10.00]我在前行"
    private val reading = "[00:05.00]kimi no koe ga kikoeru\n[00:10.00]boku wa aruiteru"
    private val query = OnlineLyricsQuery("sample", "测试歌曲", "测试歌手", trackRef = OnlineTrackRef("wy", "{\"id\":1}", "online"))
    private fun body(annotations: Boolean) = buildJsonObject {
        putJsonObject("lrc") { put("lyric", original) }
        if (annotations) {
            putJsonObject("tlyric") { put("lyric", translated) }
            putJsonObject("romalrc") { put("lyric", reading) }
        }
    }.toString()

    @Test fun `逐字接口只有原词仍查询同ID普通接口且原词先交付`() = runBlocking {
        val hits = mutableListOf<OnlineLyricsProviderHit>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("eapi")) respond(body(false)) else {
                assertEquals(1, hits.size, "补充请求开始前必须已发布原词")
                respond(body(true))
            }
        })
        try {
            assertTrue(PlatformLyricsProvider(OnlineLyricsSource.WY, LyricsHttp(client)).searchProgressively(query) { hits += it })
            assertEquals(2, hits.size)
            assertNull(hits.first().translationText)
            assertEquals(reading, hits.last().romanizationText)
        } finally { client.close() }
    }

    @Test fun `首个严格同曲版本无注释时继续第二候选而不选择翻唱`() = runBlocking {
        var eapi = 0
        val hits = mutableListOf<OnlineLyricsProviderHit>()
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.toString().contains("/search/") -> respond("""{"result":{"songs":[{"id":9,"name":"测试歌曲","artists":[{"name":"翻唱歌手"}]},{"id":1,"name":"测试歌曲","artists":[{"name":"测试歌手"}]},{"id":2,"name":"测试歌曲","artists":[{"name":"测试歌手"}]}]}}""")
                request.url.toString().contains("eapi") -> { eapi++; respond(body(eapi == 2)) }
                else -> respond(body(false))
            }
        })
        try {
            PlatformLyricsProvider(OnlineLyricsSource.WY, LyricsHttp(client)).searchProgressively(query) { hits += it }
            assertEquals(2, eapi)
            assertEquals(translated, hits.last().translationText)
        } finally { client.close() }
    }

    @Test fun `补查失败不能写成确认没有音译`() = runBlocking {
        val hits = mutableListOf<OnlineLyricsProviderHit>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("eapi")) respond(body(false)) else throw java.io.IOException("模拟断网")
        })
        try {
            assertFalse(PlatformLyricsProvider(OnlineLyricsSource.WY, LyricsHttp(client)).searchProgressively(query) { hits += it })
            assertEquals(1, hits.size)
        } finally { client.close() }
    }

    @Test fun `发布原词后取消不继续补查`() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond(body(false)) })
        try {
            assertFailsWith<CancellationException> {
                PlatformLyricsProvider(OnlineLyricsSource.WY, LyricsHttp(client)).searchProgressively(query) { throw CancellationException("切歌") }
            }
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun `跨平台一句拆成两行时合并整句读音与译文并保留时间轴`() {
        val base = LrcLyricsParser.parse(original)!!
        val donor = LrcLyricsParser.parse("[00:05.00]君の声が\n[00:06.00]聞こえる\n[00:10.00]僕は歩いてる",
            translation = "[00:05.00]你的声音\n[00:06.00]传入耳中\n[00:10.00]我在前行",
            romanization = "[00:05.00]kimi no koe ga\n[00:06.00]kikoeru\n[00:10.00]boku wa aruiteru")!!
        val result = LyricRomanizationPolicy.enrich(base, donor, null, "wy").document
        assertEquals("kimi no koe ga kikoeru", result.lines.first().romanization)
        assertEquals(base.lines.map { it.timeMs }, result.lines.map { it.timeMs })
        assertTrue(result.lines.first().romanizationSyllables.isEmpty())
        assertEquals("你的声音 传入耳中", LyricTranslationPolicy.enrich(base, donor, null, "wy").document.lines.first().translation)
    }
}
