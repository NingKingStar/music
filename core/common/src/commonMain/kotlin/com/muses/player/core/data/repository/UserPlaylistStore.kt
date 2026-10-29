package com.muses.player.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muses.player.core.model.Song
import com.muses.player.core.model.sameSong
import com.muses.player.core.model.download.DownloadTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** 这里只保存用户歌单；系统的喜欢和下载不存在可写的歌单 ID。 */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<DownloadTrack> = emptyList(),
    val followPreferredQuality: Boolean = false,
) {
    fun contains(song: Song): Boolean = tracks.any { sameSong(it.toSong(), song) }
    fun adding(song: Song): UserPlaylist =
        if (contains(song)) this else copy(tracks = tracks + DownloadTrack.from(song))
    fun removing(song: Song): UserPlaylist = copy(tracks = tracks.filterNot { sameSong(it.toSong(), song) })
}

class UserPlaylistStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("user_playlists_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(prefs: Preferences): List<UserPlaylist> =
        prefs[key]?.let { json.decodeFromString(it) } ?: emptyList()
    val playlists = dataStore.data.map { it[key] }.distinctUntilChanged().map { raw ->
        raw?.let { json.decodeFromString<List<UserPlaylist>>(it) } ?: emptyList()
    }

    private suspend fun update(transform: (List<UserPlaylist>) -> List<UserPlaylist>) {
        dataStore.edit { prefs -> prefs[key] = json.encodeToString(transform(decode(prefs))) }
    }

    private fun validateName(name: String, playlists: List<UserPlaylist>, exceptId: String? = null): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 40) { "歌单名称需为 1–40 个字符" }
        require(trimmed !in setOf("我喜欢", "已下载")) { "该名称用于系统分类，请换一个名称" }
        require(playlists.none { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }) { "已存在同名歌单" }
        return trimmed
    }

    /** 新建和可选的首次加歌在同一次事务中保存，失败不会留下半成品。 */
    suspend fun create(name: String, firstSong: Song? = null): String {
        val id = "playlist-" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        update { current ->
            current + UserPlaylist(id, validateName(name, current), firstSong?.let { listOf(DownloadTrack.from(it)) } ?: emptyList())
        }
        return id
    }

    /** 已完整读取并核对的歌单一次性保存，失败时不留下部分歌单。 */
    suspend fun createImported(name: String, songs: List<Song>): String {
        require(songs.isNotEmpty()) { "没有可导入的歌曲" }
        val id = "playlist-" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        val tracks = songs.map(DownloadTrack::from)
        update { current -> current + UserPlaylist(id, validateName(name, current), tracks, followPreferredQuality = true) }
        return id
    }

    /** 只应用检查开始时仍未被用户编辑的条目；不增删或重排成员。 */
    suspend fun applyMetadata(id: String, expected: List<DownloadTrack>, updated: List<Song>) = update { current ->
        require(current.any { it.id == id }) { "歌单已被删除" }
        val originals = expected.associateBy { it.id }
        val patches = updated.associateBy { it.id }
        current.map { playlist -> if (playlist.id != id) playlist else playlist.copy(tracks = playlist.tracks.map { saved ->
            if (saved == originals[saved.id]) patches[saved.id]?.let(DownloadTrack::from) ?: saved else saved
        }) }
    }

    suspend fun rename(id: String, name: String) = update { current ->
        require(current.any { it.id == id }) { "歌单已被删除" }
        val valid = validateName(name, current, id)
        current.map { if (it.id == id) it.copy(name = valid) else it }
    }

    /** 只追加到明确选择的自建歌单，不同步喜欢、不更改其他歌单。 */
    suspend fun addTo(ids: Set<String>, song: Song) = update { current ->
        require(ids.isNotEmpty() && ids.all { id -> current.any { it.id == id } }) { "请选择仍存在的自建歌单" }
        current.map { if (it.id in ids) it.adding(song) else it }
    }

    suspend fun removeFrom(id: String, song: Song) = update { current ->
        current.map { if (it.id == id) it.removing(song) else it }
    }

    /** 删除歌单仅解除成员关系，不删除歌曲文件或其他分类。 */
    suspend fun delete(id: String) = update { current -> current.filterNot { it.id == id } }
    suspend fun snapshot(): List<UserPlaylist> = playlists.first()
}
