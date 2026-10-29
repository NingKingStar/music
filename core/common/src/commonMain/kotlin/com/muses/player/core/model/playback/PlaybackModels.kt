package com.muses.player.core.model.playback

/**
 * 播放持久化领域模型（任务 08-25-native-playback-persistence / P0）。
 *
 * 规格书 = Web 层 src/features/player/{queue,session,recent}.ts 的类型段，逐字段翻译。
 */

/** 循环模式（queue.ts RepeatMode）；wire 值与 Web 一致 */
enum class RepeatMode(val wire: String) {
    NONE("none"),
    ONE("one"),
    ALL("all"),
}

enum class PlaybackMode(val label: String, val repeat: RepeatMode, val shuffle: Boolean) {
    SEQUENTIAL("顺序播放", RepeatMode.NONE, false),
    LOOP("列表循环", RepeatMode.ALL, false),
    SINGLE("单曲循环", RepeatMode.ONE, false),
    SHUFFLE("随机播放", RepeatMode.ALL, true);
    companion object {
        fun from(config: PlayerConfig): PlaybackMode = if (config.shuffleEnabled) SHUFFLE else
            entries.first { it.repeat == config.repeatMode && !it.shuffle }
    }
}

/**
 * 播放器配置（queue.ts PlayerConfig）。
 * 默认值对齐 defaultConfig()：all / false。
 */
data class PlayerConfig(
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val shuffleEnabled: Boolean = false,
)

/** 队列条目：仅存 songId，恢复时按曲库解析完整歌曲（queue.ts QueueItem） */
data class QueueItem(val songId: String)

/**
 * 队列快照（queue.ts QueueData）：
 * items=当前生效序列；originalOrder=未洗牌原始序；shuffleOrder=洗牌序（未启用为 null）。
 */
data class QueueSnapshotData(
    val items: List<QueueItem> = emptyList(),
    val originalOrder: List<QueueItem> = emptyList(),
    val shuffleOrder: List<QueueItem>? = null,
)

/**
 * 冷启动播放会话（session.ts PlaybackSession）：当前曲 + 进度。
 */
data class PlaybackSessionInfo(
    val currentSongId: String,
    /** 毫秒，>= 0 */
    val positionMs: Long = 0L,
)

/**
 * 最近播放条目：同曲去重置顶，仅保留最近半年的记录；
 * 曲库歌曲按 songId 解析；在线歌曲额外保存引用，以便应用重启后再次播放。
 */
@kotlinx.serialization.Serializable
data class RecentPlayEntry(
    val songId: String,
    val title: String,
    /** artist - album 形式的副标题 */
    val subtitle: String,
    val coverUri: String? = null,
    val playedAt: Long,
    val onlineTrack: RecentOnlineTrack? = null,
)

@kotlinx.serialization.Serializable
data class RecentOnlineTrack(
    val sourceId: String,
    val path: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
)

fun com.muses.player.core.model.Song.toRecentPlayEntry(playedAt: Long): RecentPlayEntry = RecentPlayEntry(
    songId = id,
    title = title,
    subtitle = listOfNotNull(artist, album).filter { it.isNotBlank() }.joinToString(" - "),
    coverUri = coverUri,
    playedAt = playedAt,
    onlineTrack = if (sourceType == com.muses.player.core.model.SourceType.ONLINE &&
        com.muses.player.core.model.online.OnlineTrackRef.parse(path) != null
    ) RecentOnlineTrack(sourceId, path, artist, album, durationMs) else null,
)

fun RecentPlayEntry.onlineSong(): com.muses.player.core.model.Song? {
    val track = onlineTrack ?: return null
    if (com.muses.player.core.model.online.OnlineTrackRef.parse(track.path) == null) return null
    return com.muses.player.core.model.Song(
        id = songId, sourceId = track.sourceId, path = track.path, title = title,
        artist = track.artist, album = track.album, durationMs = track.durationMs,
        durationSec = track.durationMs / 1000, coverUri = coverUri,
        sourceType = com.muses.player.core.model.SourceType.ONLINE,
    )
}
