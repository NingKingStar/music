package com.muses.player.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.sameSong
import com.muses.player.core.model.download.DownloadTrack
import com.muses.player.core.model.online.OnlineTrackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FavoriteSelection(val track: DownloadTrack, val liked: Boolean)

data class FavoriteSongsState(
    val selections: List<FavoriteSelection> = emptyList(),
    val library: List<Song> = emptyList(),
    val loaded: Boolean = false,
) {
    fun isInLibrary(song: Song): Boolean = library.any { sameSong(it, song) }

    fun isLiked(song: Song): Boolean =
        selections.lastOrNull { sameSong(it.track.toSong(), song) }?.liked == true

    /** 喜欢仅由心形管理；优先使用曲库中仍存在的文件和最新标签。 */
    val likedSongs: List<Song> by lazy { selections.asReversed()
        .filter { it.liked && isLiked(it.track.toSong()) }
        .map { selection ->
            val song = selection.track.toSong()
            library.firstOrNull { sameSong(it, song) } ?: song
        }.fold(emptyList<Song>()) { result, song ->
            if (result.any { sameSong(it, song) }) result else result + song
        }
    }

    /** 仅手动收藏的在线歌曲需要下载；同曲跨平台只保留一份。 */
    val missingOnlineSongs: List<Song> get() = likedSongs.filter {
        OnlineTrackRef.parse(it.path) != null && !isInLibrary(it)
    }
}

/** 喜欢独立持久化；本地文件、下载与自建歌单均不隐式设置喜欢。 */
class FavoriteSongsStore(
    private val dataStore: DataStore<Preferences>,
    songs: SongRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val key = stringPreferencesKey("favorite_songs_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(prefs: Preferences): List<FavoriteSelection> =
        prefs[key]?.let { json.decodeFromString(it) } ?: emptyList()
    private val snapshots = combine(dataStore.data.map { it[key] }.distinctUntilChanged(), songs.observeSongs()) { raw, library ->
        FavoriteSongsState(raw?.let { json.decodeFromString(it) } ?: emptyList(), library.filter { it.sourceType != SourceType.ONLINE }, loaded = true)
            .also { it.likedSongs }
    }.flowOn(Dispatchers.Default)
    val state = snapshots.stateIn(scope, SharingStarted.Eagerly, FavoriteSongsState())
    suspend fun snapshot(): FavoriteSongsState = snapshots.first()

    suspend fun setLiked(song: Song, liked: Boolean): Boolean {
        dataStore.edit { prefs ->
            val updated = decode(prefs).filterNot { sameSong(it.track.toSong(), song) } +
                FavoriteSelection(DownloadTrack.from(song), liked)
            prefs[key] = json.encodeToString(updated)
        }
        return true
    }
}
