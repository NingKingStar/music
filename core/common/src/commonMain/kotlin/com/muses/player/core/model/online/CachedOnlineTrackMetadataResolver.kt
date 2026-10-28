package com.muses.player.core.model.online

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [OnlineTrackMetadataResolver] 的进程内缓存装饰器。
 *
 * ## 为什么需要
 * 播放页（`PlayerViewModel`）与迷你条（`MainViewModel`）各自订阅「当前曲」，
 * 会为同一首在线曲目各拉一次脚本歌词/封面。脚本调用虽轻，但一次是 QuickJS 调用 +
 * 脚本内部 HTTP 往返，且迷你条歌词开关打开时触发更频繁。
 * 装饰一层缓存后，两个 VM 共享同一份结果（Koin 单例）。
 *
 * ## 缓存口径
 * - key = `platform + musicInfoJson`（同一首在线曲目的稳定标识，与 [OnlineTrackRef] 同源）；
 * - **只缓存成功结果**（含内容的歌词 / 非空封面 URL）：失败不缓存，
 *   避免脚本更新或网络恢复后仍被判空；
 * - 有界 LRU（[maxEntries]）：在线曲目不入库，缓存只服务当前会话的少量曲目；
 * - 纯内存态、**不落盘**（与「在线曲目不入库」的整体设计一致，退出即失效）。
 *
 * 线程安全：经 [Mutex] 串行化（commonMain 无 synchronized），对外仍保持 suspend 契约。
 */
class CachedOnlineTrackMetadataResolver(
    private val delegate: OnlineTrackMetadataResolver,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val namespaceProvider: suspend () -> String = { "" },
) : OnlineTrackMetadataResolver {

    private val mutex = Mutex()

    /** 插入序 Map + 命中时重插 → 末尾即最近使用（LinkedHashMap 在 commonMain 无访问序构造） */
    private val lyricsCache = LinkedHashMap<String, OnlineTrackLyrics>()
    private val coverCache = LinkedHashMap<String, String>()

    override suspend fun resolveCover(ref: OnlineTrackRef): String? {
        val actual = OnlineTrackSession.resolvedReference(ref)
        val key = namespaceProvider() + "\u0000" + keyOf(actual)
        mutex.withLock { touch(coverCache, key) }?.let { return it }

        val value = delegate.resolveCover(actual)?.takeIf { it.isNotBlank() } ?: return null
        mutex.withLock { put(coverCache, key, value) }
        return value
    }

    override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? {
        val actual = OnlineTrackSession.resolvedReference(ref)
        val key = namespaceProvider() + "\u0000" + keyOf(actual)
        mutex.withLock { touch(lyricsCache, key) }?.let { return it }

        val value = delegate.resolveLyrics(actual)?.takeIf { !it.isEmpty } ?: return null
        mutex.withLock { put(lyricsCache, key, value) }
        return value
    }

    /** 清空缓存（切换音源/退出在线搜索时可调；有界 LRU 下不调也不会涨） */
    suspend fun clear() = mutex.withLock {
        lyricsCache.clear()
        coverCache.clear()
    }

    /** 命中并把条目移到末尾（最近使用）；未命中返回 null */
    private fun <T> touch(cache: LinkedHashMap<String, T>, key: String): T? {
        val value = cache.remove(key) ?: return null
        cache[key] = value
        return value
    }

    /** 写入并淘汰最久未使用的条目 */
    private fun <T> put(cache: LinkedHashMap<String, T>, key: String, value: T) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > maxEntries) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    private fun keyOf(ref: OnlineTrackRef): String = "${ref.platform}\u0000${ref.musicInfoJson}"

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 16
    }
}
