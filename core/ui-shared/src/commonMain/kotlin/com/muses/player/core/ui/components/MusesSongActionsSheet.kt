package com.muses.player.core.ui.components

import androidx.compose.runtime.Composable

/** 曲库和沉浸式播放页共用的歌曲操作菜单。 */
@Composable
fun MusesSongActionsSheet(
    songId: String?,
    onDismiss: () -> Unit,
    /** 仅能在曲库中查到、可回写元数据的歌曲提供刮削入口。 */
    onEnqueueScrape: ((List<String>) -> Unit)?,
    /** 追加到播放队列（PlaybackPort.addToQueue）；提示文案由调用方给出，空列表不算成功。 */
    onAddToQueue: (String) -> Unit,
    onEnqueueDownload: (() -> Unit)? = null,
    favoriteSong: com.muses.player.core.model.Song? = null,
) {
    val favorites = LocalFavoriteSongs.current
    val setLiked = LocalSetSongLiked.current
    val addToPlaylist = LocalAddSongToPlaylist.current
    MusesActionsSheet(
        opened = songId != null,
        onDismiss = onDismiss,
        label = "歌曲操作",
        songInfo = favoriteSong?.let { MusesActionSongInfo(it.title, it.artist, it.album, it.coverUri) },
        items = listOfNotNull(
            favoriteSong?.takeIf { favorites.loaded && setLiked != null }?.let { song ->
                val liked = favorites.isLiked(song)
                MusesActionItem(if (liked) "取消我喜欢" else "标记我喜欢", onClick = {
                    setLiked?.invoke(song, !liked)
                    onDismiss()
                })
            },
            favoriteSong?.takeIf { addToPlaylist != null }?.let { song ->
                MusesActionItem("添加到歌单", onClick = { onDismiss(); addToPlaylist?.invoke(song) })
            },
            onEnqueueDownload?.let { enqueue -> MusesActionItem("下载歌曲", onClick = { enqueue(); onDismiss() }) },
            onEnqueueScrape?.let { enqueueScrape -> MusesActionItem(label = "添加到刮削队列", onClick = {
                songId?.let {
                    enqueueScrape(listOf(it))
                    MusesSnackbar.show("添加成功")
                }
                onDismiss()
            }) },
            MusesActionItem(label = "添加到播放队列", onClick = {
                songId?.let(onAddToQueue)
                onDismiss()
            }),
        ),
    )
}
