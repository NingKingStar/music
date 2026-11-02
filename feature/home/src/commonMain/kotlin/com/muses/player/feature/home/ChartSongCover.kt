package com.muses.player.feature.home

import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineSearchResult
import kotlinx.coroutines.CancellationException

/** 首曲缺图时保留占位，不拿榜单海报或其他歌曲冒充首曲封面。 */
internal suspend fun loadChartSongCover(
    chart: OnlineChart,
    cache: OnlineChartCacheStore,
    fetchSongs: suspend () -> List<OnlineSearchResult>,
): String? {
    val songs = cache.loadSongs(chart.platform, chart.chartId)
    if (songs != null && cache.isSongsFresh(songs.updatedAt, chart.updateInfo)) {
        return songs.songs.firstOrNull()?.coverUrl?.takeIf { it.isNotBlank() }
    }
    cache.loadCover(chart.platform, chart.chartId)?.let { return it }
    val url = fetchSongs().firstOrNull()?.coverUrl?.takeIf { it.isNotBlank() }
    if (url != null) try {
        cache.saveCover(chart.platform, chart.chartId, url)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { /* 缓存写入失败不影响本次显示。 */ }
    return url
}
