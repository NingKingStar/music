package com.muses.player.core.media.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.muses.player.core.data.log.ErrorLogStore
import java.nio.ByteBuffer

/** 仅在音频输出异常时记录缓冲状态；不修改播放器或音频数据。 */
@UnstableApi
internal class DiagnosticAudioSink(
    delegate: AudioSink,
    private val log: ErrorLogStore,
) : ForwardingAudioSink(delegate) {
    private var pending: ByteBuffer? = null
    private var previousId = 0
    private var previousTimeUs = 0L
    private var previousRemaining = 0
    private var previousConsumed = true

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val samePending = pending === buffer
        try {
            val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            previousId = System.identityHashCode(buffer)
            previousTimeUs = presentationTimeUs
            previousRemaining = buffer.remaining()
            previousConsumed = consumed
            pending = if (consumed) null else buffer
            return consumed
        } catch (error: IllegalArgumentException) {
            log.log(
                ErrorLogStore.Level.ERROR,
                "AudioBufferDiag",
                "音频缓冲异常：本次=${describe(buffer, presentationTimeUs)}；与未完成缓冲相同=$samePending；未完成缓冲=${pending?.let { describe(it, -1) } ?: "无"}；上次=id=$previousId，时间=$previousTimeUs，剩余=$previousRemaining，完成=$previousConsumed",
                null,
            )
            throw error
        }
    }

    override fun flush() {
        super.flush()
        pending = null
        previousId = 0
    }

    override fun reset() {
        super.reset()
        pending = null
        previousId = 0
    }

    private fun describe(buffer: ByteBuffer, timeUs: Long): String =
        "id=${System.identityHashCode(buffer)}，时间=$timeUs，位置=${buffer.position()}，剩余=${buffer.remaining()}，字节序=${buffer.order()}"
}
