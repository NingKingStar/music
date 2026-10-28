package com.muses.player.core.media.playback

/**
 * 解封装失败后的同曲重试守卫。
 *
 * 高档位地址可能通过探测但播放器无法识别封装（code 3003）。这种情况值得排除坏地址后
 * 重建一次，让同档其他脚本/跨平台/更低档位接手；但必须严格有界：同一首歌在冷却窗口内
 * 只重试一次，避免在坏地址上反复重建形成死循环。切歌、用户主动播放会自然带来新 id。
 */
internal class PlaybackContainerRetryGuard(private val cooldownMs: Long = 60_000L) {
    private val lastRetryAt = LinkedHashMap<String, Long>()

    fun shouldRetry(mediaItemId: String?, now: Long): Boolean {
        if (mediaItemId.isNullOrBlank()) return false
        val previous = lastRetryAt[mediaItemId]
        if (previous != null && now - previous <= cooldownMs) return false
        if (lastRetryAt.size >= 64) lastRetryAt.remove(lastRetryAt.keys.first())
        lastRetryAt[mediaItemId] = now
        return true
    }
}
