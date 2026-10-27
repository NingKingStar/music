package com.muses.player.feature.sources

import com.muses.player.core.data.repository.UserPlaylist
import com.muses.player.core.model.Song
import com.muses.player.core.model.sameSong
import com.muses.player.core.search.OnlineSearchResult
import kotlin.math.abs

internal fun playlistMembership(ids: List<String>, playlists: List<UserPlaylist>): String? {
    val idsSet = ids.toSet()
    val names = playlists.filter { playlist -> playlist.tracks.any { it.id in idsSet } }.map { it.name }
    if (names.isEmpty()) return null
    return "已加入 " + names.take(2).joinToString("、") + if (names.size > 2) " 等${names.size}个歌单" else ""
}

internal fun matchingLibrarySongs(group: List<OnlineSearchResult>, library: List<Song>): List<Song> =
    library.filter { local -> group.any { online ->
        val candidate = online.toSong("online")
        val sameId = local.id == candidate.id
        val localAlbum = local.album.orEmpty().trim()
        val onlineAlbum = candidate.album.orEmpty().trim()
        val sameAlbum = localAlbum.isNotEmpty() && onlineAlbum.isNotEmpty() &&
            localAlbum.equals(onlineAlbum, ignoreCase = true)
        sameId || (sameAlbum && local.durationMs > 0 && candidate.durationMs > 0 &&
            abs(local.durationMs - candidate.durationMs) <= 3000 && sameSong(local, candidate))
    } }

internal fun searchMembership(
    group: List<OnlineSearchResult>, library: List<Song>, playlists: List<UserPlaylist>,
): String? {
    val matches = matchingLibrarySongs(group, library)
    val ids = group.map { it.toSong("online").id } + matches.map { it.id }
    return playlistMembership(ids, playlists)
}
