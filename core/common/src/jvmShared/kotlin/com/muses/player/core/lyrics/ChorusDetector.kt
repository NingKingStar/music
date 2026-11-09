package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricLine

/** 归一化：去掉空白、标点并忽略大小写，只比较"唱词骨架"。 */
internal fun normalizeChorusLine(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/**
 * 从带时间轴的歌词里推断副歌起点（毫秒）。
 *
 * 思路：副歌在流行歌里最典型的表现是"一小段连续多行反复出现"。这里枚举长度 1..3 的连续块，
 * 统计它后面完整重复的次数，按「总覆盖行数」打分（越长、重复越多分越高），取最高分的**最早**
 * 起点——也就是第一次唱副歌的位置，符合"跳到副歌"的期待。
 *
 * 约束：至少重复 1 次（即出现 2 遍）、总行数 ≥ 3，避免随口重复一句就被当成副歌；
 * 可用行数少于 6 时直接放弃，短样本的推断没有意义。
 */
fun detectChorusStartMs(lines: List<LyricLine>): Long? {
    val usable = lines.asSequence()
        .filter { it.timeMs > 0 }
        .map { it.timeMs to normalizeChorusLine(it.text) }
        .filter { it.second.isNotEmpty() }
        .toList()
    if (usable.size < 6) return null
    var bestTime: Long? = null
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
                // 覆盖行数优先，同等覆盖时偏向更长的块（更可能是完整副歌而非单句 hook）
                val score = covered * 10 + length
                if (score > bestScore) {
                    bestScore = score
                    bestTime = usable[start].first
                }
            }
            start++
        }
    }
    return bestTime
}
