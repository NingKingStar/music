package com.muses.player.core.search.provider

import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.wyCatalogQualityLabel
import com.muses.player.core.search.wyCatalogQualityTiers
import kotlinx.serialization.json.JsonObject

/** 搜索、榜单与导入共用，平台编号与专辑编号分别保留。 */
internal fun parseWyTrack(item: JsonObject): OnlineSearchResult? {
    val id = item.str("id")?.takeIf { it.toLongOrNull() != null } ?: return null
    val title = item.str("name")?.takeIf { it.isNotBlank() } ?: return null
    val artist = (item.arr("ar") ?: item.arr("artists"))?.joinStr("name")?.takeIf { it.isNotBlank() }
    val album = item.obj("al") ?: item.obj("album")
    val duration = (item.long("dt") ?: item.long("duration"))?.takeIf { it > 0 }
    return OnlineSearchResult("wy", id, title, artist, album?.str("name"), duration,
        album?.str("picUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        buildMusicInfo("id" to id, name = title, singer = artist, album = album?.str("name"), durationSec = duration?.div(1000),
            extra = buildMap {
                album?.str("id")?.let { put("albumId", it) }
                item.long("originCoverType")?.let { put("originCoverType", it.toString()) }
                wyCatalogQualityLabel(item)?.let { put("catalogQuality", it) }
                wyCatalogQualityTiers(item).takeIf { it.isNotEmpty() }?.let { put("catalogQualityTiers", it.joinToString(",")) }
                item.str("isrc")?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
            }))
}

internal fun parseTxTrack(raw: JsonObject): OnlineSearchResult? {
    val item = raw.obj("songInfo") ?: raw.obj("songinfo") ?: raw
    val mid = (item.str("mid") ?: item.str("songmid"))?.takeIf { it.isNotBlank() } ?: return null
    val title = (item.str("name") ?: item.str("title") ?: item.str("songname"))?.takeIf { it.isNotBlank() } ?: return null
    val artist = item.arr("singer")?.joinStr("name")?.takeIf { it.isNotBlank() }
    val album = item.obj("album")
    val seconds = item.long("interval")?.takeIf { it > 0 }
    val numericId = (item.str("id") ?: item.str("songid"))?.takeIf { it.toLongOrNull() != null }
    return OnlineSearchResult("tx", mid, title, artist, album?.str("name"), seconds?.times(1000), qqCoverUrl(album),
        buildMusicInfo("songmid" to mid, "mid" to mid, "id" to numericId, "songId" to numericId, "song_id" to numericId,
            name = title, singer = artist, album = album?.str("name"), durationSec = seconds,
            extra = buildMap {
                putAll(catalogMetadata("tx", item))
                album?.str("id")?.let { put("albumId", it) }
                album?.str("mid")?.let { put("albumMid", it) }
                item.str("isrc")?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
            }))
}
