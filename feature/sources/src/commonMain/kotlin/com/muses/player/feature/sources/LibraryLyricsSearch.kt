package com.muses.player.feature.sources

import com.muses.player.core.model.Song

enum class SearchCategory { ALL, ALBUMS, ARTISTS }

data class LibraryLyricMatch(
    val song: Song,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

data class LibraryLyricsResults(
    val keyword: String = "",
    val loading: Boolean = false,
    val matches: List<LibraryLyricMatch> = emptyList(),
    val songsWithLyrics: Int = 0,
)

private val spaces = Regex("\\s+")
private val squareMarkers = Regex("\\[(?:\\d+:\\d+(?:[.:]\\d+)?|\\d+,\\d+)]")
private val metadataLine = Regex("\\[[a-zA-Z]+:.*]")
private val wordMarkers = Regex("<\\d+(?::\\d+)*(?:[.,]\\d+)?>|\\(\\d+,\\d+(?:,\\d+)?\\)")
private val xmlTags = Regex("<[^>]+>")
private val xmlBody = Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
private val xmlBreaks = Regex("</p\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
private val xmlIndentation = Regex(">[ \\t]*[\\r\\n]+\\s*<")
private val numericEntity = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

/** 仅检索歌词正文：先移除时间标记，逐字歌词也能用完整一句话匹配。 */
internal fun matchLibraryLyrics(song: Song, keyword: String): LibraryLyricMatch? {
    val raw = song.lyrics?.takeIf { it.isNotBlank() } ?: return null
    val query = keyword.trim().replace(spaces, " ")
    if (query.isEmpty()) return null
    val isXml = raw.contains("<tt", ignoreCase = true) || raw.contains("<body", ignoreCase = true)
    val text = if (isXml) {
        val body = xmlBody.find(raw)?.groupValues?.get(1) ?: raw
        decodeEntities(body.replace(xmlIndentation, "><").replace(xmlBreaks, "\n").replace(xmlTags, ""))
    } else {
        raw.lineSequence()
            .filterNot { it.trim().matches(metadataLine) }
            .joinToString("\n") { it.replace(squareMarkers, "").replace(wordMarkers, "") }
    }
    for (line in text.lineSequence()) {
        val clean = line.trim().replace(spaces, " ")
        val index = clean.indexOf(query, ignoreCase = true)
        if (index < 0) continue
        // 长段落围绕命中位置截取，保证关键词在预览内可见。
        val from = (index - 30).coerceAtLeast(0)
        val to = (index + query.length + 50).coerceAtMost(clean.length)
        val prefix = if (from > 0) "…" else ""
        val snippet = prefix + clean.substring(from, to) + if (to < clean.length) "…" else ""
        val start = prefix.length + index - from
        return LibraryLyricMatch(song, snippet, start, start + query.length)
    }
    return null
}

private fun decodeEntities(text: String): String = numericEntity.replace(text) { match ->
    val value = match.groupValues[1]
    val code = if (value.startsWith("x")) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
    when {
        code == null || code !in 0..0x10FFFF || code in 0xD800..0xDFFF -> match.value
        code <= 0xFFFF -> code.toChar().toString()
        else -> {
            val offset = code - 0x10000
            "${(0xD800 + (offset shr 10)).toChar()}${(0xDC00 + (offset and 0x3FF)).toChar()}"
        }
    }
}.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&amp;", "&")
