package com.muses.player.core.media.playback

/** 提前 EOF 与长缓冲必须和正常结束、用户暂停分开；同曲在冷却内最多一次自动恢复。 */
internal class PlaybackEarlyEndGuard(private val nowMs: () -> Long) {
    private val attempts = LinkedHashMap<String, Long>()
    private var bufferingId: String? = null
    private var bufferingSince = 0L

    fun isEarlyEnd(position: Long, expected: Long, detected: Long): Boolean {
        if (expected < 90_000 || position < 5_000 || expected - position < 30_000) return false
        // 文件本身明显比目录短，或播放器明确标为结束但尚未达到预期。
        // 保守限定不足原曲 80%，避免尾部静音/目录时长误差反复触发恢复。
        return position < expected * 8 / 10 && (detected <= 0 || detected <= position + 5_000)
    }

    fun stalled(id: String?, buffering: Boolean, wantsPlay: Boolean, suppressed: Boolean): Boolean {
        if (!buffering || !wantsPlay || suppressed || id == null) {
            bufferingId = null
            return false
        }
        val now = nowMs()
        if (id != bufferingId) { bufferingId = id; bufferingSince = now; return false }
        return now - bufferingSince >= 25_000
    }

    fun allow(id: String): Boolean {
        val now = nowMs()
        if (attempts[id]?.let { now - it < 120_000 } == true) return false
        if (attempts.size >= 64) attempts.remove(attempts.keys.first())
        attempts[id] = now
        bufferingId = null
        return true
    }
}
