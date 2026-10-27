package com.muses.player.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 类型化路由（miuix-nav）：密封接口 + @Serializable data 路由。
 *
 * 对照原 string 路由表（NavDestination.route / DetailRoutes）一比一映射：
 * - 顶层 tabs：Home/Songs/Mine（无参 data object）
 * - 详情/表单：AlbumDetail/ArtistDetail/WebDavEdit（单 id 参数）
 * - 刮削审核：ScrapeReview(songId + queueCsv，逗号分隔队列上下文，对照原 URL query）
 * - WebDAV 浏览：WebDavBrowse（连接信息直传字段，原 URLEncoder query 入参）
 *
 * 约束（miuix-nav 存栈恢复硬性要求）：路由类必须 @Serializable data（值语义 toString），
 * 同栈内相同值 key 禁止重复 push（调用方 navigateToTab/pushUnique 负责幂等）。
 */
@Serializable
sealed interface MusesRoute : NavKey {
    /** 探索首页：主题横幅、发现入口与音乐分类（启动默认页）。 */
    @Serializable
    data object Home : MusesRoute

    /** 首页入口打开的推荐歌曲或榜单目录。 */
    @Serializable
    data class HomeCollection(val recommendations: Boolean) : MusesRoute

    @Serializable
    data class PlaylistDetail(val platform: String, val playlistId: String, val title: String) : MusesRoute

    /** 排行榜歌曲详情页（从探索页选择具体榜单进入）。 */
    @Serializable
    data class ChartDetail(
        val platform: String,
        val chartId: String,
        val chartName: String,
    ) : MusesRoute

    @Serializable
    data object Mine : MusesRoute

    @Serializable
    data object Downloads : MusesRoute

    // ---- 主菜单（曲库）----
    @Serializable
    data object Songs : MusesRoute

    /** 原本地与 WebDAV 曲库，由“我的”进入。 */
    @Serializable
    data object LocalLibrary : MusesRoute

    /** 我喜欢、已下载及自建歌单共用详情路由。 */
    @Serializable
    data class UserCollection(val collectionId: String) : MusesRoute

    @Serializable
    data object Albums : MusesRoute

    @Serializable
    data object Artists : MusesRoute

    @Serializable
    data class AlbumDetail(val albumId: String) : MusesRoute

    @Serializable
    data class ArtistDetail(val artistId: String) : MusesRoute

    // ---- 次菜单（工具）----
    @Serializable
    data object Scrape : MusesRoute

    /**
     * 刮削审核页（songId 必传、queueCsv 可选逗号分隔队列上下文，供「应用并下一首」推进）。
     * 对照原 `"scrape_review?songId={songId}&queue={queue}"`（见旧 DetailRoutes.scrapeReview）。
     */
    @Serializable
    data class ScrapeReview(val songId: String, val queueCsv: String = "") : MusesRoute

    @Serializable
    data object Sources : MusesRoute

    /** 在线音源脚本管理（洛雪自定义源） */
    @Serializable
    data object LxScripts : MusesRoute

    /** 添加洛雪在线音源脚本。 */
    @Serializable
    data object LxSourceAdd : MusesRoute

    /** 编辑洛雪在线音源脚本。 */
    @Serializable
    data class LxSourceEdit(val sourceId: String) : MusesRoute

    /**
     * 在线搜索（各平台官方接口）。
     *
     * [keyword] 非空时进入即自动搜索；一般由搜索主导航按钮进入空关键词页面。
     */
    @Serializable
    data class OnlineSearch(val keyword: String = "") : MusesRoute

    @Serializable
    data object WebDavAdd : MusesRoute

    @Serializable
    data class WebDavEdit(val sourceId: String) : MusesRoute

    @Serializable
    data object Settings : MusesRoute

    @Serializable
    data object Statistics : MusesRoute

    @Serializable
    data object History : MusesRoute

    /** AI 服务配置二级页（设置页「AI 推荐」→ 箭头进入：地址/模型/Key） */
    @Serializable
    data object AiSettings : MusesRoute

    @Serializable
    data object SyncSettings : MusesRoute
}
