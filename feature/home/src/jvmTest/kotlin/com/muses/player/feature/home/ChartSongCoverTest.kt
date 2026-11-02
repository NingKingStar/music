package com.muses.player.feature.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineSearchResult
import kotlinx.coroutines.*
import java.nio.file.Files
import java.io.File
import kotlin.test.*
import kotlin.time.Clock

class ChartSongCoverTest {
    private val chart = OnlineChart("wy", "1", "热歌榜", "https://test/poster.jpg")
    private fun song(id: String, cover: String?) = OnlineSearchResult(
        platform = "wy", songId = id, name = id, artist = null, album = null,
        durationMs = null, coverUrl = cover, musicInfoJson = "{}")

    private fun withCache(block: suspend (OnlineChartCacheStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("chart-song-cover").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(OnlineChartCacheStore(PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(root, "cache.preferences_pb") })))
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun `有海报的榜单仍取首曲并持久缓存免重复请求`() = withCache { cache ->
        var requests = 0
        val fetch: suspend () -> List<OnlineSearchResult> = { requests++; listOf(song("first", "https://test/first.jpg")) }
        assertEquals("https://test/first.jpg", loadChartSongCover(chart, cache, fetch))
        assertEquals("https://test/first.jpg", loadChartSongCover(chart, cache, fetch))
        assertEquals(1, requests)
        assertNull(cache.loadCover("tx", "1"))
    }

    @Test fun `已有新鲜整榜缓存时不再为封面请求网络`() = withCache { cache ->
        cache.saveCover("wy", "1", "https://test/previous.jpg")
        cache.saveSongs("wy", "1", CachedChartSongs(listOf(song("first", "https://test/current.jpg")),
            1, false, Clock.System.now().toEpochMilliseconds()))
        assertEquals("https://test/current.jpg", loadChartSongCover(chart, cache) { error("不应重复请求") })
    }

    @Test fun `首曲缺图不用海报或后续歌曲替代`() = withCache { cache ->
        assertNull(loadChartSongCover(chart, cache) { listOf(song("first", null), song("second", "https://test/second.jpg")) })
        cache.saveSongs("wy", "1", CachedChartSongs(listOf(song("first", null)),
            1, false, Clock.System.now().toEpochMilliseconds()))
        assertNull(loadChartSongCover(chart, cache) { error("缺图的首曲已缓存") })
    }

    @Test fun `过期歌曲缓存不能阻止读取新的首曲`() = withCache { cache ->
        cache.saveSongs("wy", "1", CachedChartSongs(listOf(song("old", "https://test/old.jpg")), 1, false, 1))
        assertEquals("https://test/new.jpg", loadChartSongCover(chart, cache) { listOf(song("new", "https://test/new.jpg")) })
    }
}
