package com.muses.player.core.search.provider

import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineChartException
import com.muses.player.core.search.OnlineChartPage
import com.muses.player.core.search.OnlineChartProvider
import com.muses.player.core.search.OnlineSearchResult
import com.muses.player.core.search.http.SearchHttp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * QQ 音乐排行榜。
 *
 * 复用搜索同款 host（`u.y.qq.com/cgi-bin/musicu.fcg`，桌面端 UA + `y.qq.com` Referer，均无需签名）：
 * - 榜单列表：`musicToplist.ToplistInfoServer/GetAll` → `req.data.group[].toplist[]`
 * - 榜单歌曲：`musicToplist.ToplistInfoServer/GetDetail`（`topId` + `offset`/`num`）→ `req.data.data.songInfoList[]`
 *
 * 实测：两跳均匿名可用（榜单约 20 个，`topId` 如 62=飙升榜）。
 * 歌曲标识取 `mid`（QQ 源脚本通用字段）。
 */
class QqChartProvider(
    private val http: SearchHttp = SearchHttp(),
) : OnlineChartProvider {

    override val platform: String = PLATFORM_TX
    override val displayName: String = "QQ音乐"

    private val host = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    override suspend fun charts(): List<OnlineChart> {
        val data = buildJsonObject {
            put("comm", buildJsonObject { put("ct", 24); put("cv", 0) })
            put("req", buildJsonObject {
                put("method", "GetAll")
                put("module", "musicToplist.ToplistInfoServer")
                put("param", buildJsonObject { })
            })
        }.toString()

        val text = try {
            // POST JSON：QQ 服务端 PC 端同口径；嵌套 JSON 走 query 参数实测会拿不到数据
            http.postJson(url = host, body = data, referer = "https://y.qq.com/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineChartException(platform, "QQ音乐榜单列表请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        val groups = root.path("req", "data")?.arr("group")
            ?: throw OnlineChartException(platform, "QQ音乐榜单列表结构异常")

        return groups.flatMap { groupElement ->
            val group = groupElement as? JsonObject ?: return@flatMap emptyList()
            val topLists = group.arr("toplist") ?: return@flatMap emptyList()
            topLists.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val topId = item.long("topId") ?: return@mapNotNull null
                val title = item.str("title") ?: return@mapNotNull null
                OnlineChart(
                    platform = platform,
                    chartId = topId.toString(),
                    name = title,
                    // cover 在不同版本里是对象或字符串，两种都兜
                    coverUrl = item.obj("cover")
                        ?.let { c -> c.str("defaultUrl") ?: c.str("url") ?: c.str("smallUrl") }
                        ?: item.str("cover"),
                    updateInfo = item.str("period") ?: item.str("titleDetail"),
                )
            }
        }.distinctBy { it.chartId }
    }

    override suspend fun chartSongs(chartId: String, page: Int, pageSize: Int): OnlineChartPage {
        // topId 在请求体里是数字：先校验再拼，避免把非法值当 JSON 注入
        val topId = chartId.toLongOrNull()
            ?: throw OnlineChartException(platform, "QQ音乐榜单标识异常：$chartId")
        val offset = (page - 1).coerceAtLeast(0) * pageSize

        val data = buildJsonObject {
            put("comm", buildJsonObject { put("ct", 24); put("cv", 0) })
            put("req", buildJsonObject {
                put("method", "GetDetail")
                put("module", "musicToplist.ToplistInfoServer")
                put("param", buildJsonObject {
                    put("topId", topId)
                    put("offset", offset)
                    put("num", pageSize)
                })
            })
        }.toString()

        val text = try {
            // POST JSON：同上，避免 GET query 携带嵌套 JSON
            http.postJson(url = host, body = data, referer = "https://y.qq.com/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineChartException(platform, "QQ音乐榜单歌曲请求失败：${e.message}", e)
        }

        val root = http.parseObject(text)
        // ⚠️ 字段层级（实测）：songInfoList 与榜单元信息 data 是**兄弟**，均在 req.data 下；
        // 不是 req.data.data.songInfoList。totalNum 也在同级，用于 hasMore。
        val container = root.path("req", "data")
            ?: throw OnlineChartException(platform, "QQ音乐榜单歌曲结构异常")
        val list = container.arr("songInfoList")
            ?: return OnlineChartPage(platform, chartId, page, emptyList(), false)

        val results = list.mapNotNull { (it as? JsonObject)?.let(::parseTxTrack) }
        val hasMore = container.long("totalNum")?.let { offset + results.size < it } ?: (results.size >= pageSize)
        return OnlineChartPage(platform, chartId, page, results, hasMore)
    }
}
