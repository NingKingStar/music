package com.muses.player.feature.sources

import com.muses.player.core.search.OnlineSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchResultGroupsTest {
    private fun song(platform: String, title: String = "歌曲", artist: String? = "歌手", duration: Long? = 200000, album: String? = "专辑") =
        OnlineSearchResult(platform, platform, title, artist, album, duration, null, "{}")
    private fun groups(vararg songs: OnlineSearchResult) =
        groupSearchResults(songs.map { PlatformSearchState(it.platform, it.platform, listOf(it)) })

    @Test fun sameRecordingCombines() {
        assertEquals(1, groups(song("a"), song("b", duration = 201000)).size)
    }
    @Test fun versionsAndUncertainResultsStaySeparate() {
        assertEquals(2, groups(song("a"), song("b", title = "歌曲 (Live)")).size)
        assertEquals(2, groups(song("a"), song("b", artist = "别的歌手")).size)
        assertEquals(2, groups(song("a"), song("b", duration = null)).size)
        assertEquals(2, groups(song("a"), song("b", album = "其他专辑")).size)
        assertEquals(2, groups(song("a"), song("b", duration = 220000)).size)
    }
    @Test fun samePlatformNeverCollapses() {
        assertEquals(2, groups(song("a"), song("a")).size)
    }
}
