package com.muses.player.core.model.download

import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import kotlinx.serialization.Serializable

/** 已下载集合保存实际文件位置，与可清理的下载任务记录分离。 */
@Serializable
data class DownloadedSong(
    val taskId: String,
    val track: DownloadTrack,
    val location: String,
    val target: DownloadTarget = DownloadTarget(),
    val quality: String? = null,
    val songId: String? = null,
) {
    fun toSong(): Song = track.toSong().copy(
        id = songId ?: "downloaded:$taskId",
        sourceId = target.sourceId ?: "device-downloads",
        path = location,
        sourceType = if (target.kind == DownloadTargetKind.WEBDAV) SourceType.WEBDAV else SourceType.LOCAL,
        audioQuality = quality,
    )
}

/** 按实际文件去重，不合并同名但位置不同的音频；旧条目保留ID以保护收藏和队列引用。 */
fun collectCompletedDownloads(saved: List<DownloadedSong>, tasks: List<DownloadTask>): List<DownloadedSong> {
    var result = saved
    tasks.filter { it.status == DownloadStatus.COMPLETED && !it.savedLocation.isNullOrBlank() }.forEach { task ->
        val target = task.target ?: DownloadTarget()
        val previous = result.firstOrNull {
            it.taskId == task.id || ((it.location == task.savedLocation ||
                (task.savedSongId != null && it.songId == task.savedSongId)) &&
                it.target.sourceId == target.sourceId && it.target.kind == target.kind)
        }
        if (task.skippedExisting && previous != null) return@forEach
        val entry = DownloadedSong(previous?.taskId ?: task.id, task.track, task.savedLocation!!, target,
            task.actualQualityLabel, if (previous != null) previous.songId else task.savedSongId)
        if (previous != entry) result = result.filterNot {
            it.taskId == entry.taskId
        } + entry
    }
    return result
}
