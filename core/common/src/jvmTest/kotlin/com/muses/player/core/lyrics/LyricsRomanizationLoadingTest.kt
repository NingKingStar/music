package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.aligner.TimedPronunciation
import com.muses.player.core.lyrics.amll.*
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.model.*
import com.muses.player.core.lyrics.parser.LrcLyricsParser
import com.muses.player.core.lyrics.provider.*
import com.muses.player.core.model.lyrics.*
import com.muses.player.core.model.online.OnlineTrackRef
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LyricsRomanizationLoadingTest {
    private val original = "[00:05.00]君の声が\n[00:10.00]夜に響く\n[00:15.00]너의 목소리\n[00:20.00]밤에 울려"
    private val translated = "[00:05.00]你的声音\n[00:10.00]响彻夜晚\n[00:15.00]你的声音\n[00:20.00]响彻夜晚"
    private val reading = "[00:05.00]kimi no koe ga\n[00:10.00]yoru ni hibiku\n[00:15.00]neoui moksori\n[00:20.00]bame ullyeo"
    private val query = OnlineLyricsQuery("reading", "测试歌曲", "测试歌手", trackRef = OnlineTrackRef("wy", "{}", "online"))
    private class Amll : AmllTtmlDbClient(LyricsHttp(), AmllIndexRepository { "" }) {
        override suspend fun match(query: AmllMatchQuery) = AmllMatchResult.Fail(AmllFailReason.NO_MATCH)
    }
    private fun provider(source: OnlineLyricsSource, counter: AtomicInteger = AtomicInteger(), wait: Long = 0,
        block: () -> OnlineLyricsProviderHit?) = object : LyricsProvider {
        override val id = source
        override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit? {
            counter.incrementAndGet(); delay(wait); return block()
        }
    }
    private fun loader(scope: CoroutineScope, providers: List<LyricsProvider>, dir: java.io.File? = null,
        now: () -> Long = { System.currentTimeMillis() }) = ProgressiveLyricsLoader(Amll(), providers,
        dir?.let { { it } }, scope, nowMs = now, totalTimeoutMs = 700, providerTimeoutMs = 350, hedgeDelayMs = 20)
    private fun hit(roman: String? = null, translation: String? = translated) =
        OnlineLyricsProviderHit(original, OnlineLyricsFormat.LRC, translation, roman)

    @Test fun completeTranslationStillSearchesQqReadingAndNeverUsesQqTranslation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tx = AtomicInteger()
            val updates = loader(scope, listOf(provider(OnlineLyricsSource.WY) { hit() },
                provider(OnlineLyricsSource.TX, tx, 50) { hit(reading, "[00:05.00]不能显示的译文") })).observe(query).toList()
            assertNull(updates.first().document.lines.first().romanization)
            assertEquals("kimi no koe ga", updates.last().document.lines.first().romanization)
            assertEquals("你的声音", updates.last().document.lines.first().translation)
            assertEquals("wy", updates.last().translationSource)
            assertEquals("tx", updates.last().romanizationSource)
            assertEquals(1, tx.get())
        } finally { scope.cancel() }
    }

    @Test fun qqReadingWithoutAnyTranslationSurvivesDiskRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("reading-only").toFile()
        try {
            loader(scope, listOf(provider(OnlineLyricsSource.TX) { hit(reading, null) }), dir)
                .observe(query.copy(trackRef = OnlineTrackRef("tx", "{}", "online"))).toList()
            val calls = AtomicInteger()
            val updates = loader(scope, listOf(provider(OnlineLyricsSource.TX, calls) { error("不应重新请求") }), dir)
                .observe(query.copy(trackRef = OnlineTrackRef("tx", "{}", "online"))).toList()
            assertEquals("kimi no koe ga", updates.first().document.lines.first().romanization)
            assertTrue(updates.first().fromCache)
            assertTrue(updates.all { it.document.lines.all { l -> l.translation == null } })
            assertEquals("tx", updates.first().romanizationSource)
            assertEquals(0, calls.get())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun failedReadingLookupRetriesAfterShortBackoffNotSixHours() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("reading-retry").toFile()
        var now = 1_800_000_000_000L
        val txCalls = AtomicInteger()
        try {
            val sources = listOf(provider(OnlineLyricsSource.WY) { hit() }, provider(OnlineLyricsSource.TX, txCalls) { null })
            loader(scope, sources, dir) { now }.observe(query).toList()
            now += 10_000
            loader(scope, sources, dir) { now }.observe(query).toList()
            assertEquals(1, txCalls.get())
            now += 21_000
            val updates = loader(scope, listOf(provider(OnlineLyricsSource.WY) { hit() },
                provider(OnlineLyricsSource.TX, txCalls) { hit(reading) }), dir) { now }.observe(query).toList()
            assertTrue(updates.first().fromCache)
            assertEquals(2, txCalls.get())
            assertEquals("kimi no koe ga", updates.last().document.lines.first().romanization)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun explicitMissingReadingHasFiveMinuteCacheThenCanRecover() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("reading-missing").toFile()
        var now = 1_800_000_000_000L
        val calls = AtomicInteger()
        try {
            val sources = listOf(provider(OnlineLyricsSource.WY) { hit() }, provider(OnlineLyricsSource.TX, calls) { hit(null, null) })
            loader(scope, sources, dir) { now }.observe(query).toList()
            now += 4 * 60_000
            loader(scope, sources, dir) { now }.observe(query).toList()
            assertEquals(1, calls.get())
            now += 2 * 60_000
            val updates = loader(scope, listOf(provider(OnlineLyricsSource.WY) { hit(reading) },
                provider(OnlineLyricsSource.TX, calls) { hit() }), dir) { now }.observe(query).toList()
            assertEquals("kimi no koe ga", updates.last().document.lines.first().romanization)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun neteaseLrcReadingEnrichesYrcWithoutLosingWordTiming() = runBlocking {
        val body = buildJsonObject {
            putJsonObject("yrc") { put("lyric", "[5000,1000](5000,1000,0)君の声が\n[10000,1000](10000,1000,0)夜に響く") }
            putJsonObject("lrc") { put("lyric", "[00:05.00]君の声が\n[00:10.00]夜に響く") }
            putJsonObject("romalrc") { put("lyric", "[00:05.00]kimi no koe ga\n[00:10.00]yoru ni hibiku") }
        }
        val hit = pickWyLyricsBody(body)!!
        assertEquals(OnlineLyricsFormat.YRC, hit.format)
        val document = parseLyricsMatch(OnlineLyricsMatchResult.Ok(hit.text, hit.format, OnlineLyricsSource.WY,
            romanizationText = hit.romanizationText, romanizationBaseText = hit.romanizationBaseText,
            romanizationFallbackText = hit.romanizationFallbackText))!!
        assertEquals("kimi no koe ga", document.lines.first().romanization)
        assertTrue(document.lines.all { it.timingKind == LyricTimingKind.Precise && it.syllables.isNotEmpty() })
    }

    @Test fun qqPlainBase64AndEncryptedRomaAreCarriedThroughParser() = runBlocking {
        val encoded = java.util.Base64.getEncoder().encodeToString(reading.toByteArray())
        val hit = parseTxLyricsBody(buildJsonObject { put("lyric", original); put("roma", encoded) })!!
        assertEquals(reading, hit.romanizationText)
        val doc = parseLyricsMatch(OnlineLyricsMatchResult.Ok(hit.text, hit.format, OnlineLyricsSource.TX, romanizationText = hit.romanizationText))!!
        assertEquals("neoui moksori", doc.lines[2].romanization)
        val cipher = "a6e0d4c95b8eeb9d9c3df309f31af834cdcc30ca50c381e5711a47805ffd3ce611fcc9ba2e50e2f3885376bcdb9bbf8ca9137e33146afa0b"
        val encrypted = parseTxLyricsBody(buildJsonObject { put("lyric", cipher); put("roma", cipher) }, encrypted = true)!!
        assertEquals(encrypted.text, encrypted.romanizationText)
    }

    @Test fun differentRecordingOrDifferentTextDoesNotReceiveReading() {
        val base = LrcLyricsParser.parse(original)!!
        val late = LrcLyricsParser.parse(original.replace("00:05", "01:05").replace("00:10", "01:10").replace("00:15", "01:15").replace("00:20", "01:20"), romanization = reading)!!
        assertEquals(base, LyricRomanizationPolicy.enrich(base, late, null, "wy").document)
        val wrong = LrcLyricsParser.parse("[00:05.00]違う曲です\n[00:10.00]違う言葉です", romanization = reading)!!
        assertEquals(base, LyricRomanizationPolicy.enrich(base, wrong, null, "tx").document)
    }

    @Test fun qrcPrefixAndSuffixTimingPreserveEveryOriginalAndReadingWord() {
        val parser = com.muses.player.core.lyrics.parser.QQMusicQrcLyricsParser
        val before = parser.parse("[5000,1000](5000,500)君(5500,500)の", romanization = "[5000,1000](5000,500)kimi(5500,500)no")
        val after = parser.parse("[5000,1000]君(5000,500)の(5500,500)", romanization = "[5000,1000]kimi(5000,500)no(5500,500)")
        assertEquals("君の", after.lines.single().text)
        assertEquals(before.lines.single().syllables, after.lines.single().syllables)
        assertEquals(before.lines.single().romanizationSyllables, after.lines.single().romanizationSyllables)
        assertEquals(listOf("kimi", "no"), after.lines.single().romanizationSyllables.map { it.text })
    }

    @Test fun noPhoneticGuessingForEnglishChineseOrChineseHomophones() {
        assertFalse(LyricRomanizationPolicy.needsReading("Baby, baby, baby, oh"))
        assertFalse(LyricRomanizationPolicy.needsReading("普通中文歌词"))
        assertTrue(LyricRomanizationPolicy.needsReading("生まれてきちゃってごめん"))
        assertTrue(LyricRomanizationPolicy.needsReading("너의 목소리"))
        assertFalse(LyricRomanizationPolicy.valid("ありがとう", "阿里嘎多"))
        assertTrue(LyricRomanizationPolicy.valid("ありがとう", "arigatō"))
    }

    @Test fun mixedLanguageReadingDoesNotRepeatUnchangedLatinLines() {
        val document = LyricsDocument(listOf(
            LyricLine(0, text = "Signal, Siehst du das?", romanization = "Signal , Siehst du das ?"),
            LyricLine(1000, text = "君の声", romanization = "kimi no koe")))
        val cleaned = LyricRomanizationPolicy.removeDuplicateReadings(document)
        assertNull(cleaned.lines.first().romanization)
        assertEquals("kimi no koe", cleaned.lines[1].romanization)
    }

    @Test fun timedReadingPairsOnlyWithMatchingAuthoredTimeNotEqualCountsAlone() {
        val line = LyricLine(timeMs = 1000, text = "君の", romanization = "kimi no",
            timingKind = LyricTimingKind.Precise,
            syllables = listOf(LyricSyllable("君", 1000, 1600), LyricSyllable("の", 1600, 2000)),
            romanizationSyllables = listOf(LyricSyllable("kimi", 1000, 1600), LyricSyllable("no", 1600, 2000)))
        assertEquals("kimi", TimedPronunciation.rubyLine(line)!!.syllables.first().ruby.single().text)
        assertNull(TimedPronunciation.rubyLine(line.copy(romanizationSyllables = line.romanizationSyllables.map { it.copy(startTimeMs = it.startTimeMs + 500) })))
        assertNull(TimedPronunciation.rubyLine(line.copy(timingKind = LyricTimingKind.LineSynchronized)))
        assertNull(TimedPronunciation.rubyLine(line.copy(romanizationSyllables = emptyList())))
    }

    @Test fun cachedCrossPlatformReadingPreservesAuthoredTimingAndRequiresOriginalFingerprint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("timed-reading-cache").toFile()
        val yrc = "[5000,1000](5000,500,0)君(5500,500,0)の\n[10000,1000](10000,500,0)声(10500,500,0)が"
        val qrc = "[5000,1000]君(5000,500)の(5500,500)\n[10000,1000]声(10000,500)が(10500,500)"
        val roma = "[5000,1000]kimi(5000,500)no(5500,500)\n[10000,1000]koe(10000,500)ga(10500,500)"
        try {
            val final = loader(scope, listOf(
                provider(OnlineLyricsSource.WY) { OnlineLyricsProviderHit(yrc, OnlineLyricsFormat.YRC) },
                provider(OnlineLyricsSource.TX, wait = 40) { OnlineLyricsProviderHit(qrc, OnlineLyricsFormat.QRC, romanizationText = roma) }
            ), dir).observe(query).toList().last()
            assertNotNull(TimedPronunciation.rubyLine(final.document.lines.first()))
            val cached = loader(scope, emptyList(), dir).observe(query).toList().first()
            assertEquals(final.document.lines.first().romanizationSyllables, cached.document.lines.first().romanizationSyllables)
            assertNotNull(TimedPronunciation.rubyLine(cached.document.lines.first()))
            val file = dir.listFiles()!!.single { it.extension == "json" }
            val body = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            body["originalFingerprint"] = JsonPrimitive("invalid-original")
            file.writeText(JsonObject(body).toString())
            val rejected = loader(scope, emptyList(), dir).observe(query).toList().first()
            assertTrue(rejected.document.lines.all { it.romanization.isNullOrBlank() })
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
}
