package com.muses.player.core.search

/** 相关性先于原唱；稳定排序不拿未知冒充原唱，也不改变平台分页顺序。 */
fun searchResultRank(song: OnlineSearchResult, keyword: String): Int {
    fun normalized(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    val query = normalized(keyword)
    if (query.isEmpty()) return 0
    val title = normalized(song.name)
    val artist = song.artist.orEmpty().split(Regex("[/、,，&;；]+"))
        .map(::normalized).any { it.isNotEmpty() && query.contains(it) }
    val relevance = when {
        title == query -> 4
        title.isNotEmpty() && query.contains(title) -> 3
        title.contains(query) -> 2
        artist -> 1
        else -> 0
    }
    val explicitVersion = listOf("live", "现场", "翻唱", "伴奏", "remix", "加速", "纯音乐").firstOrNull { it in query }
    val versionMatch = explicitVersion == null || explicitVersion in title
    return (if (song.lyricMatched) 500 else relevance * 100) + (if (artist) 30 else 0) + (if (versionMatch) 10 else 0) +
        (if (versionMatch && song.performanceLabel != null) 5 else 0)
}
