package com.muses.player.core.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackRef

/** 导航壳提供入队动作，列表不会直接获取音频。 */
val LocalDownloadSong = staticCompositionLocalOf<((Song) -> Unit)?> { null }

/**
 * 在线歌曲行的统一动作区。
 *
 * 行内只保留「喜欢 + 三点菜单」：菜单里已经有「添加到歌单」，
 * 再放一个独立加号就是重复入口。需要单独加号、又没有菜单的场景请直接使用
 * [SongPlaylistButton]，不要在这里叠加。
 */
@Composable
fun OnlineSongDownloadAction(song: Song, onDismissRecommendation: (() -> Unit)? = null) {
    val enqueue = LocalDownloadSong.current
    val favorites = LocalFavoriteSongs.current
    val setLiked = LocalSetSongLiked.current
    val addToPlaylist = LocalAddSongToPlaylist.current
    if (enqueue == null && onDismissRecommendation == null && setLiked == null && addToPlaylist == null) return
    // 只有 LX 在线引用才能走下载管线；本地/WebDAV 曲目在菜单里不应出现无效的「下载歌曲」。
    val downloadable = enqueue != null && OnlineTrackRef.isOnlineUri(song.path)
    var opened by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SongFavoriteButton(song)
        SongMoreButton(onClick = { opened = true })
    }
    MusesActionsSheet(opened = opened, onDismiss = { opened = false }, label = "歌曲操作",
        songInfo = MusesActionSongInfo(song.title, song.artist, song.album, song.coverUri),
        items = buildList {
            if (setLiked != null && favorites.loaded) {
                val liked = favorites.isLiked(song)
                add(MusesActionItem(if (liked) "取消我喜欢" else "标记我喜欢", onClick = {
                    setLiked(song, !liked)
                    opened = false
                }))
            }
            if (addToPlaylist != null) add(MusesActionItem("添加到歌单", onClick = { opened = false; addToPlaylist(song) }))
            if (downloadable) add(MusesActionItem("下载歌曲", onClick = { enqueue(song); opened = false }))
            if (onDismissRecommendation != null) add(MusesActionItem("无感", onClick = { onDismissRecommendation(); opened = false }))
        })
}
