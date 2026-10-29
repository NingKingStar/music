package com.muses.player.core.search

import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackIdentity
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

fun needsSongMetadata(song: Song): Boolean = song.title.isBlank() || song.title == "待补全歌曲" || song.artist.isNullOrBlank() ||
    song.album.isNullOrBlank() || song.durationMs <= 0 || song.coverUri.isNullOrBlank()

/** 同平台编号补全；不改有效标题、手工内容、音质选择与原歌曲身份。 */
fun fillMissingSongMetadata(song: Song, result: OnlineSearchResult): Song {
    val ref = OnlineTrackRef.parse(song.path) ?: return song
    if (!OnlineTrackIdentity.matches(ref, result.toTrackRef(ref.sourceId))) return song
    val previous = runCatching { Json.parseToJsonElement(ref.musicInfoJson).jsonObject }.getOrNull() ?: return song
    val detail = Json.parseToJsonElement(result.musicInfoJson).jsonObject
    val merged = previous.toMutableMap()
    detail.forEach { (key, value) ->
        if ((previous[key] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() || key.startsWith("catalog") || key == "originCoverType" ||
            (key in setOf("interval", "duration") && ((previous[key] as? JsonPrimitive)?.longOrNull ?: 0) <= 0)) {
            merged[key] = value
        }
    }
    val duration = song.durationMs.takeIf { it > 0 } ?: song.durationSec.takeIf { it > 0 }?.times(1000) ?: result.durationMs ?: 0
    return song.copy(
        title = song.title.takeIf { it.isNotBlank() && it != "待补全歌曲" } ?: result.name,
        artist = song.artist?.takeIf { it.isNotBlank() } ?: result.artist,
        album = song.album?.takeIf { it.isNotBlank() } ?: result.album,
        coverUri = song.coverUri?.takeIf { it.isNotBlank() } ?: result.coverUrl,
        durationMs = duration,
        durationSec = duration / 1000,
        path = ref.copy(musicInfoJson = JsonObject(merged).toString()).encode(),
    )
}

data class MetadataRepairResult(val songs: List<Song>, val checked: Int, val timedOut: Boolean)

/** 只读目录详情，不解析音频，不把搜索到的相似曲当成原曲。整个检查最多 30 秒。 */
class PlaylistMetadataRepair(private val http: SearchHttp, private val search: OnlineSearchService) {
    suspend fun inspect(songs: List<Song>): MetadataRepairResult {
        val found = mutableMapOf<String, OnlineSearchResult>()
        val lock = Mutex()
        val permits = Semaphore(2)
        var checked = 0
        val complete = withTimeoutOrNull(30_000) {
            coroutineScope {
                val online = songs.mapNotNull { song -> OnlineTrackRef.parse(song.path)?.let { song to it } }
                online.filter { it.second.platform == "wy" }.chunked(100).forEach { batch -> launch {
                    permits.withPermit {
                        try {
                            withTimeoutOrNull(5_000) {
                                val ids = batch.mapNotNull { (_, ref) ->
                                    val info = Json.parseToJsonElement(ref.musicInfoJson).jsonObject
                                    info.str("songmid")?.toLongOrNull() ?: info.str("id")?.toLongOrNull()
                                }
                                if (ids.isEmpty()) return@withTimeoutOrNull
                                val response = http.parseObject(http.getText("https://music.163.com/api/song/detail/?ids=%5B${ids.joinToString(",")}%5D", referer = "https://music.163.com/"))
                                val details = response.arr("songs").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseWyTrack) }
                                lock.withLock { details.forEach { found["online:wy:${it.songId}"] = it }; checked += batch.size }
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                } }
                online.filter { it.second.platform != "wy" }.forEach { (song, ref) -> launch {
                    permits.withPermit {
                        try {
                            withTimeoutOrNull(4_000) {
                                val detail = if (ref.platform != "tx") null else try { withTimeoutOrNull(1_500) {
                                    val info = Json.parseToJsonElement(ref.musicInfoJson).jsonObject
                                    val mid = info.str("songmid") ?: return@withTimeoutOrNull null
                                    val body = buildJsonObject {
                                        put("req", buildJsonObject {
                                            put("module", "music.pf_song_detail_svr"); put("method", "get_song_detail_yqq")
                                            put("param", buildJsonObject { put("song_mid", mid); put("song_type", 0) })
                                        })
                                    }
                                    http.parseObject(http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), "https://y.qq.com/"))
                                        .obj("req")?.obj("data")?.obj("track_info")?.let(::parseTxTrack)
                                        ?.takeIf { OnlineTrackIdentity.matches(ref, it.toTrackRef(ref.sourceId)) }
                                } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                                val result = detail ?: search.search(ref.platform, "${song.title} ${song.artist.orEmpty()}", pageSize = 20).results
                                    .firstOrNull { OnlineTrackIdentity.matches(ref, it.toTrackRef(ref.sourceId)) }
                                lock.withLock { if (result != null) found[song.id] = result; checked++ }
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                } }
            }
            true
        } ?: false
        return MetadataRepairResult(songs.map { song -> found[song.id]?.let { fillMissingSongMetadata(song, it) } ?: song }, checked, !complete)
    }
}
