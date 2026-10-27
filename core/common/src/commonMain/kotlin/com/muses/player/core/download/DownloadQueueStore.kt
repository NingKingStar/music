package com.muses.player.core.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muses.player.core.model.download.DownloadTask
import com.muses.player.core.model.download.DownloadStatus
import com.muses.player.core.model.download.DownloadTarget
import com.muses.player.core.model.download.DownloadedSong
import com.muses.player.core.model.download.collectCompletedDownloads
import com.muses.player.core.data.repository.FavoriteSelection
import com.muses.player.core.data.repository.UserPlaylist
import com.muses.player.core.model.online.OnlineTrackRef
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 与曲库独立的持久队列；在线歌曲快照不会因进程退出而丢失。 */
class DownloadQueueStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("download_queue_v1")
    private val targetKey = stringPreferencesKey("download_target_v1")
    private val downloadedKey = stringPreferencesKey("downloaded_songs_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(raw: String?): List<DownloadTask> = if (raw == null) emptyList() else json.decodeFromString(raw)
    val tasks = dataStore.data.map { it[key] }.distinctUntilChanged().map(::decode)
    private fun downloaded(prefs: Preferences): List<DownloadedSong> =
        prefs[downloadedKey]?.let { json.decodeFromString(it) } ?: emptyList()
    // 旧版本的完成记录即时可见；下一次队列写入时原子保存迁移，清理任务不会丢失。
    // 已下载快照存在后不再因每个进度回调反复解码；无快照时仍兼容旧完成任务。
    val downloadedSongs = dataStore.data.map { prefs ->
        prefs[downloadedKey]?.let { it to false } ?: (prefs[key] to true)
    }.distinctUntilChanged().map { (raw, legacy) ->
        if (legacy) collectCompletedDownloads(emptyList(), decode(raw)).asReversed()
        else (raw?.let { json.decodeFromString<List<DownloadedSong>>(it) } ?: emptyList()).asReversed()
    }
    val defaultTarget = dataStore.data.map { prefs -> prefs[targetKey]?.let { json.decodeFromString<DownloadTarget>(it) } ?: DownloadTarget() }
    suspend fun setDefaultTarget(target: DownloadTarget) { dataStore.edit { it[targetKey] = json.encodeToString(target) } }
    suspend fun load() = tasks.first()

    /** 删除文件后原子移除对应记录和已完成任务；保留收藏，并将精确的本地引用还原为在线来源。 */
    suspend fun forgetDownloaded(entry: DownloadedSong) {
        dataStore.edit { prefs ->
            val saved = if (prefs[downloadedKey] == null)
                collectCompletedDownloads(emptyList(), decode(prefs[key])) else downloaded(prefs)
            require(saved.any { it == entry }) { "下载记录已变化，请刷新后重试" }
            val local = entry.toSong()
            fun sameFile(track: com.muses.player.core.model.download.DownloadTrack) =
                track.id == local.id && track.reference == entry.location
            if (OnlineTrackRef.parse(entry.track.reference) != null) {
                val favoriteKey = stringPreferencesKey("favorite_songs_v1")
                val playlistKey = stringPreferencesKey("user_playlists_v1")
                prefs[favoriteKey]?.let { raw ->
                    val values = json.decodeFromString<List<FavoriteSelection>>(raw)
                    prefs[favoriteKey] = json.encodeToString(values.map {
                        if (sameFile(it.track)) it.copy(track = entry.track) else it
                    })
                }
                prefs[playlistKey]?.let { raw ->
                    val values = json.decodeFromString<List<UserPlaylist>>(raw)
                    prefs[playlistKey] = json.encodeToString(values.map { playlist ->
                        playlist.copy(tracks = playlist.tracks.map { if (sameFile(it)) entry.track else it })
                    })
                }
            }
            prefs[downloadedKey] = json.encodeToString(saved.filterNot { it == entry })
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { task ->
                task.status == DownloadStatus.COMPLETED && task.savedLocation == entry.location &&
                    (task.target ?: DownloadTarget()).kind == entry.target.kind &&
                    (task.target ?: DownloadTarget()).sourceId == entry.target.sourceId
            })
        }
    }

    suspend fun update(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        dataStore.edit { prefs ->
            val previous = decode(prefs[key])
            val next = transform(previous)
            val before = previous.filter { it.status == DownloadStatus.COMPLETED }
            val after = next.filter { it.status == DownloadStatus.COMPLETED }
            // 进度变化不解码/遍历已下载表；旧队列清理前仍先原子收录完成记录。
            if (prefs[downloadedKey] == null || before != after) {
                val saved = downloaded(prefs)
                val collected = collectCompletedDownloads(saved, before + after)
                if (prefs[downloadedKey] == null || collected != saved) prefs[downloadedKey] = json.encodeToString(collected)
            }
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun enqueue(task: DownloadTask): Boolean {
        var added = false
        update { existing ->
            if (existing.any { it.track.id == task.track.id }) existing
            else (existing + task).also { added = true }
        }
        return added
    }

    /** 进程中断后保留任务；必须由用户手动继续，不自动开始待下载项。 */
    suspend fun recoverInterrupted() = update { tasks -> tasks.map {
        if (it.status.active) it.copy(
            status = if (it.localAudioPath != null && it.target?.kind == com.muses.player.core.model.download.DownloadTargetKind.WEBDAV)
                DownloadStatus.PENDING_UPLOAD else DownloadStatus.PAUSED,
            error = if (it.localAudioPath != null) "本地文件已保留，点击继续" else "上次下载已中断，点击继续",
        ) else it
    } }
}
