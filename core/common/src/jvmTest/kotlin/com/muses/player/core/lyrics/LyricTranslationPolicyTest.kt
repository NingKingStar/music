package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.*
import com.muses.player.core.lyrics.parser.LrcLyricsParser
import org.junit.Test
import kotlin.test.*

class LyricTranslationPolicyTest {
    private val words = listOf("A quiet morning", "I walk beside you", "We follow the light", "The road leads home")
    private fun document(translated: Boolean = false, offset: Long = 0, precise: Boolean = false) = LyricsDocument(
        words.mapIndexed { index, text -> LyricLine(5_000L + index * 5_000L + offset, text = text,
            translation = if (translated) "译文第" + index + "句" else null,
            syllables = if (precise) listOf(LyricSyllable(text, 5_000L + index * 5_000L, 6_000L + index * 5_000L)) else emptyList(),
            timingKind = if (precise) LyricTimingKind.Precise else LyricTimingKind.LineSynchronized) })

    @Test fun preservesWordTimingWhenAddingTranslation() {
        val base = document(precise = true)
        val result = LyricTranslationPolicy.enrich(base, document(true, 600), "script", "wy")
        assertEquals(base.lines.map { it.syllables }, result.document.lines.map { it.syllables })
        assertEquals(base.lines.map { it.timeMs }, result.document.lines.map { it.timeMs })
        assertEquals(1.0, LyricTranslationPolicy.coverage(result.document))
        assertEquals("wy", result.translationSource)
    }
    @Test fun prefersPlatformTranslationButDoesNotReplaceItWithFallback() {
        val base = document(true).let { it.copy(lines = it.lines.map { l -> l.copy(translation = "旧译文") }) }
        val donor = document(true)
        assertEquals("译文第0句", LyricTranslationPolicy.enrich(base, donor, "kw", "wy").document.lines.first().translation)
        assertEquals(base, LyricTranslationPolicy.enrich(base, donor, "wy", "kw").document)
    }
    @Test fun rejectsDifferentTextAndLiveTiming() {
        val base = document()
        val other = document(true).let { it.copy(lines = it.lines.map { l -> l.copy(text = "Different original " + l.timeMs) }) }
        assertEquals(base, LyricTranslationPolicy.enrich(base, other, "tx", "wy").document)
        assertEquals(base, LyricTranslationPolicy.enrich(base, document(true, 20_000), "tx", "wy").document)
        val drifting = document(true).let { it.copy(lines = it.lines.mapIndexed { i, l -> l.copy(timeMs = l.timeMs + i * 3_000) }) }
        assertEquals(base, LyricTranslationPolicy.enrich(base, drifting, "tx", "wy").document)
    }
    @Test fun partialTranslationAndRomanizationAreNotCompleteTranslation() {
        val partial = document().let { it.copy(lines = it.lines.mapIndexed { i, l -> l.copy(translation = if (i == 0) "译文" else null) }) }
        assertEquals(0.25, LyricTranslationPolicy.coverage(partial))
        assertTrue(LyricTranslationPolicy.needsEnrichment(partial, "wy"))
        assertFalse(LyricTranslationPolicy.validTranslation("안녕하세요", "annyeonghaseyo"))
        assertFalse(LyricTranslationPolicy.validTranslation("Hello", "Hello"))
        assertFalse(LyricTranslationPolicy.validTranslation("Hello", "翻译作品著作权"))
    }
    @Test fun pairsJapaneseAndKoreanButPreservesTwoOriginalLines() {
        for (original in listOf("君の声が聞こえる", "너의 목소리가 들려")) {
            val doc = LrcLyricsParser.parse("[00:01.00]" + original + "\n[00:01.00]听到你的声音\n[00:05.00]" + original)
            assertEquals(2, doc.lines.size)
            assertEquals("听到你的声音", doc.lines.first().translation)
            assertEquals(original, doc.lines.first().text)
        }
        val doc = LrcLyricsParser.parse("[00:01.00]君の声\n[00:01.00]私の声")
        assertEquals(2, doc.lines.size)
        assertNull(doc.lines.first().translation)
    }
    @Test fun onlyForeignLyricsNeedEnrichment() {
        val chinese = LyricsDocument(listOf(LyricLine(0, text = "中文原文")))
        assertFalse(LyricTranslationPolicy.needsEnrichment(chinese, "kw"))
        assertFalse(LyricTranslationPolicy.needsEnrichment(document(true), "wy"))
        assertTrue(LyricTranslationPolicy.needsEnrichment(document(true), "kw"))
    }

    @Test fun differentSentenceBoundariesRequireCompletePlatformDocument() {
        val base = document(precise = true).let { it.copy(lines = listOf(
            it.lines[0].copy(text = "A quiet morning I walk beside you"),
            it.lines[2].copy(text = "We follow the light The road leads home"))) }
        val result = LyricTranslationPolicy.enrich(base, document(true), "script", "wy")
        assertEquals(base, result.document)
        assertTrue(LyricTranslationPolicy.preferTranslatedDocument(base, document(true), "script", "wy", true))
    }
    @Test fun doesNotDuplicateOneTranslationAcrossTwoOriginalLines() {
        val base = document(precise = true)
        val donor = document(true).let { it.copy(lines = listOf(
            it.lines[0].copy(text = "A quiet morning I walk beside you"),
            it.lines[2].copy(text = "We follow the light The road leads home"))) }
        assertTrue(LyricTranslationPolicy.compatible(base, donor))
        assertEquals(base, LyricTranslationPolicy.enrich(base, donor, "script", "wy").document)
    }
}
