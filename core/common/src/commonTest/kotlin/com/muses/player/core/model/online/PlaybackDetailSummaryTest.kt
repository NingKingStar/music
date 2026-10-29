package com.muses.player.core.model.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 播放来源摘要：本地、脚本声明音质、降档回退、未知信息都要如实区分。 */
class PlaybackDetailSummaryTest {
    @Test fun `跨平台替代显示原平台实际平台和音源而非只显示音质`() {
        val text = playbackDetailSummary(OnlineTrackSession.PlaybackDetails("ikun", "master", "flac", false, "wy", "tx"))
        assertTrue(text.contains("QQ音乐"))
        assertTrue(text.contains("已从网易云音乐切换同版"))
        assertTrue(text.contains("ikun"))
        assertTrue(text.contains("请求 master 已回退"))
    }

    @Test
    fun `本地下载不冒充实测音质`() {
        val text = playbackDetailSummary(
            OnlineTrackSession.PlaybackDetails(
                sourceName = "本地下载", requestedQuality = null, resolvedQuality = null, local = true,
            ),
        )
        assertEquals("本地下载 · 音质未核实", text)
    }

    @Test
    fun `在线音源标出脚本声明与档位`() {
        val text = playbackDetailSummary(
            OnlineTrackSession.PlaybackDetails("ikun音源", "320k", "320k", local = false),
        )
        assertEquals("ikun音源 · 320k（脚本声明）", text)
    }

    @Test
    fun `降档后保留请求档位`() {
        val text = playbackDetailSummary(
            OnlineTrackSession.PlaybackDetails("ikun音源", "flac", "320k", local = false),
        )
        assertTrue(text.contains("请求 flac 已回退"), text)
        assertTrue(text.startsWith("ikun音源 · 320k"), text)
    }

    @Test
    fun `缺少音源名或音质时明确标注未核实`() {
        val noQuality = playbackDetailSummary(
            OnlineTrackSession.PlaybackDetails(null, "320k", null, local = false),
        )
        assertEquals("音源未核实 · 音质未核实", noQuality)
        val noSource = playbackDetailSummary(
            OnlineTrackSession.PlaybackDetails("  ", null, "128k", local = false),
        )
        assertEquals("音源未核实 · 128k（脚本声明）", noSource)
        assertFalse(noQuality.contains("已回退"))
    }
}
