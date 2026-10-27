package com.muses.player.core.model

import com.muses.player.core.data.repository.FavoriteSelection
import com.muses.player.core.data.repository.FavoriteSongsState
import com.muses.player.core.model.download.DownloadTrack
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteSongsIdentityTest {
    private val saved = Song("local", "library", "/music/test.flac", "测试歌曲", "测试歌手（Example Artist）")
    private val online = saved.copy(id = "online", sourceId = "wy", artist = "Example Artist", sourceType = SourceType.ONLINE)

    @Test fun `顿号与斜杠跨平台显示同一喜欢状态且可互相取消`() {
        val local = saved.copy(artist = "歌手甲、歌手乙")
        val other = online.copy(artist = "歌手甲/歌手乙")
        val library = FavoriteSongsState(library = listOf(local), loaded = true)
        assertFalse(library.isLiked(other))
        assertTrue(library.isInLibrary(other))
        val liked = FavoriteSelection(DownloadTrack.from(local), true)
        assertTrue(FavoriteSongsState(selections = listOf(liked)).isLiked(other))
        val cancelled = FavoriteSelection(DownloadTrack.from(other), false)
        assertFalse(FavoriteSongsState(selections = listOf(liked, cancelled)).isLiked(local))
        assertFalse(library.isLiked(other.copy(artist = "歌手甲/歌手丙")))
        assertFalse(library.isLiked(other.copy(artist = "歌手甲")))
    }

    @Test fun `曲库存在同曲不自动标记喜欢`() {
        val state = FavoriteSongsState(library = listOf(saved), loaded = true)
        assertFalse(state.isLiked(online))
        assertTrue(state.isInLibrary(online))
        assertFalse(state.isLiked(online.copy(artist = "另一歌手")))
    }

    @Test fun `手动喜欢和取消喜欢使用同一套别名规则`() {
        val liked = FavoriteSelection(DownloadTrack.from(saved), true)
        assertTrue(FavoriteSongsState(selections = listOf(liked)).isLiked(online))
        val cancelled = FavoriteSelection(DownloadTrack.from(online), false)
        assertFalse(FavoriteSongsState(selections = listOf(liked, cancelled)).isLiked(saved))
    }
}
