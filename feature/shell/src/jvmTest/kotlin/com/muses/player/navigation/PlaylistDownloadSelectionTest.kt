package com.muses.player.navigation

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.online.OnlineTrackRef
import kotlin.test.*

class PlaylistDownloadSelectionTest {
    private fun online(id: String) = Song("online:wy:$id", "online",
        OnlineTrackRef("wy", "{\"id\":\"$id\"}", "online", "flac").encode(), "歌曲$id", sourceType = SourceType.ONLINE)

    @Test fun `默认全选在线条目并去重但不把本地文件发给解析器`() {
        val first = online("1")
        val local = Song("local", "local", "/music/test.flac", "本地")
        val selection = PlaylistDownloadSelection.from(listOf(first, local, first, online("2")))
        assertEquals(listOf(first, online("2")), selection.selectedSongs)
        assertTrue(selection.allSelected)
    }
    @Test fun `勾选顺序不改变歌单顺序或在线解析身份`() {
        val songs = listOf(online("1"), online("2"), online("3"))
        val selection = PlaylistDownloadSelection.from(songs).toggleAll().toggle(songs[2].id).toggle(songs[0].id)
        assertEquals(listOf(songs[0], songs[2]), selection.selectedSongs)
        assertFalse(selection.allSelected)
    }
    @Test fun `部分选择后全选随后全不选且忽略不存在的条目`() {
        val initial = PlaylistDownloadSelection.from(listOf(online("1"), online("2")))
        val partial = initial.toggle(online("1").id)
        assertTrue(partial.toggleAll().allSelected)
        assertTrue(partial.toggleAll().toggleAll().selectedSongs.isEmpty())
        assertEquals(partial, partial.toggle("不存在"))
    }
    @Test fun `进入选择后外部歌单变化不会改变此次下载快照`() {
        val source = mutableListOf(online("1"), online("2"))
        val selection = PlaylistDownloadSelection.from(source)
        source.clear()
        source += online("3")
        assertEquals(listOf(online("1"), online("2")), selection.selectedSongs)
    }
    @Test fun `空列表不会呈现全不选或构成可下载选择`() {
        val selection = PlaylistDownloadSelection.from(emptyList())
        assertFalse(selection.allSelected)
        assertTrue(selection.toggleAll().selectedSongs.isEmpty())
    }
}
