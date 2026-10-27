package com.muses.player.navigation

import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 顶层导航项：探索/歌单/我的，搜索由探索顶部进入；原曲库由“我的”进入，
 * 我的页面提供音源/刮削/统计/历史记录/设置。
 *
 * 路由本体已类型化（[MusesRoute]，miuix-nav @Serializable 密封层级）；
 * 本 enum 只保留展示层（label/icon）与分组/激活判定：
 * - [routeKey]：tab 本体 key（详情页激活由 [isActive] 按 key 类型判定，对照 Web 层 childPrefixes）
 * - [isActive]：对照 TabsPage.vue 的 isNavActive
 *
 * U22 上收：labelRes（安卓资源 id）改为共享文案常量（工程 UI 文案统一中文硬编码，
 * 与 commonMain 各 Page 层一致，避免引入 CMP 资源基建）。
 */
enum class NavDestination(
    val routeKey: MusesRoute,
    val label: String,
    /** Web 层同款语义的图标（括号内为 Tabler 原名） */
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    // ---- 探索（U27 新增：搜索框 + 排行榜 + 猜你喜欢，启动默认页）----
    Home(MusesRoute.Home, "探索", TablerIcons.Compass),
    Search(MusesRoute.OnlineSearch(), "搜索", TablerIcons.Search),

    // ---- 主菜单（曲库）：primaryNavItems = navItems.slice(0, 4) ----
    Songs(MusesRoute.Songs, "歌单", TablerIcons.QueueMusic),
    Albums(MusesRoute.Albums, "专辑", TablerIcons.Album),               // albums (Disc)
    Artists(MusesRoute.Artists, "艺术家", TablerIcons.Person),          // user (MicVocal)

    // ---- 我的与工具 ----
    Scrape(MusesRoute.Scrape, "刮削", TablerIcons.Checklist),          // listCheck
    Mine(MusesRoute.Mine, "我的", TablerIcons.Person),
    ;

    /** 对照 TabsPage.vue 的 isNavActive(item)：tab 本体 + 其详情/子页均算激活 */
    fun isActive(key: NavKey?): Boolean = when (this) {
        Home -> key == MusesRoute.Home || key is MusesRoute.HomeCollection ||
            key is MusesRoute.ChartDetail || key is MusesRoute.PlaylistDetail || key is MusesRoute.OnlineSearch
        Search -> key is MusesRoute.OnlineSearch
        Songs -> key == MusesRoute.Songs || key is MusesRoute.UserCollection
        Albums -> key == MusesRoute.Albums || key is MusesRoute.AlbumDetail
        Artists -> key == MusesRoute.Artists || key is MusesRoute.ArtistDetail
        Scrape -> key == MusesRoute.Scrape || key is MusesRoute.ScrapeReview
        Mine -> key == MusesRoute.Mine || key == MusesRoute.Settings ||
            key == MusesRoute.LocalLibrary || key == MusesRoute.Albums || key == MusesRoute.Artists ||
            key is MusesRoute.AlbumDetail || key is MusesRoute.ArtistDetail ||
            key == MusesRoute.Downloads ||
            key == MusesRoute.Statistics || key == MusesRoute.History ||
            key is MusesRoute.AiSettings ||
            key == MusesRoute.Sources || key is MusesRoute.WebDavAdd ||
            key is MusesRoute.WebDavEdit ||
            key is MusesRoute.LxScripts || key == MusesRoute.Scrape ||
            key is MusesRoute.ScrapeReview
    }

    companion object {
        /** 主导航组（探索与歌单），工具由“我的”进入。 */
        val Primary: List<NavDestination> = listOf(Home, Songs)

        /** 工具与我的入口 */
        val Secondary: List<NavDestination> = listOf(Scrape, Mine)
    }
}

/** 从最近的明确来源推断共享详情页归属，返回时自然恢复原栏；兼容旧恢复栈。 */
internal fun activeDestination(stack: List<NavKey>): NavDestination {
    val shared: (NavKey) -> Boolean = {
        it is MusesRoute.AlbumDetail || it is MusesRoute.ArtistDetail || it == MusesRoute.Downloads
    }
    val key = stack.asReversed().firstOrNull { !shared(it) }
    return when {
        NavDestination.Songs.isActive(key) -> NavDestination.Songs
        NavDestination.Home.isActive(key) -> NavDestination.Home
        key != null -> NavDestination.Mine
        else -> NavDestination.Mine
    }
}
