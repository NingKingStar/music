package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.lyrics.model.LyricsDocument
import kotlin.math.abs

/**
 * 跨版本歌词注解合并（译文 / 读音）。
 *
 * 为什么不直接按行合并：同一首歌在网易常有多个版本（原版 / 现场 / 不同发行 / AI 版），
 * 断句与时间轴都不一样，按"整行文本相等"去匹配会大面积落空——这才是"很多歌没有译文"的根因。
 *
 * 这里的规则对任何歌都成立：
 * 1) 先按归一化文本找同句（同一版本时最准）；
 * 2) 找不到就按时间就近匹配，且距离不超过 [TOLERANCE_MS]（不同断句时的主路径）；
 * 3) 只填空缺，不覆盖已有的更高优先级译文；
 * 4) 返回实际填充行数，调用方用它挑"合得最多"的那个候选版本。
 */
fun mergeAnnotations(base: LyricsDocument, donor: LyricsDocument, toleranceMs: Long = 1_500L): Pair<LyricsDocument, Int> {
    val donorLines = donor.lines
    if (donorLines.isEmpty()) return base to 0
    val donorByText = donorLines
        .map { normalizedLyricText(it.text) to it }
        .filter { it.first.isNotEmpty() }
        .toMap()
    var filled = 0
    val merged = base.lines.map { line ->
        if (line.translation.isNullOrBlank() && line.romanization.isNullOrBlank()) {
            val byText = donorByText[normalizedLyricText(line.text)]
            val match = byText ?: donorLines.minByOrNull { abs(it.timeMs - line.timeMs) }
                ?.takeIf { abs(it.timeMs - line.timeMs) <= toleranceMs }
            if (match == null) {
                line
            } else {
                val translation = line.translation ?: match.translation?.takeIf { it.isNotBlank() }
                val romanization = line.romanization ?: match.romanization?.takeIf { it.isNotBlank() }
                if (translation != line.translation || romanization != line.romanization) filled++
                line.copy(translation = translation, romanization = romanization)
            }
        } else {
            line
        }
    }
    return base.copy(lines = merged) to filled
}

/** 归一化：去空白与标点、忽略大小写，只比较"唱词骨架"。 */
internal fun normalizedLyricText(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/** 译文/读音覆盖率（0..1），用于判断是否需要再补、以及挑选候选。 */
fun annotationCoverage(document: LyricsDocument): Double {
    val singable = document.lines.filter { normalizedLyricText(it.text).isNotEmpty() }
    if (singable.isEmpty()) return 0.0
    return singable.count { !it.translation.isNullOrBlank() } .toDouble() / singable.size
}
