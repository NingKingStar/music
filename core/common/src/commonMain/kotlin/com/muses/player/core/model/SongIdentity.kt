package com.muses.player.core.model

internal expect fun normalizeSongIdentity(value: String): String
internal expect fun normalizeArtistIdentityText(value: String): String

private val artistSeparator = Regex("[/、,，&;；|＋+]+|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
private val bilingualArtist = Regex("^([^()]+)\\(([^()]+)\\)$")

/** 只把明确的中英双语括注作为别名，保留乐队名括注与合作歌手数量。 */
private fun artistNames(value: String?): List<Set<String>> = normalizeArtistIdentityText(value.orEmpty())
    .split(artistSeparator).mapNotNull { raw ->
        val name = normalizeSongIdentity(raw)
        if (name.isEmpty()) return@mapNotNull null
        val bilingual = bilingualArtist.matchEntire(raw.trim())
        val parts = bilingual?.groupValues?.drop(1).orEmpty()
        fun chineseOnly(text: String) = text.any { it in '\u4e00'..'\u9fff' } &&
            text.none { it in 'a'..'z' || it in 'A'..'Z' }
        fun latinOnly(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            text.none { it in '\u4e00'..'\u9fff' }
        if (parts.size == 2 && ((chineseOnly(parts[0]) && latinOnly(parts[1])) ||
                (latinOnly(parts[0]) && chineseOnly(parts[1])))) {
            parts.map(::normalizeSongIdentity).filter { it.isNotEmpty() }.toSet()
        } else setOf(name)
    }.distinct()

private fun sameArtists(left: String?, right: String?): Boolean {
    val candidates = artistNames(right)
    val names = artistNames(left)
    if (names.isEmpty() || names.size != candidates.size) return false
    // 允许重新分配已匹配成员，别名有交集时也不受比较方向或排列顺序影响。
    val owners = IntArray(candidates.size) { -1 }
    fun assign(index: Int, visited: BooleanArray): Boolean {
        candidates.forEachIndexed { candidateIndex, aliases ->
            if (!visited[candidateIndex] && names[index].any { it in aliases }) {
                visited[candidateIndex] = true
                if (owners[candidateIndex] == -1 || assign(owners[candidateIndex], visited)) {
                    owners[candidateIndex] = index
                    return true
                }
            }
        }
        return false
    }
    return names.indices.all { assign(it, BooleanArray(candidates.size)) }
}

/** 歌名保留版本文字；跨平台匹配必须同时具备相同歌手，空歌手只按歌曲 ID 判断。 */
fun sameSong(left: Song, right: Song): Boolean {
    if (left.id == right.id) return true
    val name = normalizeSongIdentity(left.title)
    return name.isNotEmpty() && name == normalizeSongIdentity(right.title) && sameArtists(left.artist, right.artist)
}

/** 先按 ID/规范歌名缩小候选，再用原 sameSong 判定；保持原集合中首个匹配项。 */
class SongMatchIndex(songs: List<Song>) {
    private val byId = songs.withIndex().groupBy { it.value.id }
    private val byTitle = songs.withIndex().groupBy { normalizeSongIdentity(it.value.title) }
    fun find(song: Song): Song? = (byId[song.id].orEmpty() + byTitle[normalizeSongIdentity(song.title)].orEmpty())
        .asSequence().filter { sameSong(it.value, song) }.minByOrNull { it.index }?.value
}
