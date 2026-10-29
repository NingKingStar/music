package com.muses.player.core.search.provider

import com.muses.player.core.search.OnlineSearchException
import com.muses.player.core.search.OnlineSearchPage
import com.muses.player.core.search.OnlineSearchProvider
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.http.SearchHttp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * QQ 音乐搜索。
 *
 * 接口：`https://u.y.qq.com/cgi-bin/musicu.fcg`（POST JSON，**当前无需 sign 签名**）
 * 请求体：`{"comm":{...},"req":{"method":"DoSearchForQQMusicDesktop",...}}`
 *
 * 响应：`req.data.body.song.list[]`，标识字段 `mid`。
 *
 * 注意：该接口对 Referer 敏感，缺失会返回空列表。
 */
class TxSearchProvider(
    private val http: SearchHttp = SearchHttp(),
) : OnlineSearchProvider {

    override val platform: String = PLATFORM_TX
    override val displayName: String = "QQ音乐"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
        val body = buildJsonObject {
            put("comm", buildJsonObject {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            })
            put("req", buildJsonObject {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                put("param", buildJsonObject {
                    put("num_per_page", pageSize)
                    put("page_num", page)
                    put("query", keyword)
                    put("search_type", 0)
                })
            })
        }.toString()

        val text = try {
            http.postJson(
                url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
                body = body,
                referer = "https://y.qq.com/",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineSearchException(platform, "QQ音乐搜索请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        val songNode = root.path("req", "data", "body", "song")
            ?: throw OnlineSearchException(platform, "QQ音乐返回结构异常")
        val list = songNode.arr("list") ?: return OnlineSearchPage(platform, keyword, page, emptyList(), false)

        val results = list.mapNotNull { (it as? JsonObject)?.let(::parseTxTrack) }
        val totalNum = songNode.long("totalnum")
        val hasMore = when {
            totalNum != null && totalNum > 0 -> page.toLong() * pageSize < totalNum
            else -> results.size >= pageSize
        }
        return OnlineSearchPage(platform, keyword, page, results, hasMore)
    }
}

/**
 * 酷狗音乐搜索。
 *
 * 接口：`https://songsearch.kugou.com/song_search_v2`（GET，无需签名）
 * 参数：`keyword`、`page`、`pagesize`、`platform=WebFilter`、`format=json`
 *
 * 响应：`data.lists[]`，标识字段 `FileHash`（脚本侧通常读 `hash`）。
 *
 * 注：旧的 `mobilecdn.kugou.com/api/v3/search/song` 已失效，勿用。
 */
class KgSearchProvider(
    private val http: SearchHttp = SearchHttp(),
) : OnlineSearchProvider {

    override val platform: String = PLATFORM_KG
    override val displayName: String = "酷狗音乐"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
        val url = "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=${urlEncode(keyword)}" +
            "&page=$page&pagesize=$pageSize&platform=WebFilter&format=json"

        val text = try {
            http.getText(url, referer = "https://www.kugou.com/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineSearchException(platform, "酷狗搜索请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        val data = root.obj("data") ?: throw OnlineSearchException(platform, "酷狗返回结构异常")
        val list = data.arr("lists") ?: return OnlineSearchPage(platform, keyword, page, emptyList(), false)

        val results = list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            // 优先无损/320k 的 hash（音质更好），退回默认
            val hash = item.str("SQFileHash")?.takeIf { it.isNotBlank() && it != "0" }
                ?: item.str("HQFileHash")?.takeIf { it.isNotBlank() && it != "0" }
                ?: item.str("FileHash")
                ?: return@mapNotNull null
            val name = item.str("SongName") ?: return@mapNotNull null
            val artist = item.str("SingerName")
            val album = item.str("AlbumName")
            // 各音质带各自的 hash 与时长，一并冗余写入供脚本按音质取用
            val durationSec = item.long("SQDuration") ?: item.long("HQDuration") ?: item.long("Duration")
            OnlineSearchResult(
                platform = platform,
                songId = hash,
                name = name,
                artist = artist,
                album = album,
                durationMs = durationSec?.times(1000),
                // 封面：实测 `AlbumImage` 恒为空串，真正有值的是 `Image`（且带 `{size}` 占位符）
                coverUrl = (item.str("AlbumImage") ?: item.str("Image"))
                    ?.replace("{size}", "240")
                    ?.takeIf { it.startsWith("http") },
                musicInfoJson = buildMusicInfo(
                    "hash" to hash,
                    "songmid" to hash,
                    name = name,
                    singer = artist,
                    album = album,
                    durationSec = durationSec,
                    // 各音质 hash 与专辑 id 显式给出（酷狗脚本按音质取对应 hash）
                    extra = buildMap {
                        putAll(catalogMetadata(platform, item))
                        item.str("SQFileHash")?.takeIf { it.isNotBlank() && it != "0" }
                            ?.let { put("sqhash", it); put("SQFileHash", it) }
                        item.str("HQFileHash")?.takeIf { it.isNotBlank() && it != "0" }
                            ?.let { put("hqhash", it); put("HQFileHash", it) }
                        item.str("AlbumID")?.takeIf { it.isNotBlank() && it != "0" }
                            ?.let { put("albumId", it); put("album_id", it) }
                    },
                ),
            )
        }
        val total = data.long("total")
        val hasMore = when {
            total != null && total > 0 -> page.toLong() * pageSize < total
            else -> results.size >= pageSize
        }
        return OnlineSearchPage(platform, keyword, page, results, hasMore)
    }

}

/**
 * 网易云音乐搜索。
 *
 * 接口：`https://music.163.com/api/search/get`（GET，**明文接口，绕过 weapi 加密**）
 * 参数：`s`=关键词、`type=1`(单曲)、`offset`、`limit`
 *
 * 响应：`result.songs[]`，标识字段 `id`。需要 Referer，否则 403。
 */
class WySearchProvider(
    private val http: SearchHttp = SearchHttp(),
) : OnlineSearchProvider {

    override val platform: String = PLATFORM_WY
    override val displayName: String = "网易云音乐"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val url = "https://music.163.com/api/search/get" +
            "?s=${urlEncode(keyword)}&type=1&offset=$offset&limit=$pageSize&total=true"

        val text = try {
            http.getText(url, referer = "https://music.163.com/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineSearchException(platform, "网易云搜索请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        val code = root.long("code")
        if (code != null && code != 200L) {
            throw OnlineSearchException(platform, "网易云搜索暂时不可用（code=$code），请稍后重试")
        }
        val resultNode = root.obj("result") ?: throw OnlineSearchException(platform, "网易云返回结构异常")
        val songs = resultNode.arr("songs")
            ?: return OnlineSearchPage(platform, keyword, page, emptyList(), false)

        val results = songs.mapNotNull { (it as? JsonObject)?.let(::parseWyTrack) }
        val total = resultNode.long("songCount")
        val hasMore = when {
            total != null && total > 0 -> offset + results.size < total
            else -> results.size >= pageSize
        }
        return OnlineSearchPage(platform, keyword, page, com.muses.player.core.search.enrichWySongVersions(http, results), hasMore)
    }
}

/**
 * 咪咕音乐搜索。
 *
 * 接口：`https://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do`（GET，无需签名）
 * 参数：`text`=关键词、`pageNo`、`pageSize`、`searchSwitch`（URL 编码的 JSON）
 *
 * 响应：`songResultData.result[]`，标识字段 `copyrightId`（脚本侧常读 `copyrightId`）。
 *
 * 注：该接口 `pageSize` 有上限（超过约 20 会被截断），故实现内做钳制。
 */
class MgSearchProvider(
    private val http: SearchHttp = SearchHttp(),
) : OnlineSearchProvider {

    override val platform: String = PLATFORM_MG
    override val displayName: String = "咪咕音乐"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
        // 实测（pageSize=3/5/20/30 均返回 20 条）：咪咕**忽略 pageSize**，固定每页 20 条。
        // 故分页单位硬编码 20，忽略调用方传入值 —— 否则页码偏移会错位（page2 会跳掉数据）。
        val url = "https://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do" +
            "?ua=Android_migu&version=5.0.1" +
            "&text=${urlEncode(keyword)}" +
            "&pageNo=$page&pageSize=$MG_PAGE_SIZE&searchSwitch=$MG_SEARCH_SWITCH"

        val text = try {
            http.getText(url, referer = "https://m.music.migu.cn/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineSearchException(platform, "咪咕搜索请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        val data = root.obj("songResultData") ?: throw OnlineSearchException(platform, "咪咕返回结构异常")
        val list = data.arr("result") ?: return OnlineSearchPage(platform, keyword, page, emptyList(), false)

        val results = list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            // copyrightId 是脚本侧通用标识；缺失时退回 id
            val copyrightId = item.str("copyrightId")?.takeIf { it.isNotBlank() && it != "0" }
                ?: item.str("id")
                ?: return@mapNotNull null
            val name = item.str("name") ?: return@mapNotNull null
            val artist = item.arr("singers")?.joinStr("name")
            val album = item.arr("albums")?.firstStr("name")
            OnlineSearchResult(
                platform = platform,
                songId = copyrightId,
                name = name,
                artist = artist,
                album = album,
                // 咪咕搜索响应不提供时长字段
                durationMs = null,
                coverUrl = item.arr("imgItems")?.firstStr("img")?.takeIf { it.startsWith("http") },
                musicInfoJson = buildMusicInfo(
                    "copyrightId" to copyrightId,
                    "id" to item.str("id"),
                    name = name,
                    singer = artist,
                    album = album,
                    extra = catalogMetadata(platform, item),
                ),
            )
        }
        val total = data.str("totalCount")?.toLongOrNull()
        val hasMore = when {
            total != null && total > 0 -> page.toLong() * MG_PAGE_SIZE < total
            else -> results.size >= MG_PAGE_SIZE
        }
        return OnlineSearchPage(platform, keyword, page, results, hasMore)
    }

    private companion object {
        /** 咪咕固定每页 20 条（服务端忽略 pageSize 参数） */
        const val MG_PAGE_SIZE = 20
        val MG_SEARCH_SWITCH: String = urlEncode(
            """{"song":1,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":1}""",
        )
    }
}
