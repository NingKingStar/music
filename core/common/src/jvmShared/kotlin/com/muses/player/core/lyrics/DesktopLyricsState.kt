package com.muses.player.core.lyrics

import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.LyricTimingKind
import com.muses.player.core.model.lyrics.DesktopLyricsWord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 双端悬浮歌词复用播放页已解析的文档，不写库、不补充本地歌词。 */
data class DesktopLyricsSnapshot(
    val songId: String? = null,
    val title: String = "",
    val artist: String? = null,
    val document: LyricsDocument? = null,
)

object DesktopLyricsState {
    private val mutable = MutableStateFlow(DesktopLyricsSnapshot())
    val snapshot = mutable.asStateFlow()
    fun publish(value: DesktopLyricsSnapshot) { mutable.value = value }
}

/** 页面加载中的空文档不能遮住服务歌词，普通行歌词也不能覆盖真实逐字文档。 */
fun desktopLyricsSnapshot(songId: String, shared: DesktopLyricsSnapshot, fallback: DesktopLyricsSnapshot): DesktopLyricsSnapshot {
    val page = shared.takeIf { it.songId == songId && it.document?.lines?.isNotEmpty() == true }
    val service = fallback.takeIf { it.songId == songId }
    return if (service?.document?.hasPreciseDesktopLyrics() == true && page?.document?.hasPreciseDesktopLyrics() != true) service
    else page ?: service ?: DesktopLyricsSnapshot(songId)
}

fun LyricsDocument.hasPreciseDesktopLyrics(): Boolean = lines.any {
    it.timingKind == LyricTimingKind.Precise && it.syllables.isNotEmpty()
}

data class DesktopLyricsText(
    val primary: String,
    val secondary: String?,
    val words: List<DesktopLyricsWord> = emptyList(),
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
)

fun desktopLyricsText(snapshot: DesktopLyricsSnapshot, positionMs: Long, showTranslation: Boolean, isPlaying: Boolean = false, showRomanization: Boolean = false): DesktopLyricsText {
    val line = snapshot.document?.let { document ->
        document.highlightedIndex(positionMs)?.let { document.lines.getOrNull(it) }
    }
    val primary = line?.text?.takeIf { it.isNotBlank() } ?: snapshot.title.ifBlank { "Muses" }
    val secondary = if (line?.text?.isNotBlank() == true) {
        when {
            showTranslation -> line.translation?.takeIf { it.isNotBlank() }
            showRomanization -> line.romanization?.takeIf { it.isNotBlank() }
            else -> null
        }
    } else snapshot.artist?.takeIf { it.isNotBlank() }
    val words = if (line?.timingKind == LyricTimingKind.Precise && line.text.isNotBlank()) {
        val untrimmed = line.syllables.joinToString("") { it.text }
        val exact = untrimmed == primary
        val leading = if (exact) 0 else untrimmed.length - untrimmed.trimStart().length
        var cursor = -leading
        buildList {
            if (!exact && untrimmed.trim() != primary) return@buildList
            for (syllable in line.syllables) {
                if (syllable.text.isEmpty()) continue
                val start = cursor.coerceIn(0, primary.length)
                cursor += syllable.text.length
                val end = cursor.coerceIn(start, primary.length)
                if (end > start && syllable.endTimeMs >= syllable.startTimeMs) {
                    add(DesktopLyricsWord(start, end, syllable.startTimeMs, syllable.endTimeMs))
                }
            }
        }
    } else emptyList()
    return DesktopLyricsText(primary, secondary, words, if (words.isEmpty()) 0 else positionMs,
        isPlaying)
}
