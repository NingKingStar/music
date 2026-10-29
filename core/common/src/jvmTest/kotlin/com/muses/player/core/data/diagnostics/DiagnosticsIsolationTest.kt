package com.muses.player.core.data.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DiagnosticsIsolationTest {
    @Test fun `观测回调抛错与缺省配置均不会阻断业务`() {
        val sink = Diagnostics.sink; val identity = Diagnostics.identify
        val context = Diagnostics.contextProvider; val playback = Diagnostics.startPlayback
        try {
            Diagnostics.sink = { _, _ -> error("写入失败") }
            Diagnostics.identify = { error("匿名失败") }
            Diagnostics.contextProvider = { error("上下文失败") }
            Diagnostics.startPlayback = { _, _ -> error("会话失败") }
            Diagnostics.record("ready"); Diagnostics.beginPlayback("歌曲", "user")
            assertEquals("未知", Diagnostics.anonymous("歌曲")); assertTrue(Diagnostics.context().isEmpty())
            Diagnostics.sink = null; Diagnostics.identify = null
            Diagnostics.contextProvider = null; Diagnostics.startPlayback = null
            Diagnostics.record("ready"); Diagnostics.beginPlayback("歌曲", "user")
            assertEquals("未知", Diagnostics.anonymous("歌曲"))
        } finally {
            Diagnostics.sink = sink; Diagnostics.identify = identity
            Diagnostics.contextProvider = context; Diagnostics.startPlayback = playback
        }
    }

    @Test fun `并发解析各自持有捕获上下文且退出后不污染全局`() = runBlocking {
        val previous = Diagnostics.contextProvider
        try {
            Diagnostics.contextProvider = { mapOf("session" to "全局丙") }
            val firstStarted = CompletableDeferred<Unit>(); val secondStarted = CompletableDeferred<Unit>()
            val first = async {
                Diagnostics.withCorrelation(mapOf("session" to "甲")) {
                    firstStarted.complete(Unit); secondStarted.await(); Diagnostics.correlation()["session"]
                }
            }
            val second = async {
                firstStarted.await()
                Diagnostics.withCorrelation(mapOf("session" to "乙")) {
                    secondStarted.complete(Unit); Diagnostics.correlation()["session"]
                }
            }
            assertEquals("甲", first.await()); assertEquals("乙", second.await())
            assertEquals("未知", Diagnostics.correlation()["session"])
        } finally { Diagnostics.contextProvider = previous }
    }
    @Test fun `目标上下文提供器故障不能阻断播放调用`() {
        val previousBinding = Diagnostics.trackBinding
        val previousTrack = Diagnostics.trackContextProvider
        val previousSong = Diagnostics.songContextProvider
        try {
            Diagnostics.trackBinding = { _, _ -> error("绑定失败") }
            Diagnostics.trackContextProvider = { error("请求上下文失败") }
            Diagnostics.songContextProvider = { error("歌曲上下文失败") }
            Diagnostics.bindTrack("私密URI", "私密ID")
            assertEquals("未知", Diagnostics.contextForTrack("私密URI")["session"])
            assertEquals("未知", Diagnostics.contextForSong("私密ID")["session"])
            assertEquals("未知", Diagnostics.contextForSong(null)["session"])
        } finally {
            Diagnostics.trackBinding = previousBinding
            Diagnostics.trackContextProvider = previousTrack
            Diagnostics.songContextProvider = previousSong
        }
    }}
