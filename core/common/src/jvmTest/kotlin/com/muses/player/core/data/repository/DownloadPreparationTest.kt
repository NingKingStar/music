package com.muses.player.core.data.repository

import com.muses.player.core.data.dao.*
import com.muses.player.core.data.db.SongEntity
import com.muses.player.core.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** 调用真实Room仓储，DAO替身计数，验证不会每首歌重建一次全库索引。 */
class DownloadPreparationTest {
    private class Harness {
        val records = linkedMapOf<String, SongEntity>()
        var writes = 0
        var rebuilds = 0
        var failRead = false
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
        @Suppress("UNCHECKED_CAST")
        private val songs = proxy<SongDao> { name, args -> when (name) {
            "getByIds" -> { check(!failRead) { "读取失败" }; (args[0] as List<String>).mapNotNull(records::get) }
            "getAll" -> records.values.toList()
            "insertAll" -> { writes++; (args[0] as List<SongEntity>).forEach { records[it.id] = it }; Unit }
            "upsert" -> { writes++; (args[0] as SongEntity).let { records[it.id] = it }; Unit }
            else -> Unit
        } }
        private val albums = proxy<AlbumDao> { name, _ -> if (name == "deleteAll") rebuilds++; Unit }
        private val artists = proxy<ArtistDao> { _, _ -> Unit }
        val repository = RoomSongRepository(songs, albums, artists)
    }

    @Test fun `三十首首次登记只重建一次再次播放零写入`() = runTest {
        val h = Harness()
        val queue = (1..30).map { Song("file:$it", "device-downloads", "/$it.mp3", "歌曲$it", "歌手") }
        h.repository.prepareDownloads(queue)
        assertEquals(1, h.writes)
        assertEquals(1, h.rebuilds)
        h.repository.prepareDownloads(queue)
        assertEquals(1, h.writes)
        assertEquals(1, h.rebuilds)
        assertEquals(30, h.records.size)
    }

    @Test fun `读库失败不能被当成缺失从而覆盖旧数据`() = runTest {
        val h = Harness(); h.failRead = true
        assertFailsWith<IllegalStateException> { h.repository.prepareDownloads(listOf(Song("id", "source", "/a", "歌曲"))) }
        assertEquals(0, h.writes)
        assertEquals(0, h.rebuilds)
    }
}
