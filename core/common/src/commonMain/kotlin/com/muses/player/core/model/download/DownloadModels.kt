package com.muses.player.core.model.download

import com.muses.player.core.model.Song
import com.muses.player.core.model.Source
import com.muses.player.core.model.SourceType
import kotlinx.serialization.Serializable

@Serializable
data class DownloadTrack(
    val id: String, val sourceId: String, val reference: String, val title: String,
    val artist: String? = null, val album: String? = null, val durationMs: Long = 0,
    val coverUri: String? = null, val lyrics: String? = null,
    val sourceType: SourceType = SourceType.ONLINE,
) {
    fun toSong() = Song(id, sourceId, reference, title, artist, album, durationMs,
        durationMs / 1000, coverUri, lyrics, sourceType = sourceType)
    companion object {
        fun from(song: Song) = DownloadTrack(song.id, song.sourceId, song.path, song.title,
            song.artist, song.album, song.durationMs, song.coverUri, song.lyrics, song.sourceType)
    }
}

@Serializable
enum class DownloadTargetKind { DEVICE, LOCAL, WEBDAV }

@Serializable
data class DownloadTarget(
    val kind: DownloadTargetKind = DownloadTargetKind.DEVICE,
    val sourceId: String? = null,
    val directory: String = "",
    val label: String = "设备下载目录",
)

@Serializable
enum class DownloadStatus {
    WAITING, PREPARING, DOWNLOADING, METADATA, SAVING, UPLOADING, PENDING_UPLOAD, PAUSED, FAILED, COMPLETED;
    val active: Boolean get() = this in listOf(PREPARING, DOWNLOADING, METADATA, SAVING, UPLOADING)
}

@Serializable
data class DownloadTask(
    val id: String,
    val track: DownloadTrack,
    val quality: String = "320k",
    val target: DownloadTarget? = null,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val transferredBytes: Long = 0,
    val transferTotalBytes: Long? = null,
    val requestedQuality: String? = null,
    /** 从下载文件音频头核验的结果，不使用脚本请求档位代替。 */
    val actualQualityLabel: String? = null,
    val qualityWarning: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val savedLocation: String? = null,
    val resumeValidator: String? = null,
    val failureStage: DownloadStatus? = null,
    val skippedExisting: Boolean = false,
    val transferMessage: String? = null,
    val localAudioPath: String? = null,
    val localAudioHash: String? = null,
    val localLyrics: String? = null,
    /** 保存层提供的稳定文件ID；旧任务缺省为空，保留原有引用。 */
    val savedSongId: String? = null,
)

/**
 * 保存位置对应的音源是否还在。
 *
 * 设备下载目录永远可用；本地/WebDAV 保存位置依赖音源本身，音源被删除（或 id 对应的类型变了）后就失效，
 * 队列里存下来的旧保存位置需要回退到当前可用的位置。
 */
fun downloadTargetAvailable(target: DownloadTarget, sources: List<Source>): Boolean = when (target.kind) {
    DownloadTargetKind.DEVICE -> true
    DownloadTargetKind.LOCAL -> sources.any { it.id == target.sourceId && it.type == SourceType.LOCAL }
    DownloadTargetKind.WEBDAV -> sources.any { it.id == target.sourceId && it.type == SourceType.WEBDAV }
}

/** 仅形成文件名，不允许音源歌曲元数据成为目录路径。 */
fun downloadBaseName(track: DownloadTrack): String {
    val raw = listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.title).joinToString(" - ")
    val safe = raw.map { if (it < ' ' || it in "<>:\"/\\|?*") '_' else it }.joinToString("")
        .trim().trim('.').take(120).ifBlank { "歌曲" }
    return if (safe.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")) "_$safe" else safe
}
