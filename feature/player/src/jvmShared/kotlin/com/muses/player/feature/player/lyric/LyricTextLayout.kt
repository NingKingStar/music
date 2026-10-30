package com.muses.player.feature.player.lyric

import androidx.compose.ui.text.style.LineBreak

/** 分句和时间保持来源原样；只为测量/绘制保护英文及韩文词内的软换行边界。 */
internal object LyricTextLayout {
    val lineBreak = LineBreak.Paragraph
    private val words = Regex("[\\p{IsLatin}\\p{IsHangul}0-9]+(?:['’][\\p{IsLatin}\\p{IsHangul}0-9]+)*")
    data class ProtectedText(val text: String, val offsets: IntArray) {
        fun layoutOffset(original: Int) = offsets[original.coerceIn(offsets.indices)]
        fun originalOffset(layout: Int): Int {
            val found = offsets.binarySearch(layout)
            return if (found >= 0) found else (-found - 1).coerceAtMost(offsets.lastIndex)
        }
    }
    fun protectWords(text: String): ProtectedText {
        val protected = BooleanArray(text.length)
        words.findAll(text).forEach { word ->
            for (index in word.range.first + 1..word.range.last) protected[index] = true
        }
        val offsets = IntArray(text.length + 1)
        val display = buildString {
            for (index in text.indices) {
                if (protected[index]) append('\u2060')
                offsets[index] = length
                append(text[index])
            }
            offsets[text.length] = length
        }
        return ProtectedText(display, offsets)
    }
}
