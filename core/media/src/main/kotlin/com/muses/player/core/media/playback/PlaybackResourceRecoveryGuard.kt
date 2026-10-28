package com.muses.player.core.media.playback

/**
 * 换源恢复的防抖守卫。
 *
 * 在线资源中途变化时必须重建解码器（stop → seek → prepare），否则会把两段不同资源的字节
 * 接在一起播放。但重建后如果仍然失败，同一个 mediaItem 不能无限重建：在冷却窗口内只允许
 * 恢复一次，交给常规错误提示与用户重试；切换歌曲后立即重新放行。
 */
internal class PlaybackResourceRecoveryGuard(private val cooldownMs: Long = 30_000L) {
    private var lastMediaItemId: String? = null
    private var lastRecoveryAt = 0L

    fun shouldRecover(mediaItemId: String?, now: Long): Boolean {
        if (mediaItemId.isNullOrBlank()) return false
        if (mediaItemId == lastMediaItemId && now - lastRecoveryAt <= cooldownMs) return false
        lastMediaItemId = mediaItemId
        lastRecoveryAt = now
        return true
    }
}
