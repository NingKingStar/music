package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricsDocument
import java.text.Normalizer

/** 读音不是译文：只补平台标注，不做中文谐音或机器逐字转换。 */
internal object LyricRomanizationPolicy {
    private val kanaHangul = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
    private val latin = Regex("[A-Za-zāīūēōĀĪŪĒŌ]")
    private val credit = Regex("(?i)^(作词|作曲|编曲|翻译|译者|词[：:]|曲[：:]|lyrics?\\s*[:：]|composer\\s*[:：])")
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
    fun needsReading(text: String) = kanaHangul.containsMatchIn(text) && !credit.containsMatchIn(text.trim())
    fun valid(original: String, reading: String?) = !reading.isNullOrBlank() && latin.containsMatchIn(reading) &&
        !Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]").containsMatchIn(reading) &&
        normalized(original) != normalized(reading) && !credit.containsMatchIn(reading.trim())
    fun coverage(document: LyricsDocument): Double {
        val lines = document.lines.filter { needsReading(it.text) }
        return if (lines.isEmpty()) 1.0 else lines.count { valid(it.text, it.romanization) }.toDouble() / lines.size
    }
    fun needsEnrichment(document: LyricsDocument) = coverage(document) < .8
    fun removeDuplicateReadings(document: LyricsDocument) = document.copy(lines = document.lines.map { line ->
        if (!line.romanization.isNullOrBlank() && normalized(line.text) == normalized(line.romanization))
            line.copy(romanization = null, romanizationSyllables = emptyList()) else line
    })
    fun priority(source: String?) = when (source) { "local" -> 5; "wy" -> 4; "tx" -> 3; "amll" -> 2; "script" -> 1; else -> 0 }
    data class Result(val document: LyricsDocument, val source: String?)
    fun enrich(base: LyricsDocument, donor: LyricsDocument, currentSource: String?, donorSource: String?): Result {
        val original = Result(base, currentSource)
        if (priority(donorSource) == 0) return original
        val groups = LyricTranslationPolicy.align(base, donor) ?: return original
        val replacements = groups.filter { it.base.size == 1 && it.donor.size == 1 }
            .associate { it.base.single() to donor.lines[it.donor.single()] }
        val replace = priority(donorSource) > priority(currentSource) && coverage(donor) >= coverage(base)
        var changed = false
        val lines = base.lines.mapIndexed { index, line ->
            val other = replacements[index]
            if (other != null && valid(line.text, other.romanization) && (replace || !valid(line.text, line.romanization))) {
                changed = true
                // 跨平台逐字时刻不能移植到当前时间轴。
                line.copy(romanization = other.romanization, romanizationSyllables =
                    if (line.timeMs == other.timeMs && line.text == other.text) other.romanizationSyllables else emptyList())
            } else line
        }
        return if (changed) Result(base.copy(lines = lines), if (replace || currentSource == null) donorSource else currentSource) else original
    }
}
