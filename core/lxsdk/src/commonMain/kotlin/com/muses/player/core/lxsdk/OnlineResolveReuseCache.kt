package com.muses.player.core.lxsdk

import com.muses.player.core.model.online.OnlinePlayableUrl
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 短期、容量有界的解析结果复用。
 *
 * 目的：同一首歌在短时间内重播、切歌来回、暂停恢复时，不必再走一遍脚本请求。
 * 约束（来自实际播放问题）：
 * - 键包含曲目身份（平台/音源/稳定 ID）、目标音质、首选选择与脚本集合版本；
 *   首选切换或脚本变化后自然不命中，正在播放的会话不受影响；
 * - 有效期保守（默认 60 秒，远小于常见签名有效期），到点即失效；
 * - 地址被证实失效（403/404/读取失败）时由播放层调用 [invalidateUrl] 立即丢弃；
 * - 只存内存，不写磁盘，不会把过期地址固化成长期缓存；
 * - 容量有界，超限淘汰最旧的条目。
 */
class OnlineResolveReuseCache(
    private val ttlMs: Long = 60_000L,
    private val maxEntries: Int = 32,
) {
    private data class Entry(val value: OnlinePlayableUrl, val savedAt: TimeMark)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    fun get(key: String): OnlinePlayableUrl? {
        val entry = entries.value[key] ?: return null
        if (entry.savedAt.elapsedNow().inWholeMilliseconds > ttlMs) {
            entries.update { it - key }
            return null
        }
        return entry.value
    }

    fun put(key: String, value: OnlinePlayableUrl) {
        val savedAt = TimeSource.Monotonic.markNow()
        entries.update { current ->
            val trimmed = if (current.size >= maxEntries && key !in current) {
                // elapsedNow 越大 = 越旧，淘汰最大者。
                val oldest = current.maxByOrNull { it.value.savedAt.elapsedNow() }?.key
                if (oldest != null) current - oldest else current
            } else current
            trimmed + (key to Entry(value, savedAt))
        }
    }

    fun invalidateUrl(url: String) {
        if (url.isBlank()) return
        entries.update { current -> current.filterValues { it.value.url != url } }
    }

    fun clear() {
        entries.value = emptyMap()
    }
}
