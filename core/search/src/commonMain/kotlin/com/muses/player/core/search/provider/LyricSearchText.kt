package com.muses.player.core.search.provider

/** 去掉搜索高亮和标点，只对接口确实返回的歌词片段判定命中。 */
fun lyricTextMatches(text: String, keyword: String): Boolean {
    fun normalize(s: String) = s.replace(Regex("<[^>]*>"), "").lowercase().filter { it.isLetterOrDigit() }
    val query = normalize(keyword)
    return query.length >= 4 && query in normalize(text)
}
