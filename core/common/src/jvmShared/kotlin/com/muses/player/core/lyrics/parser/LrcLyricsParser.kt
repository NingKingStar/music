package com.muses.player.core.lyrics.parser

import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.lyrics.model.NeteaseLyricParser

/**
 * Provider-neutral entry point for ordinary LRC lyrics. The mature timing and
 * annotation implementation remains shared with the existing NetEase parser.
 *
 * Ordinary provider LRC is a line-timed fallback, not genuine word timing. Keep
 * synthetic grapheme timing disabled so the player does not promote it into the
 * expensive word-by-word renderer. Providers with real QRC/KRC timing populate
 * [LyricLine.syllables] directly and are unaffected.
 */
object LrcLyricsParser {
    private val latinLetter = Regex("[A-Za-z]")
    private val kanaOrHangul = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
    private val hanCharacter = Regex("[\u3400-\u9FFF]")

    fun parse(
        lrc: String,
        translation: String = "",
        romanization: String = "",
    ): LyricsDocument {
        val document = NeteaseLyricParser.parse(
            yrc = "",
            lrc = lrc,
            translatedLrc = translation,
            romanizedLrc = romanization,
        )
        return document.copy(
            lines = if (translation.isBlank()) mergeInlineTranslations(document.lines) else document.lines,
            pseudoTimingAllowed = false,
        )
    }

    private fun mergeInlineTranslations(lines: List<LyricLine>): List<LyricLine> = buildList {
        var index = 0
        while (index < lines.size) {
            val original = lines[index]
            val next = lines.getOrNull(index + 1)
            if (next != null && original.timeMs == next.timeMs &&
                original.translation.isNullOrBlank() && isTranslationPair(original.text, next.text)
            ) {
                add(original.copy(translation = next.text, durationMs = next.durationMs ?: original.durationMs))
                index += 2
            } else {
                add(original)
                index++
            }
        }
    }

    private fun isTranslationPair(original: String, candidate: String): Boolean {
        if (candidate.contains("著作权") || candidate.contains("版权") || candidate.contains("翻译作品")) return false
        return (kanaOrHangul.containsMatchIn(original) ||
            (latinLetter.containsMatchIn(original) && !hanCharacter.containsMatchIn(original))) &&
            hanCharacter.containsMatchIn(candidate) && !kanaOrHangul.containsMatchIn(candidate)
    }
}
