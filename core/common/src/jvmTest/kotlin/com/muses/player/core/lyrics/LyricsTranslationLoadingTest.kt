package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.amll.*
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.model.LyricTimingKind
import com.muses.player.core.lyrics.provider.*
import com.muses.player.core.model.lyrics.*
import com.muses.player.core.model.online.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LyricsTranslationLoadingTest {
    private val original = "[00:05.00]A quiet morning\n[00:10.00]I walk beside you\n[00:15.00]We follow the light\n[00:20.00]The road leads home"
    private val translation = "[00:05.00]清晨安静\n[00:10.00]与你同行\n[00:15.00]追随光芒\n[00:20.00]道路通向家"
    private val query = OnlineLyricsQuery("track", "测试歌曲", "测试歌手", trackRef = OnlineTrackRef("kw", """{"rid":"1"}""", "online"))
    private fun provider(id: OnlineLyricsSource, delayMs: Long = 0, count: AtomicInteger = AtomicInteger(), translated: Boolean = false) = object : LyricsProvider {
        override val id = id
        override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit {
            count.incrementAndGet(); delay(delayMs)
            return OnlineLyricsProviderHit(original, OnlineLyricsFormat.LRC, if (translated) translation else null)
        }
    }
    private class Amll : AmllTtmlDbClient(LyricsHttp(), AmllIndexRepository { "" }) {
        override suspend fun match(query: AmllMatchQuery) = AmllMatchResult.Fail(AmllFailReason.NO_MATCH)
    }
    private fun loader(providers: List<LyricsProvider>, scope: CoroutineScope, dir: java.io.File? = null, now: () -> Long = { System.currentTimeMillis() }) =
        ProgressiveLyricsLoader(Amll(), providers, dir?.let { { it } }, scope, nowMs = now, totalTimeoutMs = 700, providerTimeoutMs = 350, hedgeDelayMs = 20)

    @Test fun originalFirstDoesNotStopTranslationEnrichment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val calls = AtomicInteger()
            val updates = loader(listOf(provider(OnlineLyricsSource.KW), provider(OnlineLyricsSource.WY, 50, calls, true)), scope).observe(query).toList()
            assertNull(updates.first().document.lines.first().translation)
            assertEquals("清晨安静", updates.last().document.lines.first().translation)
            assertEquals("wy", updates.last().translationSource)
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }
    @Test fun translatedLinesEnrichScriptWordTiming() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val metadata = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? = null
            override suspend fun resolveLyrics(ref: OnlineTrackRef) = OnlineTrackLyrics(lxlyric =
                "[00:05.000]<5000,800>A quiet morning\n[00:10.000]<10000,800>I walk beside you\n[00:15.000]<15000,800>We follow the light\n[00:20.000]<20000,800>The road leads home")
        }
        try {
            val updates = loader(listOf(provider(OnlineLyricsSource.WY, 80, translated = true)), scope).observe(query, metadata).toList()
            val final = updates.last()
            assertEquals("清晨安静", final.document.lines.first().translation)
            assertTrue(final.document.lines.all { it.syllables.isNotEmpty() && it.timingKind == LyricTimingKind.Precise })
            assertEquals(5_000L, final.document.lines.first().timeMs)
        } finally { scope.cancel() }
    }
    @Test fun missingTranslationCacheRetriesAndMergedCacheSurvivesRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("translation-cache").toFile()
        var now = 1_800_000_000_000L
        try {
            loader(listOf(provider(OnlineLyricsSource.KW)), scope, dir) { now }.observe(query).toList()
            now += 6 * 60_000L
            val updates = loader(listOf(provider(OnlineLyricsSource.WY, translated = true)), scope, dir) { now }.observe(query).toList()
            assertTrue(updates.first().fromCache)
            assertEquals("清晨安静", updates.last().document.lines.first().translation)
            val calls = AtomicInteger()
            val cached = loader(listOf(provider(OnlineLyricsSource.WY, count = calls)), scope, dir) { now }.observe(query).toList()
            assertEquals("清晨安静", cached.single().document.lines.first().translation)
            assertEquals("wy", cached.single().translationSource)
            assertEquals(0, calls.get())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun legacyCacheIsShownImmediatelyAndStillRechecked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("legacy-translation-cache").toFile()
        try {
            loader(listOf(provider(OnlineLyricsSource.KW)), scope, dir).observe(query).toList()
            val file = dir.listFiles()!!.single { it.extension == "json" }
            val body = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            body.remove("revision")
            file.writeText(JsonObject(body).toString())
            val calls = AtomicInteger()
            val updates = loader(listOf(provider(OnlineLyricsSource.WY, 40, calls, true)), scope, dir).observe(query).toList()
            assertTrue(updates.first().fromCache)
            assertEquals(1, calls.get())
            assertEquals("清晨安静", updates.last().document.lines.first().translation)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun qqProviderPreservesSeparateAndBase64Translation() {
        val hit = parseTxLyricsBody(buildJsonObject { put("lyric", original); put("trans", translation) })!!
        assertEquals(translation, hit.translationText)
        val encoded = java.util.Base64.getEncoder().encodeToString(translation.toByteArray())
        assertEquals(translation, parseTxLyricsBody(buildJsonObject { put("lyric", original); put("trans", encoded) })?.translationText)
    }
    @Test fun qqEncryptedResponseCarriesBothFields() {
        val cipher = "a6e0d4c95b8eeb9d9c3df309f31af834cdcc30ca50c381e5711a47805ffd3ce611fcc9ba2e50e2f3885376bcdb9bbf8ca9137e33146afa0b"
        val hit = parseTxLyricsBody(buildJsonObject { put("lyric", cipher); put("trans", cipher) }, encrypted = true)!!
        assertTrue(hit.text.contains("Hello World"))
        assertEquals(hit.text, hit.translationText)
        assertEquals(OnlineLyricsFormat.QRC, hit.format)
    }
    @Test fun neteaseYrcUsesItsOwnTranslationField() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"yrc":{"lyric":"[5000,1000](5000,1000,0)Hello"},"ytlrc":{"lyric":"[00:05.00]逐字对应译文"},"tlyric":{"lyric":"[00:05.00]旧时间轴译文"}}""") })
        try {
            val hit = searchWyLyrics(LyricsHttp(client), query.copy(trackRef = OnlineTrackRef("wy", """{"id":1}""", "online")))!!
            assertEquals("[00:05.00]逐字对应译文", hit.translationText)
            val doc = parseLyricsMatch(OnlineLyricsMatchResult.Ok(hit.text, hit.format, OnlineLyricsSource.WY, hit.translationText))!!
            assertEquals("逐字对应译文", doc.lines.first().translation)
            assertTrue(doc.lines.first().syllables.isNotEmpty())
        } finally { client.close() }
    }
    @Test fun qqTranslationIsNeverShownEvenBeforeNeteaseArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = loader(listOf(provider(OnlineLyricsSource.TX, translated = true),
                provider(OnlineLyricsSource.WY, 80, translated = true)), scope)
                .observe(query.copy(trackRef = OnlineTrackRef("tx", "{}", "online"))).toList()
            assertTrue(updates.filter { it.translationSource != "wy" }.all { u -> u.document.lines.all { it.translation == null } })
            assertEquals("wy", updates.last().translationSource)
            assertEquals("清晨安静", updates.last().document.lines.first().translation)
        } finally { scope.cancel() }
    }

    @Test fun legacyQqCacheIsStrippedAndRecheckedImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("qq-cache-migration").toFile()
        try {
            loader(listOf(provider(OnlineLyricsSource.TX)), scope, dir).observe(query).toList()
            val file = dir.listFiles()!!.single { it.extension == "json" }
            val body = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            body["revision"] = JsonPrimitive(2)
            body["translation"] = JsonPrimitive(translation)
            body["translationSource"] = JsonPrimitive("tx")
            file.writeText(JsonObject(body).toString())
            val updates = loader(listOf(provider(OnlineLyricsSource.WY, 60, translated = true)), scope, dir).observe(query).toList()
            assertTrue(updates.first().fromCache)
            assertTrue(updates.first().document.lines.all { it.translation == null })
            assertEquals("wy", updates.last().translationSource)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun missingNeteaseDoesNotFallBackToOtherTranslations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = loader(listOf(provider(OnlineLyricsSource.TX, translated = true),
                provider(OnlineLyricsSource.KW, translated = true)), scope).observe(query).toList()
            assertTrue(updates.isNotEmpty())
            assertTrue(updates.all { u -> u.document.lines.all { it.translation == null } })
        } finally { scope.cancel() }
    }

    @Test fun neteaseRomanizationSurvivesParserAndDiskRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("romanization-cache").toFile()
        val source = object : LyricsProvider {
            override val id = OnlineLyricsSource.WY
            override suspend fun searchLyrics(query: OnlineLyricsQuery) =
                OnlineLyricsProviderHit(original, OnlineLyricsFormat.LRC, translation, "[00:05.00]quiet morning pronunciation")
        }
        try {
            val first = loader(listOf(source), scope, dir).observe(query).toList().last()
            assertEquals("quiet morning pronunciation", first.document.lines.first().romanization)
            val calls = AtomicInteger()
            val cached = loader(listOf(provider(OnlineLyricsSource.WY, count = calls)), scope, dir).observe(query).toList().single()
            assertEquals(first.document.lines.first().romanization, cached.document.lines.first().romanization)
            assertEquals(0, calls.get())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun yrcWithoutMatchingTranslationUsesCompleteLrcPair() {
        val hit = pickWyLyricsBody(Json.parseToJsonElement("""{"yrc":{"lyric":"[5000,1000](5000,1000,0)Hello world"},"lrc":{"lyric":"[00:05.00]Hello\n[00:06.00]world"},"tlyric":{"lyric":"[00:05.00]你好\n[00:06.00]世界"},"romalrc":{"lyric":"[00:05.00]he lo"}}""").jsonObject)!!
        assertEquals(OnlineLyricsFormat.LRC, hit.format)
        assertTrue(hit.text.contains("[00:06.00]world"))
        assertEquals("[00:05.00]he lo", hit.romanizationText)
    }

}
