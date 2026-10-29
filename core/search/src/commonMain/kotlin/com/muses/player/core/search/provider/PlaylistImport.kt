package com.muses.player.core.search.provider

import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.http.SearchHttp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ImportedPlaylist(val name: String, val songs: List<OnlineSearchResult>)
data class PlaylistAddress(val platform: String, val id: String)

/** 仅识别受支持的平台分享地址，不请求用户输入的任意链接。 */
fun parsePlaylistAddress(input: String): PlaylistAddress {
    val text = input.trim()
    val wy = Regex("(?:https?://)?(?:music\\.163\\.com|y\\.music\\.163\\.com)/(?:[^\\s]*?)(?:playlist[^\\s]*?[?&]id=)(\\d+)", RegexOption.IGNORE_CASE).find(text)
    if (wy != null) return PlaylistAddress("wy", wy.groupValues[1])
    val qq = Regex("(?:https?://)?(?:y\\.qq\\.com|i\\.y\\.qq\\.com)/(?:[^\\s]*?)(?:playlist/|disstid=|id=)(\\d+)", RegexOption.IGNORE_CASE).find(text)
    if (qq != null) return PlaylistAddress("tx", qq.groupValues[1])
    throw IllegalArgumentException("请粘贴网易云音乐或 QQ 音乐公开歌单的完整链接")
}

class PlaylistImportService(private val http: SearchHttp = SearchHttp()) {
    suspend fun load(address: PlaylistAddress): ImportedPlaylist = when (address.platform) {
        "wy" -> loadWy(address.id)
        "tx" -> loadQq(address.id)
        else -> throw IllegalArgumentException("暂不支持该歌单平台")
    }

    private suspend fun loadWy(id: String): ImportedPlaylist {
        require(id.toLongOrNull() != null) { "歌单编号无效" }
        val root = http.parseObject(http.getText("https://music.163.com/api/v6/playlist/detail?id=$id", referer = "https://music.163.com/"))
        val info = root.obj("playlist") ?: root.obj("result") ?: error("无法读取歌单，请确认歌单公开且链接有效")
        val name = info.str("name")?.takeIf { it.isNotBlank() } ?: error("平台未返回歌单名称")
        val ids = info.arr("trackIds")?.mapNotNull { (it as? JsonObject)?.long("id") }
            ?: error("平台未返回完整歌曲列表，请稍后重试")
        val count = info.long("trackCount") ?: error("平台未返回歌曲总数，请稍后重试")
        require(count == ids.size.toLong() && ids.isNotEmpty()) { "平台返回的歌单不完整或没有歌曲，请检查歌单权限" }
        require(ids.size <= 5000) { "歌单超过 5000 首，请分成多个歌单导入" }
        val found = mutableMapOf<Long, OnlineSearchResult>()
        for (batch in ids.distinct().chunked(100)) {
            val detail = http.parseObject(http.getText("https://music.163.com/api/song/detail/?ids=%5B${batch.joinToString(",")}%5D", referer = "https://music.163.com/"))
            val entries = detail.arr("songs") ?: error("歌曲详情读取失败，请稍后重试")
            for (entry in entries) {
                val item = entry as? JsonObject ?: continue
                val song = parseWyTrack(item) ?: continue
                found[song.songId.toLong()] = song
            }
        }
        require(ids.all { it in found }) { "部分歌曲无法读取，未导入歌单，请检查权限后重试" }
        return ImportedPlaylist(name, ids.map { found.getValue(it) })
    }

    private suspend fun loadQq(id: String): ImportedPlaylist {
        val number = id.toLongOrNull() ?: throw IllegalArgumentException("歌单编号无效")
        var offset = 0
        var total: Int? = null
        var name: String? = null
        val songs = mutableListOf<OnlineSearchResult>()
        while (total == null || offset < total) {
            val body = buildJsonObject {
                put("comm", buildJsonObject { put("ct", 24); put("cv", 0) })
                put("req", buildJsonObject {
                    put("module", "music.srfDissInfo.aiDissInfo"); put("method", "uniform_get_Dissinfo")
                    put("param", buildJsonObject { put("disstid", number); put("enc_host_uin", ""); put("tag", 1); put("userinfo", 1); put("song_begin", offset); put("song_num", 50) })
                })
            }.toString()
            val root = http.parseObject(http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, "https://y.qq.com/"))
            val request = root.obj("req") ?: error("QQ 音乐未返回歌单，请确认歌单公开")
            if ((request.long("code") ?: 0L) != 0L) error("QQ 音乐无法读取歌单，请检查歌单权限")
            val info = request.obj("data") ?: error("QQ 音乐歌单结构异常")
            val directory = info.obj("dirinfo") ?: info
            name = name ?: directory.str("dissname") ?: directory.str("disstname") ?: directory.str("title")
            total = total ?: (directory.long("total_song_num") ?: directory.long("songnum") ?: directory.long("total"))?.toInt()
                ?: error("平台未返回歌曲总数，请稍后重试")
            require(total in 1..5000) { "歌单为空或超过 5000 首" }
            val page = info.arr("songlist") ?: error("平台未返回歌曲列表")
            require(page.isNotEmpty()) { "平台返回的歌单不完整，请稍后重试" }
            for (entry in page) {
                val raw = entry as? JsonObject ?: error("平台返回的歌曲信息不完整")
                songs += parseTxTrack(raw) ?: error("部分歌曲缺少标识或名称，未导入")
            }
            offset += page.size
            require(offset <= total) { "平台返回的歌单数量异常，请稍后重试" }
        }
        return ImportedPlaylist(name?.takeIf { it.isNotBlank() } ?: error("平台未返回歌单名称"), songs)
    }
}
