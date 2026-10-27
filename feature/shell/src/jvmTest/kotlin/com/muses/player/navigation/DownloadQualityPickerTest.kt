package com.muses.player.navigation

import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.core.model.online.OnlineTrackRef
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadQualityPickerTest {
    @Test fun `只读取目录明确列出的已知档位`() {
        val path = OnlineTrackRef("tx", """{"catalogQualityTiers":"320k,flac,unknown"}""", "source").encode()
        assertEquals(listOf(LxQuality.Q_320K, LxQuality.FLAC), catalogDownloadQualities(path))
    }

    @Test fun `缺少或损坏数据不推测音质`() {
        assertEquals(emptyList(), catalogDownloadQualities(null))
        assertEquals(emptyList(), catalogDownloadQualities(OnlineTrackRef("tx", "{}", "source").encode()))
        assertEquals(emptyList(), catalogDownloadQualities(OnlineTrackRef("tx", "bad json", "source").encode()))
    }
}
