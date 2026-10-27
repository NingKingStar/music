package com.muses.player.navigation

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.online.OnlineTrackRef
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportedPlaylistQualityTest {
    @Test fun `播放时使用当前偏好但不更改本地音乐`() {
        val original = OnlineTrackRef("wy", "{}", "online", "128k")
        val online = Song("online:wy:1", "online", original.encode(), "歌", sourceType = SourceType.ONLINE)
        val local = Song("local:1", "local", "/music/song.mp3", "本地")
        val first = applyImportedPlaylistQuality(listOf(online, local), "flac")
        assertEquals("flac", OnlineTrackRef.parse(first[0].path)?.quality)
        assertEquals(local, first[1])
        val changed = applyImportedPlaylistQuality(listOf(online), "320k")
        assertEquals("320k", OnlineTrackRef.parse(changed[0].path)?.quality)
        assertEquals("128k", OnlineTrackRef.parse(online.path)?.quality)
    }
}
