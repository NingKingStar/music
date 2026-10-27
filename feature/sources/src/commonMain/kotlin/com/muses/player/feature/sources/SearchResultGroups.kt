package com.muses.player.feature.sources

import com.muses.player.core.search.OnlineSearchResult
import kotlin.math.abs

/** 只合并有足够证据的跨平台结果；信息不足时保留独立行。 */
internal fun groupSearchResults(platforms: List<PlatformSearchState>): List<List<OnlineSearchResult>> {
    val groups = mutableListOf<MutableList<OnlineSearchResult>>()
    platforms.flatMap { it.results }.forEach { result ->
        val group = groups.firstOrNull { candidates ->
            candidates.none { it.platform == result.platform } && candidates.all { sameRecording(it, result) }
        }
        if (group == null) groups.add(mutableListOf(result)) else group.add(result)
    }
    return groups
}

private fun sameRecording(a: OnlineSearchResult, b: OnlineSearchResult): Boolean {
    fun normalized(s: String) = s.trim().lowercase().replace('（', '(').replace('）', ')')
        .replace(Regex("\\s+"), " ")
    val artistA = a.artist?.let(::normalized).orEmpty()
    val artistB = b.artist?.let(::normalized).orEmpty()
    val durationA = a.durationMs ?: return false
    val durationB = b.durationMs ?: return false
    if (durationA <= 0 || durationB <= 0 || artistA.isEmpty() || artistA != artistB) return false
    if (normalized(a.name) != normalized(b.name) || abs(durationA - durationB) > 3000) return false
    val albumA = a.album?.let(::normalized).orEmpty()
    val albumB = b.album?.let(::normalized).orEmpty()
    return albumA.isEmpty() || albumB.isEmpty() || albumA == albumB
}
