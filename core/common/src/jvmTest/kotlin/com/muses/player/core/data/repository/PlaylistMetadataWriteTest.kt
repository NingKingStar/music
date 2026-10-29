package com.muses.player.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.online.OnlineTrackRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlaylistMetadataWriteTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private fun song(id: String) = Song(id, "online", OnlineTrackRef("wy", """{"songmid":"$id"}""", "online").encode(), id,
        sourceType = SourceType.ONLINE)

    @Test fun `补全不会丢失并发追加成员也不会复活删除的歌曲`() = runTest {
        val store = UserPlaylistStore(MemoryStore())
        val first = song("1"); val second = song("2"); val later = song("3")
        val id = store.createImported("歌单", listOf(first, second))
        val expected = store.snapshot().single().tracks
        store.addTo(setOf(id), later)
        store.removeFrom(id, second)
        store.applyMetadata(id, expected, listOf(first.copy(artist = "歌手"), second.copy(artist = "歌手")))
        val result = store.snapshot().single()
        assertEquals(listOf("1", "3"), result.tracks.map { it.id })
        assertEquals("歌手", result.tracks.first().artist)
        assertTrue(result.followPreferredQuality)
    }

    @Test fun `检查期间被编辑的条目保留新内容`() = runTest {
        val store = UserPlaylistStore(MemoryStore())
        val song = song("1")
        val id = store.createImported("歌单", listOf(song))
        val expected = store.snapshot().single().tracks
        store.applyMetadata(id, expected, listOf(song.copy(artist = "用户修改")))
        store.applyMetadata(id, expected, listOf(song.copy(artist = "迟到的网络结果")))
        assertEquals("用户修改", store.snapshot().single().tracks.single().artist)
    }
}
