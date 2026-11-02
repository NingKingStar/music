package com.muses.player.core.search

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 相同查询排队复用缓存；不同查询不再持有整个平台的网络锁。最后一个使用者离开即释放条目。 */
internal class KeyedRequestLock<K> {
    private class Entry(val mutex: Mutex = Mutex(), var readers: Int = 0)
    private val entries = mutableMapOf<K, Entry>()
    suspend fun <T> withKey(key: K, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.readers++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(entries) { if (--entry.readers == 0) entries.remove(key) } }
    }
}
