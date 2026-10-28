package com.muses.player.core.media.playback

import com.muses.player.core.model.download.DownloadTargetKind
import com.muses.player.core.model.online.OnlineTrackRef

/**
 * 已下载文件定位：在线播放前先找「同一首、同一版本、确实可读」的本地文件。
 *
 * 设计要点：
 * - 身份匹配走 [DownloadedTrackIndex]：平台稳定 ID 优先，缺失时才保守比较规范化 JSON；
 * - 文件必须真的可读才算命中，记录还在但文件被删/权限丢失时必须回退在线；
 * - WebDAV 目标不参与（本地播放路径读不到远端），继续走在线解析；
 * - 查询是纯内存操作，不再每次读 DataStore；索引由 PlaybackService 跟随下载流刷新，
 *   下载完成立即可见，不会出现短缓存看不到新文件的问题。
 */
internal class DownloadedTrackLocator(
    private val index: DownloadedTrackIndex,
    private val isReadable: (String) -> Boolean,
) {
    fun find(ref: OnlineTrackRef): String? = index.lookup(ref)
        .asSequence()
        .filter { it.target.kind != DownloadTargetKind.WEBDAV }
        .map { it.location }
        .firstOrNull(isReadable)
}
