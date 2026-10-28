@file:Suppress("UnsafeOptInUsageError")

package com.muses.player.core.media.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.muses.player.core.data.log.ErrorLogStore
import com.muses.player.core.data.log.RingBufferErrorLogStore
import com.muses.player.core.media.scanner.CoverCacheWriter
import com.muses.player.core.data.mapper.toDomain
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.model.playback.PlayerConfig
import com.muses.player.core.model.playback.RepeatMode
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.webdav.WebDavAudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 播放连接：经 MediaController 连接 PlaybackService，
 * 暴露播放状态 Flow 供 ViewModel 消费。
 *
 * U12：实现 commonMain [PlaybackPort]（UI 全量面）——commonMain 的 PlayerViewModel
 * 经端口驱动双端播放栈；读侧派生流（currentSongId/artworkUri/queueSongIds/playerConfig）
 * 由内部 Media3 StateFlow 映射。
 */
class PlayerConnection constructor(
    private val context: Context,
    private val recoveryController: PlaybackRecoveryController,
    private val webDavCache: WebDavAudioCache,
    private val songDao: com.muses.player.core.data.dao.SongDao,
    /** 播放失败/解析失败日志（R2 埋点，与 PlaybackService 同源） */
    private val errorLogStore: ErrorLogStore = RingBufferErrorLogStore(),
) : PlaybackPort {

    /** 最近一次播放失败的安全文案；用户主动操作后清空（P4 播放页消费） */
    override val playbackError: StateFlow<String?> = recoveryController.playbackError

    /** 清除限流/播放错误（播放页「重试」/关闭按钮消费） */
    override fun clearPlaybackError() = recoveryController.clearError()

    /** @deprecated 使用 [clearPlaybackError] */
    fun clearError() = clearPlaybackError()

    /** 重置恢复链 attempted 集合（限流重试等场景） */
    override fun resetRecovery() = recoveryController.reset()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem: StateFlow<MediaItem?> = _currentMediaItem.asStateFlow()

    // U12 端口派生：当前曲 id（commonMain 侧不感知 MediaItem；syncState 同步维护）
    private val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: StateFlow<String?> = _currentSongId.asStateFlow()

    private val _mediaMetadata = MutableStateFlow<androidx.media3.common.MediaMetadata?>(null)
    val mediaMetadata: StateFlow<androidx.media3.common.MediaMetadata?> = _mediaMetadata.asStateFlow()

    // U12 端口派生：兜底封面（实时 metadata.artworkUri，PlayerViewModel 粘性封面回退用）
    private val _artworkUri = MutableStateFlow<String?>(null)
    override val artworkUri: StateFlow<String?> = _artworkUri.asStateFlow()

    // U16 端口派生：当前曲实时展示元数据（SongsPage 当前播放行动态标签消费）
    private val _currentMeta = MutableStateFlow<com.muses.player.core.playback.PlaybackMeta?>(null)
    override val currentMeta: StateFlow<com.muses.player.core.playback.PlaybackMeta?> = _currentMeta.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    override val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    // U12 端口派生：队列 songId 有序集（展示字段由 VM 按曲库组合）
    private val _queueSongIds = MutableStateFlow<List<String>>(emptyList())
    override val queueSongIds: StateFlow<List<String>> = _queueSongIds.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_ALL)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    // U12 端口派生：P1 的 PlayerConfig 流（repeatMode/shuffle 合一，模型枚举口径）
    private val portScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val playerConfig: StateFlow<PlayerConfig> = combine(
        _repeatMode,
        _shuffleModeEnabled,
    ) { repeat, shuffle ->
        PlayerConfig(
            repeatMode = if (repeat == Player.REPEAT_MODE_ONE) RepeatMode.ONE else RepeatMode.ALL,
            shuffleEnabled = shuffle,
        )
    }.stateIn(portScope, SharingStarted.Eagerly, PlayerConfig())

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentMediaItem.value = mediaItem
            _mediaMetadata.value = controller?.mediaMetadata
            // 端口派生流必须随切换同步：currentSongId 滞留 null 会把 MiniPlayer 钉在空态（「空空如也~」）
            controller?.let { syncPortDerived(it, mediaItem?.mediaMetadata ?: androidx.media3.common.MediaMetadata.EMPTY) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            // ExoPlayer 解析容器 ID3 后的合并 metadata（Player.mediaMetadata），与通知栏同源
            // 不能用 MediaItem.mediaMetadata（静态占位），否则底部栏仍为占位
            // 封面：MediaMetadata.artworkData 为内嵌字节，artworkUri 为空时需落盘为 file:// 供 Coil 显示
            var updated = mediaMetadata
            if (mediaMetadata.artworkData != null && mediaMetadata.artworkUri == null) {
                val bytes = mediaMetadata.artworkData
                if (bytes != null && bytes.isNotEmpty()) {
                    val cacheKey = _currentMediaItem.value?.mediaId ?: mediaMetadata.title?.toString() ?: "cover_${System.currentTimeMillis()}"
                    CoverCacheWriter.write(context, cacheKey, bytes)?.let { uri ->
                        updated = mediaMetadata.buildUpon().setArtworkUri(Uri.parse(uri)).build()
                    }
                }
            }
            _mediaMetadata.value = updated
            // 同时刷新 currentMediaItem 保持 queue 等状态一致
            _currentMediaItem.value = controller?.currentMediaItem
            // 端口派生流同步（用落盘后的 updated，封面才进得了 MiniPlayer）
            controller?.let { syncPortDerived(it, updated) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            ) {
                syncQueue(player)
            }
            // 同步 duration：ExoPlayer 对 WebDAV/Range 流播在缓冲足够后才会给出时长
            // （通知栏有总时长 = 服务端已算出；app 进程必须实时同步，否则沉浸页恒 --:--）
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                _duration.value = if (player.duration > 0) player.duration else 0L
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleModeEnabled.value = shuffleModeEnabled
        }
    }

    override fun connect() {
        if (android.os.Looper.myLooper() != mainHandler.looper) {
            mainHandler.post { connect() }
            return
        }
        controller?.takeIf { it.isConnected }?.let { syncState(it); return }
        if (controllerFuture?.isDone == false) return
        val previous = controllerFuture
        controllerFuture = null
        controller?.removeListener(playerListener)
        controller = null
        previous?.let { MediaController.releaseFuture(it) }
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        // MediaController 所有方法必须在主线程调用：Future 回调也需投递主线程，
        // 否则 syncState 里的 isPlaying 等直接抛 IllegalStateException（MuMu 实测崩溃）
        future.addListener(
            { connectOnMainThread(future) },
            java.util.concurrent.Executor { mainHandler.post(it) },
        )
    }

    private fun connectOnMainThread(future: ListenableFuture<MediaController>) {
        if (controllerFuture !== future) return // 丢弃旧连接的迟到回调。
        val connected = runCatching { future.get() }.getOrNull()
        if (connected == null || !connected.isConnected) {
            controllerFuture = null
            MediaController.releaseFuture(future)
            errorLogStore.log(ErrorLogStore.Level.WARN, "Playback", "播放连接未建立，返回前台时将重新连接")
            return
        }
        controller = connected.also { player ->
            player.addListener(playerListener)
            syncState(player)
        }
    }

    override fun disconnect() {
        controller?.removeListener(playerListener)
        val previous = controllerFuture
        controller = null
        controllerFuture = null
        previous?.let { MediaController.releaseFuture(it) }
    }

    /** 从歌曲列表中选择 songId 开始播放（WebDAV 直接 HTTP 流播，标签由 ExoPlayer 解析回退显示） */
    override fun play(songId: String, songs: List<com.muses.player.core.model.Song>) {
        com.muses.player.core.data.diagnostics.Diagnostics.beginPlayback(songId, "user")
        // 用户主动切歌：重置恢复链与错误状态（controller.ts 语义）
        recoveryController.reset()
        recoveryController.clearError()
        applyPlayback(songId, songs)
    }

    /** U12：P1 驱动面 enqueue——按 songId 集合重建队列并从 index 播放（详情查库后复用 applyPlayback） */
    override fun enqueue(ids: List<String>, index: Int) {
        portScope.launch {
            val songs = ids.mapNotNull { id -> songDao.getById(id)?.toDomain() }
            if (songs.isEmpty()) return@launch
            val startId = songs.getOrNull(index)?.id ?: songs.first().id
            applyPlayback(startId, songs)
        }
    }

    private fun applyPlayback(songId: String, songs: List<com.muses.player.core.model.Song>) {
        com.muses.player.core.data.diagnostics.Diagnostics.record("queue_publish", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(songId) + mapOf("song" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(songId), "count" to songs.size.toString()))
        val player = controller ?: return
        // 在线曲目不入库：登记会话级临时表，供 songLookup 回退（enqueue/队列恢复按 id 查库的场景）。
        // **必须在 setMediaItems 之前同步登记**：登记表基于 StateFlow，写入即时可见；
        // 若放在异步 scope 里，播放页可能先观察到当前曲、后收到登记，从而漏掉封面/歌词。
        if (songs.any { it.sourceType == SourceType.ONLINE }) {
            OnlineTrackSession.remember(songs)
        }
        val mediaItems = songs.map(::toMediaItem)
        val index = mediaItems.indexOfFirst { it.mediaId == songId }
        if (index < 0) return

        player.setMediaItems(mediaItems, index, C.TIME_UNSET)
        // 使用控制器本地的待播队列同步展示，无需等待服务进程完成音源解析。
        _currentMediaItem.value = mediaItems[index]
        _currentSongId.value = songId
        _currentMeta.value = com.muses.player.core.playback.PlaybackMeta(
            songs[index].title, songs[index].artist, songs[index].album, songs[index].coverUri, songId,
        )
        _artworkUri.value = songs[index].coverUri
        _position.value = 0L
        _duration.value = songs[index].durationMs
        _playbackState.value = Player.STATE_BUFFERING
        _isPlaying.value = false
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 追加到队列末尾，不打断当前播放（曲库「添加到播放队列」）。
     * 队列原本为空时退化为从追加的第一首开始播。
     */
    override fun addToQueue(songs: List<com.muses.player.core.model.Song>) {
        if (songs.isEmpty()) return
        portScope.launch {
            val player = controller ?: return@launch
            if (songs.any { it.sourceType == SourceType.ONLINE }) OnlineTrackSession.remember(songs)
            val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val additions = songs.filterNot { it.id in queued }
            if (additions.isEmpty()) return@launch
            val items = additions.map(::toMediaItem)
            if (player.mediaItemCount == 0) {
                player.setMediaItems(items, 0, C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
            } else {
                player.addMediaItems(items)
            }
        }
    }

    /** Song → MediaItem（[applyPlayback] 与 [addToQueue] 共用同一份元数据口径） */
    private fun toMediaItem(song: com.muses.player.core.model.Song): androidx.media3.common.MediaItem {
        // 在线曲目与本地/WebDAV 走同一路径：MediaItem 持有稳定标识 URI，
        // 在线直链由 PlaybackService 的 ResolvingDataSource 在**打开时才**换取。
        // （初版在此对整队逐首预解析，20 首需等待约 30 秒才出声，见 OnlineResolvingDataSourceFactory 注释）
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
        // 不传 albumTitle：小米系统卡片 / 超级岛副标题直接读此字段，
        // 播放条与通知口径统一为「标题/艺术家」，专辑信息仅保留在数据库（蓝牙场景暂不考虑）

        // 在线曲目：搜索结果自带的远程封面直接作为通知/锁屏/系统卡片封面（**零额外请求**）。
        // 此处**不**调脚本 `pic` 预解析：入队时逐首解析会让出声迟到数十秒
        // （见 OnlineResolvingDataSourceFactory 注释）；播放后解析的 pic 经 updateArtwork 同步到媒体会话。
        if (song.sourceType == SourceType.ONLINE) {
            song.coverUri
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?.let { metadata.setArtworkUri(Uri.parse(it)) }
        }
        val playbackUri = resolveUri(song)
        com.muses.player.core.data.diagnostics.Diagnostics.bindTrack(playbackUri.toString(), song.id)
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(playbackUri)
            .setRequestMetadata(PlaybackSourceMetadata.requestMetadata(song.sourceType))
            .setMediaMetadata(metadata.build())
            .build()
    }

    /**
     * 解析播放 URI：WebDAV 曲目查缓存命中转 file://；未命中走 HTTP URL（由 OkHttp 认证
     * interceptor 注入 Authorization）。其余源（含在线已解析直链）直接用 path。
     *
     * play() 在主线程调用（Media3 契约）；[WebDavAudioCache.getCachedFile] 仅做文件 stat 检查，开销可接受。
     */
    private fun resolveUri(song: com.muses.player.core.model.Song): Uri {
        if (song.sourceType != SourceType.WEBDAV) return Uri.parse(song.path)
        return webDavCache.getCachedFile(song.path)?.let { Uri.fromFile(it) } ?: Uri.parse(song.path)
    }

    override fun playPause() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "paused"))
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(positionMs: Long) {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "seek"))
        controller?.seekTo(positionMs)
    }

    /** 选中并播放队列中第 index 项 */
    override fun playAtIndex(index: Int) {
        val player = controller ?: return
        val mediaIndex = player.currentTimeline.playbackQueueIndices(player.shuffleModeEnabled)
            .getOrNull(index) ?: return
        player.seekTo(mediaIndex, 0)
        player.playWhenReady = true
    }

    /** 移除队列中第 index 项 */
    override fun removeQueueItemAt(index: Int) {
        val player = controller ?: return
        val mediaIndex = player.currentTimeline.playbackQueueIndices(player.shuffleModeEnabled)
            .getOrNull(index) ?: return
        player.removeMediaItem(mediaIndex)
    }

    /** 清空队列 */
    override fun clearQueueItems() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "queue"))
        controller?.clearMediaItems()
    }

    /** 从队列中移除指定 songIds 的条目（删源时清理播放队列与底部栏残留；U20 收编端口面） */
    override fun removeFromQueue(songIds: Set<String>) {
        if (songIds.isEmpty()) return
        val player = controller ?: return
        // 需要在主线程操作 ExoPlayer（Media3 主线程铁律），此处由主线程调用方保证或 post
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { removeFromQueue(songIds) }
            return
        }
        // 倒序删除避免索引错位
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId in songIds) {
                player.removeMediaItem(index)
            }
        }
        // 若当前播放项被删且队列非空，ExoPlayer 会自动切到下一项；若队列空则停止
        if (player.mediaItemCount == 0) {
            player.stop()
            player.clearMediaItems()
        }
    }

    override fun skipToNext() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "next"))
        controller?.let { c ->
            val count = c.mediaItemCount
            if (count <= 1) { c.seekTo(0); return@let }
            val idx = c.currentMediaItemIndex
            val target = c.currentTimeline.getNextWindowIndex(idx, Player.REPEAT_MODE_ALL, c.shuffleModeEnabled)
            if (target != C.INDEX_UNSET) c.seekTo(target, 0)
        }
    }

    override fun skipToPrevious() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "previous"))
        controller?.let { c ->
            val count = c.mediaItemCount
            if (count <= 1) { c.seekTo(0); return@let }
            val idx = c.currentMediaItemIndex
            val target = c.currentTimeline.getPreviousWindowIndex(idx, Player.REPEAT_MODE_ALL, c.shuffleModeEnabled)
            if (target != C.INDEX_UNSET) c.seekTo(target, 0)
        }
    }

    override fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    override fun setShuffleEnabled(enabled: Boolean) = setShuffleModeEnabled(enabled)

    /** U12 端口模型重载：枚举 → Media3 整型（REPEAT_MODE_OFF=0/ONE=1/ALL=2 冻结数值） */
    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    override fun play() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "playing"))
        controller?.play()
    }

    override fun pause() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "paused"))
        controller?.pause()
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    fun stop() {
        com.muses.player.core.data.diagnostics.Diagnostics.record("control_intent", com.muses.player.core.data.diagnostics.Diagnostics.contextForSong(controller?.currentMediaItem?.mediaId) + mapOf("origin" to "user", "reason" to "stop"))
        controller?.stop()
    }

    /**
     * syncState 的增量版：同步端口派生流（currentSongId/currentMeta/artworkUri/queue）。
     * [meta] 传回调收到的（可能已落盘封面）metadata；缺省从 player 实时读。
     */
    private fun syncPortDerived(player: Player, meta: androidx.media3.common.MediaMetadata = player.mediaMetadata) {
        _currentSongId.value = player.currentMediaItem?.mediaId
        _artworkUri.value = meta.artworkUri?.toString()
        _currentMeta.value = com.muses.player.core.playback.PlaybackMeta(
            title = meta.title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            artist = meta.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            album = meta.albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            coverUri = meta.artworkUri?.toString(),
            songId = player.currentMediaItem?.mediaId,
        )
        syncQueue(player)
    }

    override fun updateArtwork(songId: String, coverUri: String) {
        portScope.launch(Dispatchers.Main.immediate) {
            val player = controller ?: return@launch
            val updated = PlaybackMetadataUpdates.artwork(player.currentMediaItem, songId, coverUri)
                ?: return@launch
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
        }
    }

    private fun syncQueue(player: Player) {
        val items = player.currentTimeline.playbackQueueIndices(player.shuffleModeEnabled)
            .map { player.getMediaItemAt(it) }
        _queue.value = items
        _queueSongIds.value = items.map { it.mediaId }
    }

    private fun syncState(player: MediaController) {
        _isPlaying.value = player.isPlaying
        _currentMediaItem.value = player.currentMediaItem
        var meta = player.mediaMetadata
        if (meta.artworkData != null && meta.artworkUri == null) {
            meta.artworkData?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val cacheKey = player.currentMediaItem?.mediaId ?: meta.title?.toString() ?: "cover"
                CoverCacheWriter.write(context, cacheKey, bytes)?.let { uri ->
                    meta = meta.buildUpon().setArtworkUri(Uri.parse(uri)).build()
                }
            }
        }
        _mediaMetadata.value = meta
        // U12 端口派生流同步（与上面 Media3 流一一对应）
        _currentSongId.value = player.currentMediaItem?.mediaId
        _artworkUri.value = meta.artworkUri?.toString()
        _currentMeta.value = com.muses.player.core.playback.PlaybackMeta(
            title = meta.title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            artist = meta.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            album = meta.albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            coverUri = meta.artworkUri?.toString(),
            songId = player.currentMediaItem?.mediaId,
        )
        syncQueue(player)
        _position.value = player.currentPosition
        _duration.value = if (player.duration > 0) player.duration else 0L
        _playbackState.value = player.playbackState
        _repeatMode.value = player.repeatMode
        _shuffleModeEnabled.value = player.shuffleModeEnabled
    }

    /**
     * 位置更新轮询（UI 层调用，约 500ms 一次）。
     * ExoPlayer 的 Player.Listener 不实时推送 position，
     * UI 需主动读取以驱动进度条。
     */
    override fun currentPosition(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long {
        val d = controller?.duration ?: 0L
        return if (d > 0) d else 0L
    }
}
