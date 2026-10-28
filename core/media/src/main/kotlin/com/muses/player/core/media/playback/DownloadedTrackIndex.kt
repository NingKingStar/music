package com.muses.player.core.media.playback

import com.muses.player.core.model.download.DownloadedSong
import com.muses.player.core.model.online.OnlineTrackIdentity
import com.muses.player.core.model.online.OnlineTrackRef

/**
 * 已下载记录的内存索引。
 *
 * 播放每打开一次数据源都会问「这首歌有没有本地文件」，早先每次都读 DataStore 并线性解析
 * 整份队列 JSON，歌多时会拖慢首次出声。这里由服务端持续收集下载流，把记录建成
 * 「稳定身份 → 记录」的映射，查询变成一次哈希查找；下载完成、删除文件后索引随流刷新。
 *
 * 匹配仍走 [OnlineTrackIdentity]：优先平台稳定 ID，缺失时才退回规范化 JSON；
 * 文件是否真的可读由调用方校验，索引不缓存可读结果。
 */
internal class DownloadedTrackIndex {
    @Volatile
    private var byKey: Map<String, List<DownloadedSong>> = emptyMap()

    fun update(entries: List<DownloadedSong>) {
        val map = HashMap<String, MutableList<DownloadedSong>>(entries.size * 2)
        entries.forEach { entry ->
            val ref = OnlineTrackRef.parse(entry.track.reference) ?: return@forEach
            OnlineTrackIdentity.matchKeys(ref).forEach { key ->
                map.getOrPut(key) { ArrayList(2) }.add(entry)
            }
        }
        byKey = map
    }

    /** 命中候选；只返回与引用确实同平台、同音源、同版本的记录。 */
    fun lookup(ref: OnlineTrackRef): List<DownloadedSong> {
        val keys = OnlineTrackIdentity.matchKeys(ref)
        val snapshot = byKey
        if (snapshot.isEmpty()) return emptyList()
        val result = LinkedHashMap<String, DownloadedSong>()
        keys.forEach { key ->
            snapshot[key]?.forEach { entry ->
                if (!result.containsKey(entry.taskId)) result[entry.taskId] = entry
            }
        }
        return result.values.filter { entry ->
            val saved = OnlineTrackRef.parse(entry.track.reference)
            saved != null && OnlineTrackIdentity.matches(ref, saved)
        }
    }

    fun clear() {
        byKey = emptyMap()
    }
}
