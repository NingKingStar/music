package com.muses.player.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class NavOriginTest {
    @Test fun `搜索的专辑详情仍高亮探索`() {
        assertEquals(NavDestination.Home, activeDestination(listOf(MusesRoute.Home, MusesRoute.OnlineSearch(), MusesRoute.AlbumDetail("a"))))
    }
    @Test fun `歌单的下载管理仍高亮歌单`() {
        assertEquals(NavDestination.Songs, activeDestination(listOf(MusesRoute.Songs, MusesRoute.UserCollection("downloaded"), MusesRoute.Downloads)))
    }
    @Test fun `我的入口和回退后恢复原来的栏`() {
        assertEquals(NavDestination.Mine, activeDestination(listOf(MusesRoute.Mine, MusesRoute.Downloads)))
        assertEquals(NavDestination.Mine, activeDestination(listOf(MusesRoute.Mine, MusesRoute.LocalLibrary, MusesRoute.ArtistDetail("a"))))
        assertEquals(NavDestination.Songs, activeDestination(listOf(MusesRoute.Songs, MusesRoute.UserCollection("x"))))
    }
}
