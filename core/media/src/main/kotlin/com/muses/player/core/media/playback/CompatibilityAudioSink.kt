package com.muses.player.core.media.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.muses.player.core.data.log.ErrorLogStore
import java.nio.ByteBuffer

/** 异常交接时清理输出状态；不再访问可能已归还给解码器的旧缓冲。 */
@UnstableApi
internal class CompatibilityAudioSink(delegate: AudioSink, private val log: ErrorLogStore) : ForwardingAudioSink(delegate) {
    private var pending: ByteBuffer? = null

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pending != null && pending !== buffer) {
            log.log(ErrorLogStore.Level.WARN, "AudioBufferDiag", "检测到未完成的音频缓冲被替换，重置音频输出后继续播放")
            flush()
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pending = if (consumed) null else buffer
        return consumed
    }

    override fun flush() {
        super.flush()
        pending = null
    }

    override fun reset() {
        super.reset()
        pending = null
    }
}
