package com.muses.player.core.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.muses.player.core.data.db.SongAlbumCrossRef
import com.muses.player.core.data.db.SongArtistCrossRef
import com.muses.player.core.data.db.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Upsert
    suspend fun insertAll(songs: List<SongEntity>)

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Query("SELECT * FROM songs WHERE missing = 0 ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SongEntity>>

    /** 搜索流：空串回全库，否则标题/艺术家/专辑模糊匹配（Room 线程执行，不占主线程）；丢失歌不参与 */
    @Query("SELECT * FROM songs WHERE missing = 0 AND (:query = '' OR title LIKE '%' || :query || '%' COLLATE NOCASE OR artist LIKE '%' || :query || '%' COLLATE NOCASE OR albumTitle LIKE '%' || :query || '%' COLLATE NOCASE) ORDER BY title COLLATE NOCASE ASC")
    fun observeSearch(query: String): Flow<List<SongEntity>>

    /** 全量可见歌曲（派生索引重建 / AI 画像用；丢失歌不参与） */
    @Query("SELECT * FROM songs WHERE missing = 0")
    suspend fun getAll(): List<SongEntity>

    /** M3 自动补缺：未读过标签的歌（文件名建库 tagsVersion=0 或本地未扫）；丢失歌不参与 */
    @Query("SELECT id FROM songs WHERE tagsVersion < 1 AND missing = 0")
    suspend fun getUntaggedSongIds(): List<String>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    /** 批量取单曲（写回等多选链路一次查全量，避免 N 次逐条；分片防 IN 参数上限） */
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SongEntity>

    /** 批量存在性：恢复快照一次查全量，避免 N 次逐条查询 */
    @Query("SELECT id FROM songs WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: String): Flow<SongEntity?>

    /** U12：按 id 集合批量观测（播放队列行展示字段组合用，Room IN 参数上限 999 足够队列长度） */
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<SongEntity>>

    /** 标题模糊搜索（丢失歌不参与） */
    @Query("SELECT * FROM songs WHERE missing = 0 AND title LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY title COLLATE NOCASE ASC")
    suspend fun searchByTitle(query: String): List<SongEntity>

    /** 按音源取全部行（**含 missing**：扫描合并需保留旧行数据，删音源需清全部 id） */
    @Query("SELECT * FROM songs WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): List<SongEntity>

    /**
     * 扫描前把该音源存量行全部标记为「丢失」，随后扫描到的行 upsert 时复位。
     * 取代旧 `deleteBySourceExcept` 硬删：网络/挂载抖动不再清库。
     */
    @Query("UPDATE songs SET missing = 1 WHERE sourceId = :sourceId")
    suspend fun markAllMissing(sourceId: String)

    @Query("DELETE FROM songs WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    /** 删除单曲 */
    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE songs SET missing = 1 WHERE id = :id AND sourceId = :sourceId")
    suspend fun markFileMissing(id: String, sourceId: String)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    /**
     * 扫描入库：先把该音源存量行全部标记「丢失」，再用扫描结果 upsert（命中行复位）。
     * 同一事务内完成，中途失败不会出现「整源暂时消失」。字段级合并见 RoomSongRepository。
     */
    @Transaction
    suspend fun reconcileScan(sourceId: String, songs: List<SongEntity>) {
        markAllMissing(sourceId)
        insertAll(songs)
    }

    /** 清空专辑/艺术家索引（重建前调用） */
    @Query("DELETE FROM song_album_cross_ref")
    suspend fun clearSongAlbumRefs()

    @Query("DELETE FROM song_artist_cross_ref")
    suspend fun clearSongArtistRefs()

    @Upsert
    suspend fun insertSongAlbumRefs(refs: List<SongAlbumCrossRef>)

    @Upsert
    suspend fun insertSongArtistRefs(refs: List<SongArtistCrossRef>)
}
