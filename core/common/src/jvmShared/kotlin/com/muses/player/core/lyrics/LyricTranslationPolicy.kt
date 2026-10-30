package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricsDocument
import java.text.Normalizer
import kotlin.math.abs

/** 只评价译文是否可用、覆盖与匹配，不把这些指标当成语义翻译质量。 */
internal object LyricTranslationPolicy {
    private val han = Regex("[\\p{IsHan}]")
    private val kanaHangul = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
    private val latin = Regex("[A-Za-z]")
    private val credit = Regex("(?i)^(作词|作曲|编曲|翻译|译者|歌词翻译|词[：:]|曲[：:]|lyric[s]?\\s*[:：]|composer\\s*[:：]|written by|lyrics by|composed by|produced by|translated by)|版权|著作权")
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase().filter { it.isLetterOrDigit() }
    private fun foreign(text: String) = !credit.containsMatchIn(text.trim()) && !text.trim().endsWith("：") && !text.trim().endsWith(":") &&
        (kanaHangul.containsMatchIn(text) || (latin.containsMatchIn(text) && !han.containsMatchIn(text)))
    fun validTranslation(original: String, translation: String?): Boolean = !translation.isNullOrBlank() &&
        han.containsMatchIn(translation) && !kanaHangul.containsMatchIn(translation) &&
        !credit.containsMatchIn(translation.trim()) && normalized(original) != normalized(translation)
    fun coverage(document: LyricsDocument): Double {
        val lines = document.lines.filter { foreign(it.text) }
        return if (lines.isEmpty()) 1.0 else lines.count { validTranslation(it.text, it.translation) }.toDouble() / lines.size
    }
    fun hasForeign(document: LyricsDocument) = document.lines.any { foreign(it.text) }
    fun priority(source: String?) = when (source) {
        "local" -> 5
        "wy" -> 4
        "amll" -> 0
        else -> 0
    }
    /** 在线译文仅允许网易；本地用户提供的歌词独立保留，不以音频平台判断来源。 */
    fun restrictTranslations(document: LyricsDocument, source: String?): LyricsDocument =
        if (source == "wy" || source == "local") document else document.copy(lines = document.lines.map {
            it.copy(translation = null, accompaniment = it.accompaniment.map { bg -> bg.copy(translation = null) })
        })
    fun needsEnrichment(document: LyricsDocument, translationSource: String?) = hasForeign(document) &&
        (coverage(document) < 0.8 || priority(translationSource) < 4)

    data class Result(val document: LyricsDocument, val translationSource: String?)

    internal data class Group(val base: List<Int>, val donor: List<Int>)

    /** 允许一句在不同平台拆成两三行，但仍要求整首原文顺序与时间差一致。 */
    internal fun align(base: LyricsDocument, donor: LyricsDocument, minimumCoverage: Double = 0.80): List<Group>? {
        val a = base.lines.withIndex().filter { foreign(it.value.text) }
        val b = donor.lines.withIndex().filter { foreign(it.value.text) }
        if (a.size < 2 || b.size < 2 || maxOf(a.size, b.size) > 600) return null
        val left = a.map { normalized(it.value.text) }
        val right = b.map { normalized(it.value.text) }
        fun segments(lines: List<String>) = lines.indices.map { i ->
            (1..minOf(3, lines.size - i)).map { size -> lines.subList(i, i + size).joinToString("") }
        }
        val ls = segments(left); val rs = segments(right)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            var best = maxOf(dp[i + 1][j], dp[i][j + 1])
            for (x in ls[i].indices) for (y in rs[j].indices) {
                if (ls[i][x].isNotEmpty() && ls[i][x] == rs[j][y])
                    best = maxOf(best, ls[i][x].length + dp[i + x + 1][j + y + 1])
            }
            dp[i][j] = best
        }
        val total = maxOf(left.sumOf { it.length }, right.sumOf { it.length })
        if (total == 0 || dp[0][0].toDouble() / total < minimumCoverage) return null
        val groups = mutableListOf<Group>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            var found = false
            outer@ for (x in ls[i].indices) for (y in rs[j].indices) {
                if (ls[i][x].isNotEmpty() && ls[i][x] == rs[j][y] && dp[i][j] == ls[i][x].length + dp[i + x + 1][j + y + 1]) {
                    groups += Group((i..i + x).map { a[it].index }, (j..j + y).map { b[it].index })
                    i += x + 1; j += y + 1; found = true; break@outer
                }
            }
            if (!found) { if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++ }
        }
        if (groups.size < (if (minimumCoverage < 0.80) minOf(12, minOf(a.size, b.size)) else 2)) return null
        val offsets = groups.map { donor.lines[it.donor.first()].timeMs - base.lines[it.base.first()].timeMs }.sorted()
        val median = offsets[offsets.size / 2]
        if (abs(median) > 5_000 || offsets.count { abs(it - median) <= 1_200 }.toDouble() / offsets.size < 0.85) return null
        return groups.filter { abs(donor.lines[it.donor.first()].timeMs - base.lines[it.base.first()].timeMs - median) <= 1_200 }
    }

    fun compatible(base: LyricsDocument, donor: LyricsDocument) = align(base, donor) != null

    /** 整份采用平台双语歌词与逐行拼接分开：还需足够多的顺序及时间锚点。 */
    fun sameRecording(base: LyricsDocument, donor: LyricsDocument) = align(base, donor, 0.45) != null

    fun preferTranslatedDocument(base: LyricsDocument, donor: LyricsDocument, currentSource: String?, donorSource: String?, identityVerified: Boolean = false) =
        (base.lines.none { it.timingKind == com.muses.player.core.lyrics.model.LyricTimingKind.Precise && it.syllables.isNotEmpty() } || align(base, donor)?.any { it.base.size != 1 || it.donor.size != 1 } != false) &&
            hasForeign(base) && priority(donorSource) > priority(currentSource) && coverage(donor) >= 0.8 &&
            coverage(donor) >= coverage(base) && (identityVerified || sameRecording(base, donor))

    /** 保留当前时间轴；一句对应多行译文时合并，多句对应一句时不擅自拆译文。 */
    fun enrich(base: LyricsDocument, donor: LyricsDocument, currentSource: String?, donorSource: String?): Result {
        val original = Result(base, currentSource)
        if (donorSource !in listOf("wy", "local")) return original
        val groups = align(base, donor) ?: return original
        val currentCoverage = coverage(base)
        val replace = priority(donorSource) > priority(currentSource) && coverage(donor) >= currentCoverage - 0.05
        val translated = mutableMapOf<Int, String>()
        for (group in groups) {
            if (group.base.size == 1 && group.donor.size == 1) {
                val line = donor.lines[group.donor.single()]
                if (validTranslation(line.text, line.translation)) translated[group.base.single()] = line.translation!!
            } else if (group.base.size == group.donor.size && group.base.indices.all {
                    normalized(base.lines[group.base[it]].text) == normalized(donor.lines[group.donor[it]].text)
                }) {
                for (index in group.base.indices) {
                    val line = donor.lines[group.donor[index]]
                    if (validTranslation(line.text, line.translation)) translated[group.base[index]] = line.translation!!
                }
            }
        }
        val lines = base.lines.mapIndexed { index, line ->
            val value = translated[index]
            val translatedLine = if (value != null && (replace || !validTranslation(line.text, line.translation))) line.copy(translation = value) else line
            translatedLine
        }
        if (lines == base.lines) return original
        val result = base.copy(lines = lines)
        if (coverage(result) < currentCoverage) return original
        return Result(result, if (replace || currentCoverage == 0.0 || currentSource == null) donorSource else currentSource)
    }
}
