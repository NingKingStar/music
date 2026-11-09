package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.model.lyrics.ChorusRange

/**
 * 没有平台原生副歌区间时的文本兜底。
 *
 * 这就是 QQ 音乐在没有服务端标记时的降级做法（按「重复行/重复段落」找副歌），
 * 不是自创：网易有 `/song/chorus` 原生区间，QQ 客户端则靠歌词文本里出现次数最多的段落。
 *
 * 规则：枚举长度 1..3 的连续行块，统计完整重复次数，按覆盖行数打分取最高分的最早一段；
 * 至少重复一次、覆盖 ≥3 行、可用行 ≥6；最多返回两段（第一段和它后面最近的一次重复）。
 */
fun detectChorusRanges(lines: List<LyricLine>): List<ChorusRange> {
    val usable = lines.asSequence()
        .filter { it.timeMs > 0 && it.text.isNotBlank() }
        .map { it.timeMs to it.text.lowercase().filter { ch -> ch.isLetterOrDigit() } }
        .filter { it.second.isNotEmpty() }
        .toList()
    if (usable.size < 6) return emptyList()

    var bestStart = -1
    var bestLength = 0
    var bestScore = 0
    for (length in 1..3) {
        var start = 0
        while (start + length <= usable.size) {
            val block = (0 until length).map { usable[start + it].second }
            var repeats = 0
            var cursor = start + length
            while (cursor + length <= usable.size) {
                if ((0 until length).all { usable[cursor + it].second == block[it] }) {
                    repeats++
                    cursor += length
                } else {
                    cursor++
                }
            }
            val covered = length * (repeats + 1)
            if (repeats >= 1 && covered >= 3) {
                val score = covered * 10 + length
                if (score > bestScore) {
                    bestScore = score
                    bestStart = start
                    bestLength = length
                }
            }
            start++
        }
    }
    if (bestStart < 0) return emptyList()

    // 区间末端取该段最后一行的下一行起点（没有下一行时给 5 秒余量），够点进度条即可。
    val endIndex = (bestStart + bestLength - 1).coerceAtMost(usable.lastIndex)
    val endMs = usable.getOrNull(endIndex + 1)?.first ?: (usable[endIndex].first + 5_000L)
    val first = ChorusRange(usable[bestStart].first, endMs.coerceAtLeast(usable[endIndex].first + 1_000L))
    return listOf(first)
}
