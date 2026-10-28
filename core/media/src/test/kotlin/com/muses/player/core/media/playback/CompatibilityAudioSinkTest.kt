package com.muses.player.core.media.playback

import androidx.media3.exoplayer.audio.AudioSink
import com.muses.player.core.data.log.RingBufferErrorLogStore
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompatibilityAudioSinkTest {
    @Test fun 同一缓冲重试不重置输出() {
        var flushes = 0
        var calls = 0
        val sink = createSink { method ->
            when (method) {
                "flush" -> { flushes++; null }
                "handleBuffer" -> ++calls > 1
                else -> null
            }
        }
        val buffer = ByteBuffer.allocate(4)
        sink.handleBuffer(buffer, 0, 1)
        sink.handleBuffer(buffer, 0, 1)
        assertEquals(0, flushes)
        assertEquals(2, calls)
    }

    @Test fun 未完成缓冲被替换时先重置再交付新缓冲() {
        val calls = mutableListOf<String>()
        val sink = createSink { method ->
            calls.add(method)
            if (method == "handleBuffer") calls.count { it == "handleBuffer" } > 1 else null
        }
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        sink.handleBuffer(ByteBuffer.allocate(4), 1, 1)
        assertEquals(listOf("handleBuffer", "flush", "handleBuffer"), calls)
    }

    @Test fun 其他参数错误继续抛出() {
        var calls = 0
        val sink = createSink { method ->
            if (method == "handleBuffer") { calls++; throw IllegalArgumentException("bad") }
            null
        }
        assertThrows(IllegalArgumentException::class.java) { sink.handleBuffer(ByteBuffer.allocate(4), 0, 1) }
        assertEquals(1, calls)
    }

    private fun createSink(action: (String) -> Any?): CompatibilityAudioSink {
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ -> action(method.name) } as AudioSink
        return CompatibilityAudioSink(delegate, RingBufferErrorLogStore())
    }
}
