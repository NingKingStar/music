package com.muses.player.feature.shell.platform

internal enum class ReleaseNoteKind { TITLE, BULLET, PARAGRAPH }
internal data class ReleaseNoteBlock(val kind: ReleaseNoteKind, val text: String)

/** 发布说明只展示文本与层级，不在更新页执行 HTML 或外部链接。 */
internal fun releaseNoteBlocks(notes: String): List<ReleaseNoteBlock> {
    val result = mutableListOf<ReleaseNoteBlock>()
    val paragraph = mutableListOf<String>()
    fun clean(text: String) = text.replace(Regex("""!?\[([^]]+)\]\([^)]+\)"""), "$1")
        .replace("**", "").replace("__", "").replace(96.toChar().toString(), "").replace(Regex("<[^>]+>"), "").trim()
    fun flush() { if (paragraph.isNotEmpty()) { result += ReleaseNoteBlock(ReleaseNoteKind.PARAGRAPH, paragraph.joinToString(" ")); paragraph.clear() } }
    for (line in notes.take(32_000).lines()) {
        val text = line.trim()
        when {
            text.isBlank() || text == "---" || text.startsWith(96.toChar().toString().repeat(3)) -> flush()
            text.startsWith("#") -> { flush(); clean(text.trimStart('#', ' ')).takeIf(String::isNotBlank)?.let { result += ReleaseNoteBlock(ReleaseNoteKind.TITLE, it) } }
            text.matches(Regex("^(?:[-*+] |[0-9]+[.)] ).+")) -> {
                flush(); result += ReleaseNoteBlock(ReleaseNoteKind.BULLET, clean(text.replaceFirst(Regex("^(?:[-*+] |[0-9]+[.)] )"), "")))
            }
            else -> clean(text).takeIf(String::isNotBlank)?.let { paragraph += it }
        }
    }
    flush()
    return result
}
