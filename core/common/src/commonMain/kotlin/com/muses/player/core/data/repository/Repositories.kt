package com.muses.player.core.data.repository

import com.muses.player.core.data.dao.AlbumDao
import com.muses.player.core.data.dao.ArtistDao
import com.muses.player.core.data.dao.SourceDao
import com.muses.player.core.data.dao.SongDao
import com.muses.player.core.data.db.SongEntity
import com.muses.player.core.data.db.SongTags
import com.muses.player.core.data.mapper.toDomain
import com.muses.player.core.data.mapper.toEntity
import com.muses.player.core.model.Album
import com.muses.player.core.model.Artist
import com.muses.player.core.model.Song
import com.muses.player.core.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 扫描入库合并结果（[SongRepository.replaceSourceSongs] 返回值）。
 *
 * @param scanned 本次扫描发现的文件数
 * @param added 新入库的歌曲数
 * @param missing 本次未扫到、被标记为「丢失」的已有歌曲数
 * @param skipped 未落库（空结果/锐减保护触发），原曲库保持不变
 */
data class ScanMergeResult(
    val scanned: Int,
    val added: Int = 0,
    val missing: Int = 0,
    val skipped: Boolean = false,
)

/** 曲库仓库 */
interface SongRepository {
    fun observeSongs(): Flow<List<Song>>
    /** 搜索流：空串回全库，否则走数据库模糊匹配（分页前置，大库不全量进内存） */
    fun observeSongs(query: String): Flow<List<Song>> =
        observeSongs().map { songs ->
            if (query.isBlank()) songs
            else songs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    song.artist.orEmpty().contains(query, ignoreCase = true) ||
                    song.album.orEmpty().contains(query, ignoreCase = true)
            }
        }
    /**
     * 按 sourceId 合并本次扫描结果（**非整行覆盖**）：
     * - 已存在的歌只刷新发现层字段并复位「丢失」标记，已刮削/已懒扫描的封面·歌词·标签一律保留；
     * - 新歌直接入库；
     * - 本次未扫到的歌标为「丢失」（软删除，不硬删），文件重现时按同 id 自动复位。
     * 空结果/锐减保护触发时返回 [ScanMergeResult.skipped]，不动原曲库。
     */
    suspend fun replaceSourceSongs(sourceId: String, songs: List<Song>): ScanMergeResult
    /** 删除该音源下全部歌曲（删除音源时同步清理，对齐 Web reconcileSourceSongs(id, [])） */
    suspend fun deleteSourceSongs(sourceId: String)
    /** 从全库歌曲重建专辑/艺术家派生索引（存量库升级回填；写入路径已自动触发） */
    suspend fun rebuildDerivedIndexes()
    /** 按 id 取单曲（M3 刮削写回链路） */
    suspend fun getSong(id: String): Song?
    /** 批量取单曲（写回等多选链路一次查全量，分片防 IN 上限；默认逐条，Room 实现覆盖为单查询） */
    suspend fun getSongs(ids: List<String>): Map<String, Song> =
        ids.mapNotNull { id -> getSong(id)?.let { id to it } }.toMap()
    /** 单曲写入/更新（M3 刮削写回链路，对齐 Web upsertSong） */
    suspend fun upsert(song: Song)

    suspend fun markDownloadedFileMissing(id: String, sourceId: String) {}

    /** 同批写入只重建一次派生索引；默认实现兼容平台仓储。 */
    suspend fun upsertAll(songs: List<Song>) { songs.forEach { upsert(it) } }

    /** 播放前只登记缺失的文件引用，已存在的歌词、封面和刮削标签不被快照覆盖。 */
    suspend fun prepareDownloads(queue: List<Song>) {
        val candidates = queue.filter { it.sourceType != com.muses.player.core.model.SourceType.ONLINE }
            .distinctBy { it.id }
        val existing = getSongs(candidates.map { it.id })
        val changes = candidates.mapNotNull { song ->
            val old = existing[song.id]
            when {
                old == null -> song
                old.path != song.path -> old.copy(path = song.path, sourceId = song.sourceId, sourceType = song.sourceType)
                else -> null
            }
        }
        if (changes.isNotEmpty()) upsertAll(changes)
    }
}

class RoomSongRepository constructor(
    private val songDao: SongDao,
    private val albumDao: com.muses.player.core.data.dao.AlbumDao,
    private val artistDao: com.muses.player.core.data.dao.ArtistDao,
) : SongRepository {
    override suspend fun markDownloadedFileMissing(id: String, sourceId: String) {
        songDao.markFileMissing(id, sourceId)
        rebuildDerivedIndexes()
    }
    override fun observeSongs(): Flow<List<Song>> =
        songDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeSongs(query: String): Flow<List<Song>> =
        songDao.observeSearch(query.trim()).map { entities -> entities.map { it.toDomain() } }

    override suspend fun replaceSourceSongs(sourceId: String, songs: List<Song>): ScanMergeResult {
        val existing = songDao.getBySource(sourceId)
        val existingVisible = existing.count { !it.missing }
        // 空结果/锐减保护：音源异常（NAS 掉线、目录临时不可见）时不动库
        if (shouldSkipScan(scanned = songs.size, existingVisible = existingVisible)) {
            return ScanMergeResult(scanned = songs.size, skipped = true)
        }

        val existingById = existing.associateBy { it.id }
        val scannedIds = songs.mapTo(HashSet()) { it.id }
        val merged = songs.map { song ->
            val entity = song.toEntity()
            val old = existingById[entity.id]
            if (old == null) entity else mergeScanned(old, entity)
        }
        // 先整源标记「丢失」，再用扫描结果 upsert（命中行 missing 复位，新行直接入库）——
        // 避免 `id NOT IN (大量 id)` 的 SQLite 变量上限，也避免扫描不足时直接清库；
        // 两步在同一事务内，中途失败不会出现「整源暂时消失」
        songDao.reconcileScan(sourceId, merged)
        rebuildDerivedIndexes()

        return ScanMergeResult(
            scanned = songs.size,
            added = songs.count { it.id !in existingById },
            missing = existing.count { it.id !in scannedIds },
        )
    }

    override suspend fun deleteSourceSongs(sourceId: String) {
        songDao.deleteBySource(sourceId)
        rebuildDerivedIndexes()
    }

    override suspend fun getSong(id: String): Song? = songDao.getById(id)?.toDomain()

    override suspend fun getSongs(ids: List<String>): Map<String, Song> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(900).flatMap { chunk ->
            songDao.getByIds(chunk)
        }.associate { it.id to it.toDomain() }
    }

    override suspend fun upsert(song: Song) {
        songDao.upsert(song.toEntity())
        // 懒扫描回写可能改变专辑/艺术家归属，同步重建派生索引
        rebuildDerivedIndexes()
    }

    override suspend fun prepareDownloads(queue: List<Song>) {
        val candidates = queue.filter { it.sourceType != com.muses.player.core.model.SourceType.ONLINE }.distinctBy { it.id }
        val existing = candidates.map { it.id }.chunked(900).flatMap { songDao.getByIds(it) }.associateBy { it.id }
        val changes = candidates.mapNotNull { song ->
            val old = existing[song.id]
            when {
                old == null -> song.toEntity()
                old.missing || old.path != song.path || old.sourceId != song.sourceId ->
                    old.copy(path = song.path, sourceId = song.sourceId, sourceType = song.toEntity().sourceType, missing = false)
                else -> null
            }
        }
        if (changes.isNotEmpty()) { songDao.insertAll(changes); rebuildDerivedIndexes() }
    }

    override suspend fun upsertAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        songDao.insertAll(songs.distinctBy { it.id }.map { it.toEntity() })
        rebuildDerivedIndexes()
    }

    /**
     * 扫描结果与库内旧行的字段级合并（发现层刷新 / 富化层保护）。
     *
     * 根因：扫描器只产出「发现层」数据（WebDAV 仅文件名、本地 readTags=false 仅文件名），
     * 旧实现 insertAll 是整行 REPLACE，会把已刮削封面·歌词、字段来源标记、懒扫描补齐的
     * 标签/时长与 tagsVersion 一并打回空。此处改为：
     * - 发现层按扫描结果刷新：path；
     * - 富化层「库内非空则保留、为空才用扫描值补齐」，且尊重显式刮削决策
     *   （metaCover / lyricsSource 非空表示用户已选过，不复用扫描值）；
     * - tagsVersion 取两者较大值，不回退。
     */
    private fun mergeScanned(old: SongEntity, scanned: SongEntity): SongEntity {
        // 本地完整扫描已成功读取文件标签，刷新缓存（含文件中已移除的字段）。
        // WebDAV 的文件名发现结果没有标签，继续保留缓存，播放时核对文件版本。
        if (scanned.tagsVersion >= SongTags.TAGS_VERSION) return scanned.copy(
            durationMs = scanned.durationMs.takeIf { it > 0 } ?: old.durationMs,
            durationSec = scanned.durationSec.takeIf { it > 0 } ?: old.durationSec,
            missing = false,
        )
        return old.copy(
        path = scanned.path.ifBlank { old.path },
        // 标题：仅当旧行仍是文件名占位（未读标签、未刮削）时才允许用本次扫描结果刷新
        title = if (old.tagsVersion < SongTags.TAGS_VERSION && old.metaTitle == null && scanned.title.isNotBlank()) {
            scanned.title
        } else {
            old.title
        },
        artist = old.artist ?: scanned.artist,
        albumTitle = old.albumTitle ?: scanned.albumTitle,
        durationMs = if (old.durationMs > 0L) old.durationMs else scanned.durationMs,
        durationSec = if (old.durationSec > 0L) old.durationSec else scanned.durationSec,
        coverUri = if (old.metaCover != null) old.coverUri else old.coverUri ?: scanned.coverUri,
        lyrics = if (old.lyricsSource != null) old.lyrics else old.lyrics ?: scanned.lyrics,
        lyricsFormat = old.lyricsFormat ?: scanned.lyricsFormat,
        lyricsSource = old.lyricsSource ?: scanned.lyricsSource,
        metaTitle = old.metaTitle ?: scanned.metaTitle,
        metaArtist = old.metaArtist ?: scanned.metaArtist,
        metaAlbum = old.metaAlbum ?: scanned.metaAlbum,
        metaCover = old.metaCover ?: scanned.metaCover,
        tagsVersion = maxOf(old.tagsVersion, scanned.tagsVersion),
        missing = false,
        audioQuality = scanned.audioQuality ?: old.audioQuality,
        )
    }

    /**
     * 空结果 / 锐减保护：库内已有歌时，扫描发现 0 首、或不足既有可见数一半，
     * 视为音源异常（NAS 掉线、目录临时不可见）而不落库。
     * 小库（可见歌 < [SHRINK_GUARD_MIN_EXISTING]）只做空结果保护，避免误拦。
     */
    private fun shouldSkipScan(scanned: Int, existingVisible: Int): Boolean {
        if (existingVisible <= 0) return false
        if (scanned == 0) return true
        return existingVisible >= SHRINK_GUARD_MIN_EXISTING && scanned * 2 < existingVisible
    }

    /**
     * 从全库 songs 重建 albums/artists 索引与 cross refs（派生数据，全量重建幂等）。
     * M1 遗留缺口补齐：albums/artists 表此前无任何写入方，专辑/艺术家页恒为空。
     * id 约定："album:<标题>" / "artist:<名称>"（稳定可读，详情路由内部自洽）；
     * 无标题归入「未知专辑」、无艺术家归入「未知艺术家」（与列表页兜底文案一致）。
     */
    override suspend fun rebuildDerivedIndexes() {
        val all = songDao.getAll()
        if (all.isEmpty()) {
            albumDao.deleteAll()
            artistDao.deleteAll()
            songDao.clearSongAlbumRefs()
            songDao.clearSongArtistRefs()
            return
        }

        val albumRefs = mutableListOf<com.muses.player.core.data.db.SongAlbumCrossRef>()
        val artistRefs = mutableListOf<com.muses.player.core.data.db.SongArtistCrossRef>()

        val albums = all
            .groupBy { it.albumTitle?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "未知专辑" }
            .map { (title, group) ->
                val albumId = "album:$title"
                group.forEach { song ->
                    albumRefs += com.muses.player.core.data.db.SongAlbumCrossRef(song.id, albumId)
                }
                com.muses.player.core.data.db.AlbumEntity(
                    id = albumId,
                    title = title,
                    artist = group.mapNotNull { it.artist }.distinct().firstOrNull(),
                    songCount = group.size,
                )
            }

        val artists = all
            .groupBy { it.artist?.trim()?.takeIf { a -> a.isNotEmpty() } ?: "未知艺术家" }
            .map { (name, group) ->
                val artistId = "artist:$name"
                group.forEach { song ->
                    artistRefs += com.muses.player.core.data.db.SongArtistCrossRef(song.id, artistId)
                }
                com.muses.player.core.data.db.ArtistEntity(
                    id = artistId,
                    name = name,
                    songCount = group.size,
                )
            }

        albumDao.deleteAll()
        albumDao.insertAll(albums)
        artistDao.deleteAll()
        artistDao.insertAll(artists)
        songDao.clearSongAlbumRefs()
        songDao.insertSongAlbumRefs(albumRefs)
        songDao.clearSongArtistRefs()
        songDao.insertSongArtistRefs(artistRefs)
    }

    private companion object {
        /** 锐减保护生效的最小可见曲库量：低于此值的小库只做空结果保护 */
        const val SHRINK_GUARD_MIN_EXISTING = 10
    }
}

/** 音源仓库 */
interface SourceRepository {
    fun observeSources(): Flow<List<Source>>
    suspend fun getSource(id: String): Source?
    suspend fun upsert(source: Source)
    suspend fun deleteById(id: String)
}

class RoomSourceRepository constructor(
    private val sourceDao: SourceDao,
) : SourceRepository {
    override fun observeSources(): Flow<List<Source>> =
        sourceDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getSource(id: String): Source? =
        sourceDao.getById(id)?.toDomain()

    override suspend fun upsert(source: Source) {
        sourceDao.upsert(source.toEntity())
    }

    override suspend fun deleteById(id: String) {
        sourceDao.deleteById(id)
    }
}

/** 专辑仓库 */
interface AlbumRepository {
    fun observeAlbums(): Flow<List<Album>>
    fun observeAlbumWithSongs(albumId: String): Flow<com.muses.player.core.data.db.AlbumWithSongs?>
}

class RoomAlbumRepository constructor(
    private val albumDao: AlbumDao,
) : AlbumRepository {
    override fun observeAlbums(): Flow<List<Album>> =
        albumDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeAlbumWithSongs(albumId: String): Flow<com.muses.player.core.data.db.AlbumWithSongs?> =
        albumDao.observeAlbumWithSongs(albumId)
}

/** 艺术家仓库 */
interface ArtistRepository {
    fun observeArtists(): Flow<List<Artist>>
    fun observeArtistWithSongs(artistId: String): Flow<com.muses.player.core.data.db.ArtistWithSongs?>
}

class RoomArtistRepository constructor(
    private val artistDao: ArtistDao,
) : ArtistRepository {
    override fun observeArtists(): Flow<List<Artist>> =
        artistDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeArtistWithSongs(artistId: String): Flow<com.muses.player.core.data.db.ArtistWithSongs?> =
        artistDao.observeArtistWithSongs(artistId)
}
