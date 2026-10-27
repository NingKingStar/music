package com.muses.player.core.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.muses.player.core.model.Song

/** 导航壳提供入队动作，列表不会直接获取音频。 */
val LocalDownloadSong = staticCompositionLocalOf<((Song) -> Unit)?> { null }

@Composable
fun OnlineSongDownloadAction(song: Song, onDismissRecommendation: (() -> Unit)? = null, showPlaylistButton: Boolean = true) {
    val enqueue = LocalDownloadSong.current
    val favorites = LocalFavoriteSongs.current
    val setLiked = LocalSetSongLiked.current
    val addToPlaylist = LocalAddSongToPlaylist.current
    if (enqueue == null && onDismissRecommendation == null && setLiked == null && addToPlaylist == null) return
    var opened by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SongFavoriteButton(song)
        if (showPlaylistButton) SongPlaylistButton(song)
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
            if (enqueue != null) add(MusesActionItem("下载歌曲", onClick = { enqueue(song); opened = false }))
            if (onDismissRecommendation != null) add(MusesActionItem("无感", onClick = { onDismissRecommendation(); opened = false }))
        })
}
