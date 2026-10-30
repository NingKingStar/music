package com.muses.player.navigation

import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackRef

/** 进入选歌时固定快照，避免后台补全或排序变化使勾选落到另一首歌。 */
internal data class PlaylistDownloadSelection private constructor(
    val songs: List<Song>,
    val selectedIds: Set<String>,
) {
    val selectedSongs: List<Song> get() = songs.filter { it.id in selectedIds }
    val allSelected: Boolean get() = songs.isNotEmpty() && selectedIds.size == songs.size
    fun toggle(id: String): PlaylistDownloadSelection =
        if (songs.none { it.id == id }) this
        else copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
    fun toggleAll(): PlaylistDownloadSelection = copy(selectedIds = if (allSelected) emptySet() else songs.map { it.id }.toSet())

    companion object {
        fun from(songs: List<Song>): PlaylistDownloadSelection {
            val snapshot = songs.filter { OnlineTrackRef.parse(it.path) != null }.distinctBy { it.id }.toList()
            return PlaylistDownloadSelection(snapshot, snapshot.map { it.id }.toSet())
        }
    }
}
