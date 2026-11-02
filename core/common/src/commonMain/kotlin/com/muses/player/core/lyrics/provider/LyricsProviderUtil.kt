package com.muses.player.core.lyrics.provider

import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * provider 搜索辅助（规格书 = src/features/lyrics/providers/util.ts，逐函数翻译）。
 */

/** 关键词：title/artist/album 过滤空后空格连接（util.ts buildKeyword） */
fun buildKeyword(query: OnlineLyricsQuery): String =
    listOfNotNull(
        query.title.takeIf { it.isNotBlank() },
        query.artist?.takeIf { it.isNotBlank() },
        query.album?.takeIf { it.isNotBlank() },
    ).joinToString(" ").trim()

/** 候选条目公共字段（scoreSearchHit 入参形态） */
interface ScoreableHit {
    val title: String?
    val artist: String?
    val album: String?
    val durationSec: Double? get() = null
}

/** 仅使用同平台引用里的原歌曲 ID，不能拿别的平台 ID 请求歌词。 */
fun OnlineLyricsQuery.platformId(platform: String, vararg fields: String): String? {
    val ref = trackRef?.takeIf { it.platform == platform } ?: return null
    val json = runCatching { Json.parseToJsonElement(ref.musicInfoJson) as? JsonObject }.getOrNull() ?: return null
    return fields.firstNotNullOfOrNull { field ->
        (json[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    }
}

private fun normalized(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun matchesSong(hit: ScoreableHit, query: OnlineLyricsQuery): Boolean {
    val title = normalized(hit.title.orEmpty())
    if (title.isEmpty() || title != normalized(query.title)) return false
    val expectedDuration = query.durationSec?.takeIf { it > 0 }
    val actualDuration = hit.durationSec?.takeIf { it > 0 }
    if (expectedDuration != null && actualDuration != null &&
        kotlin.math.abs(expectedDuration - actualDuration) > 10) return false
    val artist = query.artist?.takeIf { it.isNotBlank() } ?: return true
    fun names(value: String) = value.split(Regex("[/、,，&;；]+"))
        .map(::normalized).filter { it.isNotEmpty() }.toSet()
    return names(hit.artist.orEmpty()) == names(artist)
}

private fun scoreSearchHit(hit: ScoreableHit, query: OnlineLyricsQuery): Int {
    val t = hit.title?.trim()
    val a = hit.artist?.trim()
    val al = hit.album?.trim()
    val qTitle = query.title.trim()
    val qArtist = query.artist?.trim().orEmpty()
    val qAlbum = query.album?.trim().orEmpty()

    var score = 0
    if (t != null && qTitle.isNotEmpty() && (t == qTitle || t.contains(qTitle) || qTitle.contains(t))) score += 10
    if (a != null && qArtist.isNotEmpty() && (a == qArtist || a.contains(qArtist) || qArtist.contains(a))) score += 6
    if (al != null && qAlbum.isNotEmpty() && (al == qAlbum || al.contains(qAlbum) || qAlbum.contains(al))) score += 3
    return score
}

/** 打分取最优候选（util.ts pickBest；stable 排序对齐 JS sort 语义） */
fun <T : ScoreableHit> pickBest(items: List<T>, query: OnlineLyricsQuery): T? {
    if (items.isEmpty()) return null
    return items.filter { matchesSong(it, query) }.maxByOrNull { scoreSearchHit(it, query) }
}

/** 只扩大同曲候选数量，不降低歌手、版本与时长的匹配约束。 */
fun <T : ScoreableHit> pickCandidates(items: List<T>, query: OnlineLyricsQuery): List<T> =
    items.filter { matchesSong(it, query) }.sortedByDescending { scoreSearchHit(it, query) }.take(3)

/** lrclist → 标准 LRC（util.ts linesToLrc） */
fun linesToLrc(lines: List<LrcLine>): String {
    val out = mutableListOf<String>()
    for (line in lines) {
        val text = line.text?.trim()
        if (text.isNullOrEmpty()) continue
        val rawTime = line.time ?: continue
        val seconds = when (rawTime) {
            is Double -> rawTime
            is Long -> rawTime.toDouble()
            else -> rawTime.toString().toDoubleOrNull() ?: continue
        }
        if (!seconds.isFinite()) continue
        val totalMs = (seconds * 1000).let { if (it < 0) 0.0 else it }.toInt()
        val m = totalMs / 60000
        val s = (totalMs % 60000) / 1000
        val ms = totalMs % 1000
        out.add("[%02d:%02d.%03d]%s".format(m, s, ms, text))
    }
    return out.joinToString("\n")
}

data class LrcLine(val time: Any?, val text: String?)
