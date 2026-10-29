package com.muses.player.core.search

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.online.OnlineTrackRef

/**
 * 统一在线搜索结果。
 *
 * 各平台字段差异大（songmid/hash/copyrightId…），本模型做归一；
 * [musicInfoJson] 是**给音源脚本用的原始字段包**，直接进 [OnlineTrackRef.musicInfoJson]。
 */
data class OnlineSearchResult(
    /** 平台 key（与洛雪源一致：kw/kg/tx/wy/mg） */
    val platform: String,
    /** 平台内歌曲标识（展示与去重用；具体字段名各平台不同） */
    val songId: String,
    val name: String,
    val artist: String?,
    val album: String?,
    /** 时长（毫秒）；平台不提供时为 null */
    val durationMs: Long?,
    val coverUrl: String?,
    /**
     * 构造播放引用用的音乐信息 JSON。
     *
     * 关键设计：**同一标识以多个常见字段名冗余写入**（songmid/id/hash/mid/copyrightId…）。
     * 原因：自定义源脚本由不同作者编写，读取的字段名不统一
     * （有的取 `songmid`、有的取 `hash`、有的取 `copyrightId`）。
     * 冗余提供可显著提高跨脚本兼容性，代价仅是 JSON 略大。
     */
    val musicInfoJson: String,
    /** 展示排序只重排同一页，后续分页不能把已看到的条目挤走。 */
    val searchPage: Int = 1,
) {
    /** 转为播放链路可直接消费的曲目引用 */
    fun toTrackRef(sourceId: String, quality: String? = null): OnlineTrackRef =
        OnlineTrackRef(
            platform = platform,
            musicInfoJson = musicInfoJson,
            sourceId = sourceId,
            quality = quality,
        )

    /**
     * 转为可入队播放的 [Song]（**不落库**，仅内存态）。
     *
     * - `id` 稳定可复现（`online:<platform>:<songId>`），保证同一首歌重复搜索/入队时去重正确；
     * - `path` 存 [OnlineTrackRef] 编码，播放时由解析器换取直链；
     * - `durationMs` 缺失时为 0（UI 显示为未知，不影响播放）。
     */
    fun toSong(sourceId: String, quality: String? = null): Song = Song(
        id = "online:$platform:$songId",
        sourceId = sourceId,
        path = toTrackRef(sourceId, quality).encode(),
        title = name,
        artist = artist,
        album = album,
        durationMs = durationMs ?: 0L,
        durationSec = (durationMs ?: 0L) / 1000L,
        // 封面为远程 URL，直接作为展示 URI（双端 Coil 均可加载 http）
        coverUri = coverUrl,
        sourceType = SourceType.ONLINE,
    )
}

/** 搜索分页结果 */
data class OnlineSearchPage(
    val platform: String,
    val keyword: String,
    /** 1-based 页码 */
    val page: Int,
    val results: List<OnlineSearchResult>,
    /** 是否还有下一页（平台未提供时为 null，由调用方按「返回条数 == pageSize」推断） */
    val hasMore: Boolean?,
)

/**
 * 单平台搜索 provider。
 *
 * 实现须自行处理各平台的请求参数差异（分页/编码/header）与响应字段映射；
 * 失败一律抛 [OnlineSearchException]，由聚合层归类展示。
 */
interface OnlineSearchProvider {
    /** 平台 key（kw/kg/tx/wy/mg），与洛雪源一致 */
    val platform: String

    /** 平台展示名（如「酷我音乐」） */
    val displayName: String

    /** 该平台是否需要 Cookie 才能搜索（部分平台匿名可用，标注用于 UI 提示） */
    val requiresCookie: Boolean get() = false

    /**
     * 搜索。
     * @param keyword 关键词（实现方负责 URL 编码）
     * @param page 1-based 页码
     * @param pageSize 每页条数
     */
    suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage
}

/** 搜索失败（网络/解析/平台拒绝）；聚合层会转成用户可读提示 */
class OnlineSearchException(
    val platform: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)