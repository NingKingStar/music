package com.muses.player.core.lyrics.aligner

import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.lyrics.model.LyricTimingKind
import kotlin.math.abs

/** 只有来源给出的逐字时序互相吻合才做上方注音，不凭字数或空格猜对应关系。 */
object TimedPronunciation {
    fun rubyLine(line: LyricLine): LyricLine? {
        if (line.timingKind != LyricTimingKind.Precise || line.romanization.isNullOrBlank()) return null
        val original = line.syllables
        val reading = line.romanizationSyllables
        if (original.isEmpty() || original.size != reading.size) return null
        if (original.joinToString("") { it.text }.filterNot(Char::isWhitespace) != line.text.filterNot(Char::isWhitespace)) return null
        if (original.indices.any { i ->
            reading[i].text.isBlank() || original[i].text.isBlank() ||
                original[i].endTimeMs <= original[i].startTimeMs || reading[i].endTimeMs <= reading[i].startTimeMs ||
                abs(original[i].startTimeMs - reading[i].startTimeMs) > 150 ||
                abs(original[i].endTimeMs - reading[i].endTimeMs) > 200
        }) return null
        return line.copy(syllables = original.mapIndexed { i, syllable -> syllable.copy(ruby = listOf(reading[i])) })
    }
}
