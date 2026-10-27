package com.muses.player.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muses.player.core.download.DownloadQueueStore
import com.muses.player.core.model.*
import com.muses.player.core.model.download.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** 跨集合隔离、旧数据迁移及事务并发的回归测试，使用真实文件 DataStore。 */
class SongCollectionsTest {
    private val song = Song("online:one", "online", "online-reference", "测试歌曲", "测试歌手", sourceType = SourceType.ONLINE)

    private class Library : SongRepository {
        var batches = 0
        val songs = MutableStateFlow(emptyList<Song>())
        override fun observeSongs() = songs
        override suspend fun getSong(id: String) = songs.value.firstOrNull { it.id == id }
        override suspend fun upsert(song: Song) { songs.value = songs.value.filterNot { it.id == song.id } + song }
        override suspend fun upsertAll(songs: List<Song>) {
            batches++
            this.songs.value = this.songs.value.filterNot { old -> songs.any { it.id == old.id } } + songs
        }
        override suspend fun replaceSourceSongs(sourceId: String, songs: List<Song>): ScanMergeResult = error("本测试不扫描文件")
        override suspend fun deleteSourceSongs(sourceId: String) { songs.value = songs.value.filterNot { it.sourceId == sourceId } }
        override suspend fun rebuildDerivedIndexes() = Unit
    }

    private fun verify(block: suspend (UserPlaylistStore, FavoriteSongsStore, DownloadQueueStore, Library, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("muses-collections-test").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope) { root.resolve("collections.preferences_pb") }
        val library = Library()
        try { block(UserPlaylistStore(data), FavoriteSongsStore(data, library, scope), DownloadQueueStore(data), library, data) }
        finally { job.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `导入歌单完整保存顺序且失败不留下半成品`() = verify { lists, _, _, _, data ->
        val another = song.copy(id = "online:two", title = "另一首")
        val id = lists.createImported("导入歌单", listOf(song, another, song))
        assertEquals(listOf(song.id, another.id, song.id), UserPlaylistStore(data).snapshot().single().tracks.map { it.id })
        assertFailsWith<IllegalArgumentException> { lists.createImported("导入歌单", listOf(another)) }
        assertFailsWith<IllegalArgumentException> { lists.createImported("空歌单", emptyList()) }
        assertEquals(id, lists.snapshot().single().id)
    }

    @Test fun `从甲歌单移除不会影响乙歌单喜欢或下载`() = verify { lists, favorites, downloads, _, _ ->
        val a = lists.create("甲歌单")
        val b = lists.create("乙歌单")
        lists.addTo(setOf(a, b), song)
        favorites.setLiked(song, true)
        downloads.enqueue(DownloadTask("download-1", DownloadTrack.from(song), status = DownloadStatus.COMPLETED, savedLocation = "/music/test.mp3"))
        lists.removeFrom(a, song)
        assertFalse(lists.snapshot().first { it.id == a }.contains(song))
        assertTrue(lists.snapshot().first { it.id == b }.contains(song))
        assertTrue(favorites.snapshot().isLiked(song))
        assertEquals(1, downloads.downloadedSongs.first().size)
        favorites.setLiked(song, false)
        assertTrue(lists.snapshot().first { it.id == b }.contains(song))
        assertEquals(1, downloads.downloadedSongs.first().size)
        lists.delete(b)
        assertEquals(1, downloads.downloadedSongs.first().size)
    }

    @Test fun `下载与曲库不会自动喜欢或加入自建歌单`() = verify { lists, favorites, downloads, library, _ ->
        val id = lists.create("日常")
        library.upsert(song.copy(id = "local:one", path = "/music/test.mp3", sourceType = SourceType.LOCAL))
        downloads.enqueue(DownloadTask("download-1", DownloadTrack.from(song), status = DownloadStatus.COMPLETED, savedLocation = "/music/test.mp3"))
        assertFalse(favorites.snapshot().isLiked(song))
        assertTrue(lists.snapshot().single { it.id == id }.tracks.isEmpty())
        assertEquals("/music/test.mp3", downloads.downloadedSongs.first().single().toSong().path)
        assertEquals(SourceType.LOCAL, downloads.downloadedSongs.first().single().toSong().sourceType)
    }

    @Test fun `清理旧版已完成任务仍迁移和保留已下载列表`() = verify { _, _, downloads, _, data ->
        val legacy = DownloadTask("old", DownloadTrack.from(song), status = DownloadStatus.COMPLETED, savedLocation = "/old/file.flac")
        data.edit { it[stringPreferencesKey("download_queue_v1")] = Json.encodeToString(listOf(legacy)) }
        assertEquals(1, downloads.downloadedSongs.first().size)
        downloads.update { emptyList() }
        assertTrue(downloads.load().isEmpty())
        assertEquals("/old/file.flac", DownloadQueueStore(data).downloadedSongs.first().single().location)
    }

    @Test fun `只有保存成功的完成任务可以进入已下载`() = verify { _, _, downloads, _, _ ->
        for (status in DownloadStatus.entries) {
            downloads.enqueue(DownloadTask(status.name, DownloadTrack.from(song.copy(id = status.name, title = status.name)),
                status = status, savedLocation = "/music/${status.name}.mp3"))
        }
        downloads.enqueue(DownloadTask("empty-location", DownloadTrack.from(song), status = DownloadStatus.COMPLETED))
        assertEquals(listOf("COMPLETED"), downloads.downloadedSongs.first().map { it.taskId })
    }

    @Test fun `旧喜欢迁移保持显式选择且本地歌曲可以取消喜欢`() = verify { _, favorites, _, library, data ->
        data.edit { it[stringPreferencesKey("favorite_songs_v1")] = Json.encodeToString(listOf(FavoriteSelection(DownloadTrack.from(song), true))) }
        val local = song.copy(id = "local:one", path = "/music/test.mp3", sourceType = SourceType.LOCAL)
        library.upsert(local)
        assertEquals(local.path, favorites.snapshot().likedSongs.single().path)
        favorites.setLiked(local, false)
        assertFalse(favorites.snapshot().isLiked(song))
        assertTrue(favorites.snapshot().likedSongs.isEmpty())
        assertEquals(local, library.getSong(local.id))
    }

    @Test fun `重复点击和并发添加不重复不丢歌且重建仓库后仍存在`() = verify { lists, _, _, _, data ->
        val id = lists.create("通勤")
        val other = song.copy(id = "online:two", title = "另一首歌")
        coroutineScope {
            listOf(song, song, other).map { track -> async { lists.addTo(setOf(id), track) } }.awaitAll()
        }
        assertEquals(2, UserPlaylistStore(data).snapshot().single().tracks.size)
        assertFailsWith<IllegalArgumentException> { lists.addTo(setOf(id, "system:downloaded"), song) }
        assertEquals(2, lists.snapshot().single().tracks.size)
        assertFailsWith<IllegalArgumentException> { lists.create("已下载") }
        assertFailsWith<IllegalArgumentException> { lists.create(" 通勤 ") }
        assertEquals(1, lists.snapshot().size)
    }

    @Test fun `本地与云端歌单快照持久化后保留原始播放来源`() = verify { lists, _, _, _, data ->
        val local = song.copy(id = "local", path = "/music/a.mp3", sourceType = SourceType.LOCAL)
        val cloud = song.copy(id = "cloud", title = "云端歌曲", path = "https://dav.example/a.flac", sourceType = SourceType.WEBDAV)
        val id = lists.create("混合", local)
        lists.addTo(setOf(id), cloud)
        val restored = UserPlaylistStore(data).snapshot().single().tracks.map { it.toSong() }
        assertEquals(listOf(SourceType.LOCAL, SourceType.WEBDAV), restored.map { it.sourceType })
        assertEquals(listOf(local.path, cloud.path), restored.map { it.path })
    }

    @Test fun `搜索记录持久化去重置顶且可单删清空`() = verify { _, _, _, _, data ->
        val history = SearchHistoryStore(data)
        history.record(" 周杰伦 "); history.record("晴天"); history.record("周杰伦")
        assertEquals(listOf("周杰伦", "晴天"), SearchHistoryStore(data).entries.first())
        history.remove("周杰伦")
        assertEquals(listOf("晴天"), history.entries.first())
        history.clear()
        assertTrue(SearchHistoryStore(data).entries.first().isEmpty())
    }

    @Test fun `历史输入草稿替换但其他记录保留且最多三十条`() = verify { _, _, _, _, data ->
        val history = SearchHistoryStore(data)
        history.record("旧搜索"); history.record("周"); history.record("周杰伦", replaceDraft = "周")
        assertEquals(listOf("周杰伦", "旧搜索"), history.entries.first())
        repeat(35) { history.record("关键词$it") }
        assertEquals(30, history.entries.first().size)
        assertEquals("关键词34", history.entries.first().first())
        history.record("   ")
        assertEquals(30, history.entries.first().size)
    }

    @Test fun `不同音质文件都保留且重建仓库清理任务不丢下载`() = verify { _, _, downloads, _, data ->
        downloads.enqueue(DownloadTask("mp3", DownloadTrack.from(song), status = DownloadStatus.COMPLETED,
            savedLocation = "/a.mp3", savedSongId = "stable-a"))
        // 清理后允许同曲重下到另一个文件；两个产物都可访问。
        downloads.update { emptyList() }
        downloads.enqueue(DownloadTask("flac", DownloadTrack.from(song), status = DownloadStatus.COMPLETED,
            savedLocation = "/a.flac", savedSongId = "stable-b"))
        assertEquals(setOf("stable-a", "stable-b"), DownloadQueueStore(data).downloadedSongs.first().map { it.toSong().id }.toSet())
        downloads.update { emptyList() }
        assertEquals(2, downloads.downloadedSongs.first().size)
    }

    @Test fun `同位置重下保持旧下载ID和歌单引用`() = verify { lists, _, downloads, _, _ ->
        downloads.enqueue(DownloadTask("old", DownloadTrack.from(song), status = DownloadStatus.COMPLETED, savedLocation = "/a.mp3"))
        val original = downloads.downloadedSongs.first().single().toSong()
        val id = lists.create("旧下载", original)
        downloads.update { emptyList() }
        downloads.enqueue(DownloadTask("new", DownloadTrack.from(song), status = DownloadStatus.COMPLETED,
            savedLocation = "/a.mp3", savedSongId = "new-file-id"))
        assertEquals(original.id, downloads.downloadedSongs.first().single().toSong().id)
        assertEquals(original.id, lists.snapshot().single { it.id == id }.tracks.single().id)
    }

    @Test fun `批量准备下载只补缺失记录并保留富化标签`() = verify { _, _, _, library, _ ->
        val a = song.copy(id = "downloaded:a", path = "/a.mp3", sourceType = SourceType.LOCAL)
        val b = a.copy(id = "downloaded:b", path = "/b.flac")
        library.prepareDownloads(listOf(a, b))
        assertEquals(1, library.batches)
        library.upsert(a.copy(title = "已编辑标题", lyrics = "歌词", coverUri = "file:/cover.jpg"))
        library.prepareDownloads(listOf(a, b))
        assertEquals(1, library.batches)
        assertEquals("已编辑标题", library.getSong(a.id)?.title)
        assertEquals("歌词", library.getSong(a.id)?.lyrics)
        library.prepareDownloads(listOf(a.copy(path = "/moved.mp3")))
        assertEquals(2, library.batches)
        assertEquals("歌词", library.getSong(a.id)?.lyrics)
    }
}
