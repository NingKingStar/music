package com.muses.player.core.playback

import com.muses.player.core.model.Song
import com.muses.player.core.model.playback.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放状态整型常量（U12）：与 Media3 `Player.STATE_*` / `Player.REPEAT_MODE_*`
 * 数值冻结一致（桌面 JvmPlaybackStates 同值，P3-S2 已对齐）。
 * commonMain 不能 import Media3，经此常量对象解耦。
 */
object PlaybackStates {
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    const val REPEAT_MODE_OFF = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2
}

/**
 * 播放端口·UI 全量面（U12）：在 P1 冻结的驱动端口 [PlayerPort] 之上补齐读侧观测
 * 与队列/换曲控制。commonMain 的 PlayerViewModel/QueueViewModel 经此驱动双端播放栈
 * （安卓 PlayerConnection/Media3；桌面 JvmPlayerPort/VLCJ 播放页统一时接入）。
 *
 * 队列展示字段（标题/艺术家）不进端口——端口只暴露 songId 有序集，
 * 由 VM 按曲库组合（双端同源 Room，见 QueueRow 组合链）。
 */
/** 当前曲展示元数据（播放器实时标签；动态 ID3/刮削回写优先于文件标签的展示口径） */
data class PlaybackMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val coverUri: String?,
    val songId: String? = null,
)

interface PlaybackPort : PlayerPort {
    // ── 读侧 ──
    val isPlaying: StateFlow<Boolean>

    /** 当前曲 id（null = 无当前曲；安卓取 MediaItem.mediaId，桌面 hook.currentSongId） */
    val currentSongId: StateFlow<String?>

    /** 兜底封面（播放器实时 metadata 的 artworkUri；桌面暂空流） */
    val artworkUri: StateFlow<String?>

    /** 当前曲实时展示元数据（安卓 Media3 metadata 直映；桌面暂空流） */
    val currentMeta: StateFlow<PlaybackMeta?>

    val duration: StateFlow<Long>

    /** 当前队列的实际播放顺序（随机播放时为洗牌序）；队列索引操作均使用此顺序。 */
    val queueSongIds: StateFlow<List<String>>

    // ── 写侧 ──

    /** 从歌曲列表中选择 songId 开始播放（入队语义，见安卓 PlayerConnection.play） */
    fun play(songId: String, songs: List<Song>)

    /**
     * 追加到播放队列末尾，**不打断当前播放**（曲库/播放页「添加到播放队列」）。
     *
     * 区别于 [enqueue]（那是按新列表重建队列并从指定位置开始播放）：
     * 已在队列里的曲目会被跳过；队列原本为空时，从追加的第一首开始播放。
     */
    fun addToQueue(songs: List<Song>)

    /** 将异步解析的封面同步到当前曲目元数据；过期曲目结果应忽略。 */
    fun updateArtwork(songId: String, coverUri: String) {}

    /** 暂停/继续（语义同安卓 PlayerConnection.playPause） */
    fun playPause()

    fun playAtIndex(index: Int)

    /** 显式重试当前歌曲，宿主可重建媒体源。 */
    fun restartCurrent() { playAtIndex(queueSongIds.value.indexOf(currentSongId.value).coerceAtLeast(0)) }

    fun removeQueueItemAt(index: Int)

    /**
     * 按 songId 集合清理播放队列（U20：删音源时同步清队列，语义同安卓
     * PlayerConnection.removeFromQueue；桌面 JvmPlayerPort 复用状态机 removeSongs）。
     */
    fun removeFromQueue(songIds: Set<String>)

    fun clearQueueItems()

    fun skipToNext()

    fun skipToPrevious()

    /** 即席位置查询（UI 轮询用；ExoPlayer/媒体控制器同步读） */
    fun currentPosition(): Long

    fun clearPlaybackError()

    /** 重置失败恢复链（重试播放前调用） */
    fun resetRecovery()
}
