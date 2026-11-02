package com.muses.player.feature.home

import androidx.datastore.preferences.core.*
import com.muses.player.core.search.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class ArtistPageCacheTest {
    @Test fun `分页缓存持久化隔离有界且损坏不影响新写入`() = runBlocking {
        val root=Files.createTempDirectory("artist-pages").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(root,"cache.preferences_pb")})
            val page=ArtistSongPage(listOf(OnlineSearchResult("wy","1","歌","歌手",null,null,null,"{}")),1,true)
            val cache=ArtistPageCache(store)
            cache.save("wy:6452:1",page)
            assertEquals(page,ArtistPageCache(store).load("wy:6452:1")?.value)
            assertNull(cache.load("tx:6452:1"));assertNull(cache.load("wy:6452:2"))
            for(i in 1..30){cache.save("wy:$i:1",page);delay(2)}
            assertNull(cache.load("wy:6452:1"));assertNotNull(cache.load("wy:30:1"))
            store.edit { it[stringPreferencesKey("artist_pages_v1")]="bad-json" }
            assertNull(cache.load("wy:30:1"));cache.save("wy:2:1",page);assertEquals(page,cache.load("wy:2:1")?.value)
        } finally {scope.cancel();scope.coroutineContext[Job]?.join();root.deleteRecursively()}
    }
}
