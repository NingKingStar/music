package com.muses.player.core.search

import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineArtistRef
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.muses.player.core.data.store.platformNowMs

@Serializable
data class ArtistSongPage(val songs: List<OnlineSearchResult>, val page: Int, val hasMore: Boolean)
data class ArtistResolution(val artists: List<OnlineArtistRef>, val confirmed: Boolean)

/** 目录服务独立于音源脚本；只加载进入页面需要的分页，不解析播放直链。 */
class OnlineArtistService(private val http: SearchHttp = SearchHttp()) {
    private data class Entry(val page: ArtistSongPage, val time: Long)
    private val pages = LinkedHashMap<String, Entry>()
    private val locks = KeyedRequestLock<String>()

    suspend fun resolve(song: Song): ArtistResolution = withTimeout(12_000) {
        val ref = OnlineTrackRef.parse(song.path)
        val info = ref?.let { runCatching { Json.parseToJsonElement(it.musicInfoJson).jsonObject }.getOrNull() }
        val saved = info?.str("artistRefs")?.let { runCatching { Json.decodeFromString<List<OnlineArtistRef>>(it) }.getOrNull() }.orEmpty()
            .filter { validArtist(it) }.distinctBy { it.platform to it.id }
        if (saved.isNotEmpty()) return@withTimeout ArtistResolution(saved, true)
        if (ref != null && info != null) {
            try {
                val track = withTimeoutOrNull(3_500) { when (ref.platform) {
                    "wy" -> info.str("id")?.takeIf { it.toLongOrNull() != null }?.let { id ->
                        http.parseObject(http.getText("https://music.163.com/api/song/detail/?id=$id&ids=[$id]", "https://music.163.com/"))
                            .arr("songs")?.firstOrNull()?.jsonObject?.let(::parseWyTrack)
                    }
                    "tx" -> (info.str("songmid") ?: info.str("mid"))?.let { mid ->
                        val body = qqBody("music.pf_song_detail_svr", "get_song_detail_yqq", buildJsonObject { put("song_mid", mid); put("song_type", 0) })
                        http.parseObject(http.postJson(QQ, body, "https://y.qq.com/"))
                            .obj("req")?.obj("data")?.obj("track_info")?.let(::parseTxTrack)
                    }
                    else -> null
                } }
                // 旧歌单可能只保留 id；新解析结果补有 songmid 等别名，不能因字段名不同拒绝同一平台编号。
                val requestedId = when (ref.platform) {
                    "wy" -> info.str("id")
                    "tx" -> info.str("songmid") ?: info.str("mid")
                    else -> null
                }
                val identities = track?.takeIf { it.platform == ref.platform && it.songId == requestedId }
                    ?.let { readArtists(it.musicInfoJson) }.orEmpty()
                if (identities.isNotEmpty()) return@withTimeout ArtistResolution(identities, true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val name = song.artist?.trim().orEmpty()
        require(name.isNotBlank() && name != "未知艺术家") { "这首歌曲暂缺歌手信息" }
        var candidates = emptyList<OnlineArtistRef>()
        try {
            val root = http.parseObject(http.getText("https://music.163.com/api/search/get?s=${urlEncode(name)}&type=100&limit=10&offset=0", "https://music.163.com/"))
            candidates = root.obj("result")?.arr("artists").orEmpty().mapNotNull { item ->
                val artist = item as? JsonObject ?: return@mapNotNull null
                artist.str("id")?.let { OnlineArtistRef("wy", it, artist.str("name").orEmpty(), safeCover(artist.str("picUrl"))) }?.takeIf(::validArtist)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (candidates.isEmpty()) {
            val param = buildJsonObject { put("query", name); put("search_type", 1); put("page_num", 1); put("num_per_page", 10) }
            val root = http.parseObject(http.postJson(QQ, qqBody("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", param), "https://y.qq.com/"))
            candidates = root.obj("req")?.obj("data")?.obj("body")?.obj("singer")?.arr("list").orEmpty().mapNotNull { item ->
                val a = item as? JsonObject ?: return@mapNotNull null
                a.str("singerMID")?.let { OnlineArtistRef("tx", it, a.str("singerName").orEmpty(), safeCover(a.str("singerPic"))) }?.takeIf(::validArtist)
            }
        }
        // 仅凭同名不自动认定身份，交给用户选；避免同名歌手和乐队名被误判。
        ArtistResolution(candidates.distinctBy { it.platform to it.id }, false)
    }

    suspend fun songs(artist: OnlineArtistRef, page: Int = 1, force: Boolean = false): ArtistSongPage {
        require(validArtist(artist) && page in 1..1000) { "歌手或分页信息无效" }
        val key = "${artist.platform}:${artist.id}:$page"
        return locks.withKey(key) {
            val cached = synchronized(pages) { pages[key] }
            if (!force && cached != null && platformNowMs() - cached.time in 0..1_800_000) return@withKey cached.page
            val result = withTimeout(12_000) {
                if (artist.platform == "wy") {
                    val root = http.parseObject(http.getText("https://music.163.com/api/v1/artist/songs?id=${artist.id}&order=hot&offset=${(page-1)*30}&limit=30", "https://music.163.com/"))
                    require(root.long("code") == 200L) { "歌手目录暂不可用，请重试" }
                    val raw = root.arr("songs") ?: error("歌手目录返回不完整")
                    ArtistSongPage(raw.mapNotNull { (it as? JsonObject)?.let(::parseWyTrack) }.distinctBy { it.songId }, page,
                        root["more"]?.jsonPrimitive?.booleanOrNull ?: (raw.size >= 30))
                } else {
                    val param = buildJsonObject { put("sort", 5); put("singermid", artist.id); put("sin", (page-1)*30); put("num", 30) }
                    val root = http.parseObject(http.postJson(QQ, qqBody("music.web_singer_info_svr", "get_singer_detail_info", param), "https://y.qq.com/"))
                    val response = root.obj("req") ?: error("歌手目录返回不完整")
                    require(response.long("code") == 0L) { "歌手目录暂不可用，请重试" }
                    val data = response.obj("data") ?: error("歌手目录返回不完整")
                    val raw = data.arr("songlist") ?: error("歌手目录返回不完整")
                    ArtistSongPage(raw.mapNotNull { (it as? JsonObject)?.let(::parseTxTrack) }.distinctBy { it.songId }, page,
                        data.long("total_song")?.let { page * 30L < it } ?: (raw.size >= 30))
                }
            }
            synchronized(pages) { pages[key] = Entry(result, platformNowMs()); while (pages.size > 64) pages.remove(pages.keys.first()) }
            result
        }
    }
    companion object {
        private const val QQ = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        fun validArtist(a: OnlineArtistRef) = a.name.isNotBlank() && when(a.platform) {
            "wy" -> a.id.toLongOrNull()?.let { it > 0 } == true
            "tx" -> a.id.length in 1..64 && a.id.all { it.isLetterOrDigit() }
            else -> false
        }
        fun readArtists(info: String): List<OnlineArtistRef> = runCatching {
            val value = Json.parseToJsonElement(info).jsonObject.str("artistRefs") ?: return emptyList()
            Json.decodeFromString<List<OnlineArtistRef>>(value).filter(::validArtist).distinctBy { it.platform to it.id }
        }.getOrDefault(emptyList())
        private fun safeCover(url: String?): String? = url?.replace("http://", "https://")?.takeIf { it.startsWith("https://") }
        private fun qqBody(module: String, method: String, param: JsonObject): String = buildJsonObject {
            put("comm", buildJsonObject { put("ct", 24); put("cv", 0) })
            put("req", buildJsonObject { put("module", module); put("method", method); put("param", param) })
        }.toString()
    }
}
