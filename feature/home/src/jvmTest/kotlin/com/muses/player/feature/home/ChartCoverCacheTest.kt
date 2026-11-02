package com.muses.player.feature.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class ChartCoverCacheTest {
    @Test fun `封面缓存按平台榜单隔离并拒绝过期和未来时间`() = runBlocking {
        val root = Files.createTempDirectory("chart-covers").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "covers.preferences_pb") })
            val cache = OnlineChartCacheStore(store)
            cache.saveCover("wy", "1", "https://test/a.jpg")
            assertEquals("https://test/a.jpg", OnlineChartCacheStore(store).loadCover("wy", "1"))
            assertNull(cache.loadCover("tx", "1"))
            store.edit { it[stringPreferencesKey("online_chart_cover_cache")] = """{"wy:1":{"url":"https://test/old.jpg","at":1},"wy:2":{"url":"https://test/future.jpg","at":9223372036854775807}}""" }
            assertNull(cache.loadCover("wy", "1"))
            assertNull(cache.loadCover("wy", "2"))
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }
}
