package com.muses.player.feature.sources

import com.muses.player.core.data.repository.UserPlaylist
import com.muses.player.core.model.Song
import com.muses.player.core.model.download.DownloadTrack
import com.muses.player.core.search.OnlineSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchMembershipTest {
    private val online = OnlineSearchResult("tx", "1", "歌曲", "歌手", "专辑", 200000, null, "{}")
    private val local = Song("local:1", "local", "/song", "歌曲", "歌手", "专辑", 200000)

    @Test fun onlyConfirmedLibraryAndActualPlaylistMembershipAreShown() {
        val list = UserPlaylist("1", "旅行", listOf(DownloadTrack.from(local)))
        assertEquals("已加入 旅行", searchMembership(listOf(online), listOf(local), listOf(list)))
        assertNull(searchMembership(listOf(online), emptyList(), listOf(list)))
    }

    @Test fun differentVersionsOrMissingEvidenceAreNotPresentedAsLibraryMatches() {
        assertEquals(listOf(local), matchingLibrarySongs(listOf(online), listOf(local)))
        assertEquals(emptyList(), matchingLibrarySongs(listOf(online), listOf(local.copy(title = "歌曲 (Live)"))))
        assertEquals(emptyList(), matchingLibrarySongs(listOf(online), listOf(local.copy(album = null))))
        assertEquals(emptyList(), matchingLibrarySongs(listOf(online), listOf(local.copy(durationMs = 215000))))
        assertEquals(emptyList(), matchingLibrarySongs(listOf(online), listOf(local.copy(artist = "其他歌手"))))
    }
}
