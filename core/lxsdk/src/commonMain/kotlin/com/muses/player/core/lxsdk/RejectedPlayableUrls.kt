package com.muses.player.core.lxsdk

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 运行期已证实无法播放的地址（例如播放器所有 extractor 都无法解封装）。
 *
 * 与短期解析复用不同：这里只做「有界排除」，避免下一次解析又拿到同一个坏地址，
 * 让同档其他脚本、同档跨平台或更低档位有机会被尝试。有效期有限、容量有限，
 * 不会永久封禁某个 CDN 路径，也不替代 HTTP 403/429 的既有处理策略。
 */
class RejectedPlayableUrls(
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 64,
) {
    private data class Entry(val mark: TimeMark)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    fun contains(url: String): Boolean {
        if (url.isBlank()) return false
        val entry = entries.value[url] ?: return false
        if (entry.mark.elapsedNow().inWholeMilliseconds > ttlMs) {
            entries.update { it - url }
            return false
        }
        return true
    }

    fun reject(url: String) {
        if (url.isBlank()) return
        val mark = TimeSource.Monotonic.markNow()
        entries.update { current ->
            val trimmed = if (current.size >= maxEntries && url !in current) {
                val oldest = current.maxByOrNull { it.value.mark.elapsedNow() }?.key
                if (oldest != null) current - oldest else current
            } else current
            trimmed + (url to Entry(mark))
        }
    }

    fun clear() {
        entries.value = emptyMap()
    }
}
