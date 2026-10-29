package com.muses.player.navigation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.search.*
import com.muses.player.core.search.http.SearchHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMetadataManagerTest {
    private class Memory : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    @Test fun `导入自动推进且重启从持久游标接续并保护歌单成员`() = runTest {
        val store = UserPlaylistStore(Memory())
        val tracks = (1..12).map { id -> OnlineSearchResult("wy", "$id", "歌$id", "歌手", "专辑", 100000, "https://test/cover", """{"id":"$id"}""").toSong("online") }
        val id = store.createImported("自动补全", tracks)
        val client = HttpClient(MockEngine { error("完整条目不应重复请求") })
        val repair = PlaylistMetadataRepair(SearchHttp(client), OnlineSearchService(emptyList()))
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        PlaylistMetadataManager(store, repair, firstScope).start()
        runCurrent()
        assertEquals(10, store.snapshot().single().metadataRepairCursor)
        firstScope.cancel()
        PlaylistMetadataManager(store, repair, backgroundScope).start()
        runCurrent(); advanceTimeBy(500); runCurrent()
        val restored = store.snapshot().single()
        assertEquals(1, restored.metadataRepairVersion)
        assertEquals(tracks.map { it.id }, restored.tracks.map { it.id })
        assertEquals(id, restored.id)
        client.close()
    }
}
