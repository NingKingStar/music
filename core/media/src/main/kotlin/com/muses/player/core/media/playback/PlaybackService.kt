package com.muses.player.core.media.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.cache.SimpleCache
import com.muses.player.core.data.dao.SongDao
import com.muses.player.core.data.mapper.toDomain
import com.muses.player.core.data.log.ErrorLogStore
import com.muses.player.core.data.repository.PlaybackStateRepository
import com.muses.player.core.data.repository.PlayStatsRepository
import com.muses.player.core.data.repository.PlayStatsSessionTracker
import com.muses.player.core.data.repository.RecentPlaysRepository
import com.muses.player.core.model.playback.toRecentPlayEntry
import com.muses.player.core.model.playback.toSavedOnlineSong
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.tag.AudioTagReader
import com.muses.player.core.lyrics.matchDocument
import com.muses.player.core.media.scanner.LocalLibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.muses.player.core.webdav.STREAMING_OKHTTP_QUALIFIER
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.getKoin
import org.koin.core.qualifier.named

/**
 * 播放服务：Media3 MediaSessionService。
 * 持有 ExoPlayer，自动处理通知/媒体按钮/音频焦点/蓝牙断连暂停。
 *
 * P2a Hilt→Koin：`@AndroidEntryPoint` + `@Inject` 字段改 Koin 懒委托（Service 是 Context，直接 `inject()`）。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 流播专用客户端：只带 WebDAV 认证 interceptor，不施加 4 rps 限流（见 [STREAMING_OKHTTP_QUALIFIER]） */
    private val okHttpClient: OkHttpClient by inject(named(STREAMING_OKHTTP_QUALIFIER))

    /** Media3 流播磁盘缓存：探测性重复 Range 请求命中本地不再发网络（防网关限流） */
    private val playbackCache: SimpleCache by inject()

    private val songDao: SongDao by inject()
    private val playbackStateRepository: PlaybackStateRepository by inject()
    private val recentPlaysRepository: RecentPlaysRepository by inject()
    private val playStatsRepository: PlayStatsRepository by inject()

    /** 听歌统计埋点内核（服务生命周期内常驻；见 [PlayStatsSessionTracker]） */
    private val playStatsTracker by lazy { PlayStatsSessionTracker(playStatsRepository) }

    private val recoveryController: PlaybackRecoveryController by inject()
    private val errorLogStore: ErrorLogStore by inject()
    private val audioTagReader: AudioTagReader by inject()
    private val songRepository: SongRepository by inject()
    private val settingsRepository: com.muses.player.core.data.repository.SettingsRepository by inject()

    private var saveJob: kotlinx.coroutines.Job? = null
    private var desktopLyricsOverlay: DesktopLyricsOverlay? = null

    /** 音量增益（LoudnessEnhancer）：服务生命周期内常驻，随设置即时下发 */
    private val volumeBoost by lazy { VolumeBoostController(serviceScope) }

    // ── 通知歌词模式 ──
    /** 原始元数据（切歌时快照；开启歌词模式后不从 player.currentMediaItem 读，防脏读） */
    private var originalTitle: CharSequence? = null
    private var originalArtist: CharSequence? = null
    /** 标记当前 MediaItem 是否已被歌词模式修改过（防重复 replaceMediaItem 触发持久化循环） */
    private var metadataModified = false
    /** 当前曲歌词行缓存（解析一次，position 轮询只做二分查找） */
    private var lyricsLines: List<com.muses.player.core.lyrics.model.LyricLine>? = null
    /** 当前曲歌词 LRC 全文原样（随 [SessionLyricsBridge] 注入平台会话 LYRICS key） */
    private var lyricsRaw: String? = null
    private var onlineNotificationLyricsJob: kotlinx.coroutines.Job? = null
    private var notificationLyricsSongId: String? = null
    private var onlineNotificationDocument: com.muses.player.core.lyrics.model.LyricsDocument? = null
    /** 歌词模式已写入当前曲的替换 metadata（值相等即本轮跳过 replaceMediaItem） */
    private var appliedLyricMetadata: androidx.media3.common.MediaMetadata? = null
    /** 流媒体标签晚到后可能重新覆盖会话，限制重新写入频率。 */
    private var lastLyricReassertAt = 0L
    /** 仅真正切歌才清理歌词状态，歌词替换本身也可能触发转场事件。 */
    private var transitionSongId: String? = null
    /** 换源恢复：同一首在冷却窗口内只重建一次，避免反复 stop/prepare。 */
    private val earlyEndGuard = PlaybackEarlyEndGuard { android.os.SystemClock.elapsedRealtime() }
    private val notifiedPlatformChanges = LinkedHashSet<String>()
    private val resourceRecoveryGuard = PlaybackResourceRecoveryGuard()
    /** 解封装失败后的同曲重试：同一首冷却窗口内只重试一次。 */
    private val containerRetryGuard = PlaybackContainerRetryGuard()
    /** 最近一次成功打开的在线地址（trackUri to url），用于解封装失败时排除坏地址。 */
    private val openedOnlineResources = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile
    private var lastOpenedOnline: Pair<String, String>? = null
    /** 在线解析端口（解封装失败时通知其排除坏地址）。 */
    @Volatile
    private var onlineResolverRef: com.muses.player.core.model.online.OnlineTrackResolver? = null

    // ExoPlayer 强制主线程访问（player-accessed-on-wrong-thread 崩溃防护），
    // 服务生命周期本就在主线程；Room/DataStore 挂起调用内部自行切 IO
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
        // 在线引用必须先解析，缓存以实际地址区分，避免读取旧音源的片段。
        val onlineResolver = runCatching {
            org.koin.core.context.GlobalContext.get().getOrNull<com.muses.player.core.model.online.OnlineTrackResolver>()
        }.getOrNull()
        onlineResolverRef = onlineResolver
        val cacheFactory = CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(okHttpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(this, cacheFactory)
        val dataSourceFactory: DataSource.Factory = if (onlineResolver == null) {
            baseFactory
        } else {
            // 下载记录常驻内存索引：随下载流刷新，播放时不再读 DataStore 与整份队列 JSON。
            val downloadIndex = DownloadedTrackIndex()
            val store = getKoin().getOrNull<com.muses.player.core.download.DownloadQueueStore>()
            // 解码与索引刷新都在 IO 线程：不占主线程，首次播放前已经把常见下载记录建好。
            serviceScope.launch(Dispatchers.IO) {
                store?.downloadedSongs?.collect { downloadIndex.update(it) }
            }
            val downloadedLocator = DownloadedTrackLocator(downloadIndex) { location ->
                runCatching {
                    val uri = android.net.Uri.parse(location)
                    if (uri.scheme == "content") contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
                    else java.io.File(if (uri.scheme == "file") uri.path ?: "" else location)
                        .inputStream().use { it.read() >= 0 }
                }.getOrDefault(false)
            }
            OnlineResolvingDataSourceFactory(
                upstreamFactory = baseFactory,
                resolver = onlineResolver,
                findDownloaded = { ref -> downloadedLocator.find(ref) },
                onOpenedUrl = { trackUri, url ->
                    lastOpenedOnline = trackUri to url
                    openedOnlineResources[trackUri] = url
                    while (openedOnlineResources.size > 64) openedOnlineResources.keys.firstOrNull()?.let { openedOnlineResources.remove(it) }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val ref = com.muses.player.core.model.online.OnlineTrackRef.parse(trackUri)
                        val actual = ref?.let(com.muses.player.core.model.online.OnlineTrackSession::resolvedReference)
                        val current = mediaSession?.player?.currentMediaItem
                        if (ref != null && actual != null && ref.platform != actual.platform &&
                            current?.localConfiguration?.uri?.toString() == trackUri) {
                            val key = current.mediaId + "|" + ref.platform + "|" + actual.platform
                            if (notifiedPlatformChanges.add(key)) {
                                while (notifiedPlatformChanges.size > 64) notifiedPlatformChanges.remove(notifiedPlatformChanges.first())
                                com.muses.player.core.ui.components.MusesSnackbar.show("原平台暂不可用，已切换至" +
                                    com.muses.player.core.model.online.onlinePlatformLabel(actual.platform) + "同版歌曲")
                            }
                        }
                    }
                },
                onResolveError = { uri, e ->
                    android.util.Log.w("MusesTiming", "resolveError uri=${uri.take(48)} err=${e?.message}")
                    errorLogStore.log(
                        ErrorLogStore.Level.WARN, "Playback",
                        "在线直链解析失败（DataSource 打开期）uri=${uri.take(60)}：${e?.message}",
                        e,
                    )
                },
                onResolveTiming = { uri, elapsedMs, local, stages ->
                    // 只记阶段名称与毫秒数，不写完整签名地址与密钥。
                    val detail = stages.entries.joinToString(" ") { "${it.key}=${it.value}ms" }
                    android.util.Log.i("MusesTiming", "resolved ${elapsedMs}ms local=$local ${uri.take(48)} $detail")
                    errorLogStore.log(
                        ErrorLogStore.Level.WARN, "PlaybackTiming",
                        "解析 ${elapsedMs}ms 本地=$local ${uri.take(48)} $detail",
                    )
                },
            )
        }
        // MP3 CBR 时长估算：HTTP 流播（WebDAV）默认 Mp3Extractor 不估算时长（duration=TIME_UNSET），
        // 沉浸页进度条总时长显示 --:-- 且禁用；开 CBR seek flag 后按比特率估算 duration + seek 能力
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp3ExtractorFlags(
                androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING,
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(
                    loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
                ): Long {
                    // 解析失败/资源变化不适合"原地址重试"：交回 onPlayerError 做有界换源重建，
                    // 否则 Media3 会拿同一个坏结果重复解析多次，表现为长时间 BUFFERING。
                    val cause = loadErrorInfo.exception
                    if (cause is OnlineResolveFailedException || cause is OnlineResourceChangedException) {
                        return androidx.media3.common.C.TIME_UNSET
                    }
                    return super.getRetryDelayMsFor(loadErrorInfo)
                }
            })

        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? =
                super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)
                    ?.let { CompatibilityAudioSink(it, errorLogStore) }
        }
        if (requiresSynchronousCodec(android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT)) {
            renderers.forceDisableMediaCodecAsynchronousQueueing()
        }
        val player = ExoPlayer.Builder(this, renderers)
            // 默认允许混音，持久化的音频焦点设置由下方监听即时应用。
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // 默认音量倍率保持 1，不额外衰减或放大；系统媒体音量仍由系统控制。
        player.volume = 1f
        serviceScope.launch {
            settingsRepository.audioFocusEnabled.distinctUntilChanged().collect { enabled ->
                player.setAudioAttributes(audioAttributes, enabled)
            }
        }
        // 内部音量补偿：跟随播放器实际音频会话，统一应用，不读取用户增益配置。
        volumeBoost.attach(player)
        // 注：media3 1.11 无 Player.setPreloadItems（相邻预加载 API 在 1.13+），默认不会预加载整队列；
        // 真正触发 429 的是流播 Range 被 4 rps 限流饿死，已通过流播专用 client（named streamingOkHttp）剥离限流解决。
        // 若实测恢复队列（465 首）一次性 prepare 仍发全列请求，再改为「只 prepare 当前曲 + 下一首」分批加载。

        // 通知卡片点击回应用：Media3 通知的 contentIntent 取自 sessionActivity，
        // 缺省 null 即点击无反应（小米等：别家点卡片回应用，我方无反应即此缺口）。
        // 本版 setSessionActivity 不接受 null，取不到启动意图时保持缺省（行为不变，不崩溃）。
        val notificationActions = MediaNotificationActions(this, serviceScope, getKoin().get(), songRepository, settingsRepository)
        val sessionBuilder = MediaSession.Builder(this, player).setCallback(notificationActions)
        buildSessionActivity()?.let { sessionBuilder.setSessionActivity(it) }
        mediaSession = sessionBuilder.build()
        notificationActions.bind(checkNotNull(mediaSession))

        // 09-07 定案：媒体通知口径 = 上面标题、下面艺术家，专辑不进通知（与迷你条窄屏形态一致）；
        // media3 1.11 默认行为相同，此处显式锁定防止后续升级改默认值。MediaMetadata.albumTitle
        // 保留不删——蓝牙/车载（AVRCP）仍需要专辑名。
        // 口径 provider：标题/艺术家显式锁定（与迷你条窄屏形态一致，防升级改默认值）
        val baseProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                mediaButtonPreferences: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
                showPauseButton: Boolean,
            ): com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton> {
                val buttons = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
                val like = buttons.filter { it.sessionCommand?.customAction == MediaNotificationActions.ACTION_LIKE }
                val lyrics = buttons.filter { it.sessionCommand?.customAction == MediaNotificationActions.ACTION_LYRICS }
                val playback = buttons.filter { it.sessionCommand?.customAction !in setOf(MediaNotificationActions.ACTION_LIKE, MediaNotificationActions.ACTION_LYRICS) }
                return com.google.common.collect.ImmutableList.copyOf(like + playback + lyrics)
            }
            override fun getNotificationContentTitle(
                mediaMetadata: androidx.media3.common.MediaMetadata,
            ): CharSequence = mediaMetadata.title ?: ""

            override fun getNotificationContentText(
                mediaMetadata: androidx.media3.common.MediaMetadata,
            ): CharSequence = mediaMetadata.artist ?: ""
        }
        // 小米超级岛/焦点通知：Provider 委托包装基 provider，createNotification 返回前
        // 追加岛参数（标题/封面取通知自带值，与歌词通知模式的替换结果自动一致；
        // largeIcon 就绪后 Media3 重建通知，岛参数随之刷新。非 HyperOS/无白名单时为 no-op。
        // 不直接在匿名子类 override：本版 media3 基类该方法非 open。
        baseProvider.setSmallIcon(android.R.drawable.ic_media_play)
        val notificationProvider = object : androidx.media3.session.MediaNotification.Provider {
            override fun createNotification(
                session: MediaSession,
                customLayout: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
                actionFactory: androidx.media3.session.MediaNotification.ActionFactory,
                callback: androidx.media3.session.MediaNotification.Provider.Callback,
            ): androidx.media3.session.MediaNotification {
                return baseProvider.createNotification(session, customLayout, actionFactory, callback).also {
                    com.muses.player.core.media.island.XiaomiIslandNotification.decorate(
                        this@PlaybackService,
                        it.notification,
                        true,
                    )
                }
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: android.os.Bundle,
            ): Boolean = baseProvider.handleCustomCommand(session, action, extras)

            override fun getNotificationChannelInfo(): androidx.media3.session.MediaNotification.Provider.NotificationChannelInfo =
                baseProvider.getNotificationChannelInfo()
        }
        setMediaNotificationProvider(notificationProvider)

        // 播放持久化（任务 08-25-native-playback-persistence / P1）
        player.addListener(persistenceListener)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                com.muses.player.core.data.diagnostics.Diagnostics.record("audio_position_advancing", mapOf("session" to "未知", "song" to "未知", "position_ms" to eventTime.currentPlaybackPositionMs.toString()))
            }
            override fun onAudioUnderrun(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                com.muses.player.core.data.diagnostics.Diagnostics.record("audio_underrun", mapOf("session" to "未知", "song" to "未知", "bytes" to bufferSize.toString(), "elapsed_ms" to elapsedSinceLastFeedMs.toString()))
            }
        })
        // 09-07 通知歌词模式：监听开关 + 歌词 + 播放位置，动态替换 MediaMetadata
        startNotificationLyricsMonitoring(player)
        startDesktopLyricsMonitoring(player)
        startVolumeBoostMonitoring()
        startOnlineStallMonitoring(player)
        // ExoPlayer 只能在主线程访问：恢复流程在后台查库，player 操作投递主线程
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        serviceScope.launch {
            val config = playbackStateRepository.readConfig()
            mainHandler.post { applyRestoredConfig(player, config) }
            restoreFromSnapshot(player) { block -> mainHandler.post { block() } }
        }
        // 听歌统计：播放中按节拍结算已播时长（暂停即结算、恢复才起新段），统计页据此近似实时刷新；
        // 一个节拍内的未落盘量在服务销毁时由 flush 兜底
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(PlayStatsSessionTracker.TICK_MS)
                playStatsTracker.checkpoint()
            }
        }
    }

    /**
     * 冷启动恢复（规格书 = queue.ts loadQueueData + session.ts loadPlaybackSession）：
     * 应用配置 → 按快照重建队列（已被曲库删除的歌曲自然过滤）→ seekTo 上次进度。
     * 只恢复不自动播放（playWhenReady 保持 false）。
     */
    /** 主线程应用恢复的播放配置 */
    private fun applyRestoredConfig(player: Player, config: com.muses.player.core.model.playback.PlayerConfig) {
        player.repeatMode = if (config.shuffleEnabled) {
            Player.REPEAT_MODE_ALL
        } else if (config.repeatMode == com.muses.player.core.model.playback.RepeatMode.ONE) {
            Player.REPEAT_MODE_ONE
        } else if (config.repeatMode == com.muses.player.core.model.playback.RepeatMode.NONE) {
            Player.REPEAT_MODE_OFF
        } else { Player.REPEAT_MODE_ALL }
        player.shuffleModeEnabled = config.shuffleEnabled
    }

    // ── 音量增益 ──

    /**
     * 内部音量补偿：记录实际下发状态；失败时保留原有播放。
     */
    private fun startVolumeBoostMonitoring() {
        serviceScope.launch {
            var previousFailure: String? = null
            volumeBoost.status.collect { status ->
                val failure = status.error?.let { "${status.sessionId}/${status.requestedDb}/${it.message}" }
                if (failure != null && failure != previousFailure) {
                    errorLogStore.log(
                        ErrorLogStore.Level.WARN, "Playback",
                        "音量增益未生效：目标 +${status.requestedDb} dB，音频会话 ${status.sessionId}；将有限重试",
                        status.error,
                    )
                } else if (status.appliedDb != null) {
                    android.util.Log.i("VolumeBoost", "音量增益已应用：${status.appliedDb} dB，会话 ${status.sessionId}")
                }
                previousFailure = failure
            }
        }
    }

    // ── 通知歌词模式 ──

    /** 桌面歌词独立于通知开关，优先复用播放页，页面被回收时由服务兜底。 */
    private fun startDesktopLyricsMonitoring(player: Player) {
        var lyricSize = 22
        var lyricColor = 0L
        val overlay = DesktopLyricsOverlay(this,
            onClose = { serviceScope.launch { settingsRepository.setDesktopLyricsEnabled(false) } },
            onLock = { serviceScope.launch { settingsRepository.setDesktopLyricsLocked(true) } },
            onPrevious = { player.seekToPreviousMediaItem() },
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onNext = { player.seekToNextMediaItem() },
            onColor = { color -> serviceScope.launch { settingsRepository.setDesktopLyricsColor(color) } },
            onFontSize = { size -> serviceScope.launch { settingsRepository.setDesktopLyricsFontSize(size.toLong()) } })
        desktopLyricsOverlay = overlay
        serviceScope.launch {
            var enabled = false
            var translation = true
            var coverAccentEnabled = true
            var coverUri: String? = null
            var allowArtworkFallback = true
            var lastCoverRead = 0L
            var colorKey: Pair<String?, String?>? = null
            var colorJob: kotlinx.coroutines.Job? = null
            launch { settingsRepository.desktopLyricsEnabled.collect {
                enabled = it
                if (!it) overlay.hide()
            } }
            launch { settingsRepository.lyricTranslationEnabled.collect { translation = it } }
            launch { settingsRepository.desktopLyricsLocked.collect { overlay.setLocked(it) } }
            launch { settingsRepository.desktopLyricsFontSize.collect { lyricSize = it.toInt(); overlay.setStyle(lyricSize, lyricColor) } }
            launch { settingsRepository.desktopLyricsColor.collect { lyricColor = it; overlay.setStyle(lyricSize, lyricColor) } }
            launch { settingsRepository.coverContentColorEnabled.collect { coverAccentEnabled = it } }
            var lastId: String? = null
            var fallback = com.muses.player.core.lyrics.DesktopLyricsSnapshot()
            var lastRead = 0L
            var lyricsJob: kotlinx.coroutines.Job? = null
            while (true) {
                kotlinx.coroutines.delay(100)
                if (!enabled || !android.provider.Settings.canDrawOverlays(this@PlaybackService)) {
                    overlay.hide()
                    lyricsJob?.cancel()
                    lyricsJob = null
                    lastId = null
                    colorJob?.cancel()
                    colorJob = null
                    colorKey = null
                    continue
                }
                val id = player.currentMediaItem?.mediaId
                if (id == null) {
                    overlay.hide()
                    lyricsJob?.cancel(); lyricsJob = null; lastId = null
                    colorJob?.cancel(); colorJob = null; colorKey = null
                    continue
                }
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (id != lastId) {
                        lyricsJob?.cancel()
                        lyricsJob = null
                        overlay.hide()
                        fallback = com.muses.player.core.lyrics.DesktopLyricsSnapshot(id,
                            player.mediaMetadata.title?.toString().orEmpty(), player.mediaMetadata.artist?.toString())
                        lastId = id
                        lastRead = 0L
                        lastCoverRead = 0L
                    }
                    if (now - lastCoverRead >= 2_000L) {
                        lastCoverRead = now
                        val coverSong = songRepository.getSong(id)
                            ?: com.muses.player.core.model.online.OnlineTrackSession.find(id)
                        coverUri = coverSong?.coverUri
                        allowArtworkFallback = coverSong?.metaSources?.cover == null
                    }
                    val shared = com.muses.player.core.lyrics.DesktopLyricsState.snapshot.value
                    if ((shared.songId != id || shared.document?.lines?.any {
                            it.timingKind == com.muses.player.core.lyrics.model.LyricTimingKind.Precise && it.syllables.isNotEmpty()
                        } != true) && now - lastRead >= 2_000L) {
                        lastRead = now
                        val song = songRepository.getSong(id) ?: com.muses.player.core.model.online.OnlineTrackSession.find(id)
                        if (song != null) {
                            coverUri = song.coverUri
                            val document = kotlinx.coroutines.withContext(Dispatchers.Default) {
                                com.muses.player.feature.player.lyric.LyricsParser.parseDocument(song.lyrics)
                            }
                            if (player.currentMediaItem?.mediaId != id) continue
                            val ref = com.muses.player.core.model.online.OnlineTrackRef.parse(song.path)
                            fallback = com.muses.player.core.lyrics.DesktopLyricsSnapshot(id, song.title, song.artist,
                                if (ref == null) document else fallback.document ?: document)
                            if (ref != null && lyricsJob == null) {
                                // Activity 被回收后仍可读取在线歌词；请求独立于进度轮询，切歌立即取消。
                                lyricsJob = launch lyrics@{
                                    try {
                                        val resolver = getKoin().getOrNull<com.muses.player.core.model.online.OnlineTrackMetadataResolver>()
                                        val matcher = getKoin().getOrNull<com.muses.player.core.lyrics.LyricsMatcher>()
                                        val loader = OnlineSessionLyricsLoader(
                                            resolveLyrics = { resolver?.resolveLyrics(it) },
                                            matchLyrics = {
                                                matcher?.matchDocument(songId = it.id, title = it.title, artist = it.artist,
                                                    album = it.album, durationMs = it.durationMs, durationSec = it.durationSec, songPath = it.path)
                                            },
                                        )
                                        loader.load(song) { selected ->
                                            if (!enabled || player.currentMediaItem?.mediaId != id) return@load
                                            fallback = com.muses.player.core.lyrics.DesktopLyricsSnapshot(id, song.title, song.artist, selected)
                                        }
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { errorLogStore.log(ErrorLogStore.Level.WARN, "DesktopLyrics", "在线桌面歌词加载失败：${e.message}", e) }
                                }
                            }
                        }
                    }
                    val snapshot = com.muses.player.core.lyrics.desktopLyricsSnapshot(id, shared, fallback)
                    if (!enabled || player.currentMediaItem?.mediaId != id) continue
                    val artwork = if (coverAccentEnabled) coverUri?.takeIf { it.isNotBlank() }
                        ?: player.mediaMetadata.artworkUri?.toString()?.takeIf { allowArtworkFallback } else null
                    val nextColorKey = id to artwork
                    if (nextColorKey != colorKey) {
                        colorJob?.cancel()
                        colorKey = nextColorKey
                        overlay.setAccentColor(null)
                        colorJob = artwork?.let { uri -> launch {
                            val provider = getKoin().getOrNull<com.muses.player.core.model.lyrics.CoverAccentColorProvider>()
                            val color = try { provider?.colorFor(uri) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { null }
                            if (enabled && coverAccentEnabled && player.currentMediaItem?.mediaId == id && colorKey == nextColorKey) {
                                overlay.setAccentColor(color)
                                if (color == null) {
                                    kotlinx.coroutines.delay(30_000)
                                    if (colorKey == nextColorKey) colorKey = null
                                }
                            }
                        } }
                    }
                    overlay.setTitle(snapshot.title)
                    overlay.update(com.muses.player.core.lyrics.desktopLyricsText(snapshot, player.currentPosition, translation, player.isPlaying))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    overlay.hide()
                    errorLogStore.log(ErrorLogStore.Level.WARN, "DesktopLyrics", "桌面歌词更新失败：${e.message}", e)
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    /**
     * 监听 [SettingsRepository.notificationLyricsEnabled]，开启后：
     * - 标题位置显示当前歌词行
     * - 艺术家位置显示原始标题与原始艺术家；两项都有值时以「 - 」分隔
     * 关闭时恢复原始 MediaMetadata。
     */
    private fun startNotificationLyricsMonitoring(player: Player) {
        serviceScope.launch {
            var enabled = false
            // 开关收集与轮询独立；单次读库/解析失败不能结束整条歌词推送链。
            launch {
                settingsRepository.notificationLyricsEnabled.collect {
                    enabled = it
                    if (!it) {
                        onlineNotificationLyricsJob?.cancel()
                        onlineNotificationLyricsJob = null
                        notificationLyricsSongId = null
                        restoreNotificationMetadata(player)
                    }
                }
            }
            var lastSongId: String? = null
            var lastLyricsRetryAt = 0L
            while (true) {
                kotlinx.coroutines.delay(100)
                if (!enabled) {
                    lastSongId = null
                    continue
                }
                try {
                    val currentId = player.currentMediaItem?.mediaId
                    if (currentId != lastSongId) {
                        onlineNotificationLyricsJob?.cancel()
                        onlineNotificationLyricsJob = null
                        notificationLyricsSongId = null
                        onlineNotificationDocument = null
                        lyricsLines = null
                        lyricsRaw = null
                        SessionLyricsBridge.push(mediaSession, null)
                        metadataModified = false
                        appliedLyricMetadata = null
                        snapshotOriginalMetadata(player)
                        parseCurrentSongLyrics(player) { enabled }
                        if (!enabled || player.currentMediaItem?.mediaId != currentId) continue
                        lastSongId = currentId
                        lastLyricsRetryAt = android.os.SystemClock.elapsedRealtime()
                    } else if (currentId != null && lyricsLines.isNullOrEmpty() &&
                        android.os.SystemClock.elapsedRealtime() - lastLyricsRetryAt >= 2_000L
                    ) {
                        // 切歌时懒扫描/在线匹配可能尚未写回歌词，稍后再读一次曲库。
                        parseCurrentSongLyrics(player) { enabled }
                        lastLyricsRetryAt = android.os.SystemClock.elapsedRealtime()
                    }
                    // 播放页歌词可能晚到或升级为逐字文档，按同曲标识接入车机输出。
                    if (currentId != null && com.muses.player.core.model.online.OnlineTrackSession.find(currentId) != null) {
                        val shared = com.muses.player.core.lyrics.DesktopLyricsState.snapshot.value
                        val document = shared.document
                        if (shared.songId == currentId && document != null && document.lines.isNotEmpty() &&
                            document !== onlineNotificationDocument
                        ) {
                            onlineNotificationLyricsJob?.cancel()
                            applyOnlineNotificationLyrics(document)
                        }
                    }
                    if (!enabled || player.currentMediaItem?.mediaId != currentId) continue
                    updateNotificationMetadataWithLyric(player)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorLogStore.log(ErrorLogStore.Level.WARN, "NotificationLyrics", "歌词通知更新失败，下轮重试：${e.message}", e)
                    // 快照或解析失败时，下轮重新加载当前曲；避免轮询永久停在旧状态。
                    lastSongId = null
                }
            }
        }
    }

    /** 快照原始元数据（只在首次或切歌时调用，防歌词模式下读到脏值） */
    private suspend fun snapshotOriginalMetadata(player: Player) {
        if (metadataModified) return
        val item = player.currentMediaItem ?: return
        // 快照优先走 Room：媒体元数据的 title 可能是「标题-艺术家」等解析拼接值，
        // 而 artist 字段才是库里的原始艺术家；车机展示的 ucar.* 直接依赖该快照。
        // 查库失败（如在线曲目）回退媒体元数据，行为不变。
        val dbSong = withTimeoutOrNull(500) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { songDao.getById(item.mediaId) }
        }
        if (player.currentMediaItem?.mediaId != item.mediaId) return
        val online = com.muses.player.core.model.online.OnlineTrackSession.find(item.mediaId)
        originalTitle = dbSong?.title ?: online?.title ?: item.mediaMetadata.title
        originalArtist = dbSong?.artist ?: online?.artist ?: item.mediaMetadata.artist
    }

    /** 曲库只读已有歌词；在线曲目接入会话文档，并由独立任务加载，避免阻塞进度推送。 */
    private suspend fun parseCurrentSongLyrics(player: Player, enabled: () -> Boolean) {
        val songId = player.currentMediaItem?.mediaId ?: run {
            lyricsLines = null
            lyricsRaw = null
            return
        }
        val song = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            songRepository.getSong(songId)
        } ?: com.muses.player.core.model.online.OnlineTrackSession.find(songId)
        if (!enabled() || player.currentMediaItem?.mediaId != songId) return
        if (song?.sourceType == com.muses.player.core.model.SourceType.ONLINE) {
            if (notificationLyricsSongId == songId) return
            notificationLyricsSongId = songId
            val resolver = getKoin().getOrNull<com.muses.player.core.model.online.OnlineTrackMetadataResolver>()
            val matcher = getKoin().getOrNull<com.muses.player.core.lyrics.LyricsMatcher>()
            val loader = OnlineSessionLyricsLoader(
                resolveLyrics = { resolver?.resolveLyrics(it) },
                matchLyrics = {
                    matcher?.matchDocument(songId = it.id, title = it.title, artist = it.artist,
                        album = it.album, durationMs = it.durationMs, durationSec = it.durationSec, songPath = it.path)
                },
            )
            onlineNotificationLyricsJob = serviceScope.launch {
                try {
                    loader.load(song) { document ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (enabled() && player.currentMediaItem?.mediaId == songId) applyOnlineNotificationLyrics(document)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    errorLogStore.log(ErrorLogStore.Level.WARN, "NotificationLyrics", "在线车载歌词加载失败：${e.message}", e)
                }
            }
            return
        }
        lyricsRaw = song?.lyrics
        lyricsLines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.muses.player.feature.player.lyric.LyricsParser.parseDocument(song?.lyrics)?.lines
        }
    }

    /** 根据播放位置更新 MediaMetadata：标题=歌词行，艺术家=原始标题与原始艺术家 */
    private fun updateNotificationMetadataWithLyric(player: Player) {
        val session = mediaSession ?: return
        val lines = lyricsLines
        val lyricLine = lines?.takeIf { it.isNotEmpty() }?.let { list ->
            val index = com.muses.player.core.lyrics.model.LyricsDocument(
                lines = list,
            ).highlightedIndex(player.currentPosition)
            index?.let { list.getOrNull(it)?.text?.trim() }?.takeIf { it.isNotEmpty() }
        }
        if (lines.isNullOrEmpty()) {
            if (metadataModified) restoreNotificationMetadata(player)
            return
        }
        val origTitle = originalTitle?.toString() ?: ""
        val origArtist = originalArtist?.toString() ?: ""
        val originalTrackLabel = listOf(origTitle, origArtist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" - ")
        // 标题位=歌词行，副标题位（通知 content text / 车机 ARTIST）按非空值拼接；
        // 前奏/间奏无匹配行：保持上一次的歌词行，不闪回歌名
        if (lyricLine != null) {
            val metadata = PlaybackMetadataUpdates.notificationText(player.mediaMetadata, lyricLine, originalTrackLabel)
            // 相同值跳过：媒体元数据没变就不反复 replaceMediaItem（每轮 Timeline 变更都会触发
            // 持久化保存），车机端同值 setMetadata 也少一次通知刷新。
            val now = android.os.SystemClock.elapsedRealtime()
            val sessionOverwritten = player.mediaMetadata.let {
                it.title != metadata.title || it.artist != metadata.artist
            }
            if (metadata != appliedLyricMetadata ||
                (sessionOverwritten && now - lastLyricReassertAt >= 1_000L)
            ) {
                val current = player.currentMediaItem ?: return
                player.replaceMediaItem(
                    player.currentMediaItemIndex,
                    current.buildUpon().setMediaMetadata(metadata).build(),
                )
                appliedLyricMetadata = metadata
                metadataModified = true
                lastLyricReassertAt = now
            }
        }
        // 注入会话歌词 key 放在 replaceMediaItem **之后**：替换会让 media3 整体重建 metadata
        // （白名单重建），把刚注入的 LYRICS/ucar.* 抹掉——先注入后替换等于每换一行歌词都
        // 白注入一次、空窗到下一轮轮询（≤100ms）；先替换再注入则 key 在本次即生效。
        SessionLyricsBridge.push(
            session,
            lyricsRaw = lyricsRaw,
            ucarTitle = originalTitle?.toString(),
            ucarArtist = originalArtist?.toString(),
            ucarLine = lyricLine,
        )
    }

    /** 恢复原始 MediaMetadata（关闭歌词模式或切歌时） */
    private fun restoreNotificationMetadata(player: Player) {
        val origTitle = originalTitle ?: return
        val origArtist = originalArtist
        val metadata = PlaybackMetadataUpdates.notificationText(player.mediaMetadata, origTitle, origArtist)
        val current = player.currentMediaItem ?: return
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            current.buildUpon().setMediaMetadata(metadata).build(),
        )
        metadataModified = false
        appliedLyricMetadata = null
        lyricsLines = null
        lyricsRaw = null
        // 关歌词模式/切歌走此恢复：清掉平台会话里的歌词 key（media3 若已重建 metadata 则幂等跳过）
        SessionLyricsBridge.push(mediaSession, null)
    }

    /**
     * 冷启动恢复（后台读库；[onMain] 把 ExoPlayer 调用投递回主线程）：
     * 按快照重建队列（已被曲库删除的歌曲自然过滤）→ seekTo 上次进度。
     * 只恢复不自动播放（playWhenReady 保持 false）。
     */
    private suspend fun restoreFromSnapshot(player: Player, onMain: (() -> Unit) -> Unit) {
        val snapshot = playbackStateRepository.readSnapshot() ?: return
        if (snapshot.items.isEmpty()) return

        val online = snapshot.restoreOnlineSongs(recentPlaysRepository.load())
        val onlineById = online.associateBy { it.id }
        val resolved = snapshot.items.mapNotNull { songDao.getById(it.songId)?.toDomain() ?: onlineById[it.songId] }
        if (resolved.isEmpty()) return

        val startIndex = resolved.indexOfFirst { it.id == snapshot.currentSongId }
            .let { if (it >= 0) it else 0 }
        val mediaItems = resolved.map { song -> buildRestoreMediaItem(song) }
        onMain {
            if (player.mediaItemCount != 0) return@onMain
            com.muses.player.core.model.online.OnlineTrackSession.remember(online)
            val position = if (resolved[startIndex].id == snapshot.currentSongId) snapshot.positionMs.coerceAtLeast(0) else 0L
            player.setMediaItems(mediaItems, startIndex, position)
            player.prepare()
        }
    }

    private fun buildRestoreMediaItem(song: com.muses.player.core.model.Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(song.path.toUri())
            .setRequestMetadata(PlaybackSourceMetadata.requestMetadata(song.sourceType))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    // 与 applyPlayback 同口径：不传 albumTitle，系统卡片只显示艺术家
                    .build(),
            )
            .build()

    /** 变更节流保存：500ms debounce，避免 seek 拖动高频写盘 */
    private fun scheduleSnapshotSave(player: Player) {
        saveJob?.cancel()
        saveJob = serviceScope.launch {
            kotlinx.coroutines.delay(500)
            saveSnapshotNow(player)
        }
    }

    private suspend fun saveSnapshotNow(player: Player) {
        val items = (0 until player.mediaItemCount).map {
            com.muses.player.core.model.playback.QueueItem(player.getMediaItemAt(it).mediaId)
        }
        val onlineSongs = com.muses.player.core.model.online.OnlineTrackSession.findAll(items.map { it.songId })
            .values.mapNotNull { song ->
                // 持久队列保留用户原条目；跨平台只属于本次播放，重启后仍可重试原来源。
                (com.muses.player.core.model.online.OnlineTrackSession.originalSong(song.id) ?: song).toSavedOnlineSong()
            }
        playbackStateRepository.writeSnapshot(
            PlaybackStateRepository.PlaybackSnapshot(
                items = items,
                onlineSongs = onlineSongs,
                originalOrder = items,
                shuffleOrder = null, // shuffle 序由 Media3 内部管理，恢复时按开关重洗
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                currentSongId = player.currentMediaItem?.mediaId,
            ),
        )
    }

    private fun recoverOnlineSameSong(player: Player, id: String, index: Int, position: Long,
        opened: Pair<String, String>, reason: String,
    ): Boolean {
        if (!opened.second.startsWith("http", true) || !player.playWhenReady ||
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || !earlyEndGuard.allow(id)) return false
        recordPlayback("recovery_decision", mapOf("reason" to when (reason) {
            "持续缓冲超过25秒" -> "buffer_stall"; "读取中断" -> "read_failure"; else -> "early_eof"
        }, "position_ms" to position.toString()), id)
        onlineResolverRef?.rejectPlayableUrl(opened.second)
        errorLogStore.log(ErrorLogStore.Level.WARN, "Playback", "同曲恢复：原因="+reason+"，歌曲ID="+id+"，位置="+position+"ms")
        com.muses.player.core.ui.components.MusesSnackbar.show("音频加载中断，正在尝试同版音源")
        player.stop()
        player.seekTo(index, position)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    private fun startOnlineStallMonitoring(player: Player) {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val id = player.currentMediaItem?.mediaId
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                val ref = com.muses.player.core.model.online.OnlineTrackRef.parse(uri)
                if (earlyEndGuard.stalled(id, ref != null && player.currentPosition >= 5_000 && player.playbackState == Player.STATE_BUFFERING,
                        player.playWhenReady, player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)) {
                    val opened = uri?.let { key -> openedOnlineResources[key]?.let { key to it } }
                    if (id != null && opened != null) recoverOnlineSameSong(player, id, player.currentMediaItemIndex,
                        player.currentPosition, opened, "持续缓冲超过25秒")
                }
            }
        }
    }

    /** 持久化监听：转场/播放态切换/seek 触发节流保存；转场登记最近播放 */
    /** 媒体回调没有可靠的播放代次来源；只记实际目标，会话始终未知。 */
    private fun recordPlayback(event: String, fields: Map<String, String>, songId: String? = mediaSession?.player?.currentMediaItem?.mediaId) {
        com.muses.player.core.data.diagnostics.Diagnostics.record(event,
            fields + mapOf("song" to (songId?.let { com.muses.player.core.data.diagnostics.Diagnostics.anonymous(it) } ?: "未知"), "session" to "未知"))
    }

    private var playerIntentGeneration = 0L
    private var playerTimelineGeneration = 0L
    private val persistenceListener = object : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            recordPlayback("playback_suppression", mapOf("reason" to playbackSuppressionReason.toString()))
            errorLogStore.log(ErrorLogStore.Level.WARN, "Playback", "音频焦点/播放抑制变化：reason=" + playbackSuppressionReason)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            recordPlayback("play_intent_changed", mapOf("state" to if (playWhenReady) "playing" else "paused", "reason" to reason.toString()))
            playerIntentGeneration++
            if (!playWhenReady) errorLogStore.log(ErrorLogStore.Level.WARN, "Playback", "播放暂停：reason="+reason)
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { playerTimelineGeneration++ }

        override fun onPlaybackStateChanged(state: Int) {
            val player = mediaSession?.player ?: return
            recordPlayback("player_state", mapOf("state" to when (state) {
                Player.STATE_READY -> "ready"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_ENDED -> "ended"; else -> "idle"
            }, "position_ms" to player.currentPosition.toString(), "duration_ms" to player.duration.toString()))
            if (state != Player.STATE_ENDED) return
            val id = player.currentMediaItem?.mediaId ?: return
            val song = com.muses.player.core.model.online.OnlineTrackSession.snapshot.value[id] ?: return
            if (!earlyEndGuard.isEarlyEnd(player.currentPosition, song.durationMs, player.duration)) return
            val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            val opened = lastOpenedOnline?.takeIf { it.first == uri } ?: return
            recoverOnlineSameSong(player, id, player.currentMediaItemIndex, player.currentPosition, opened, "队尾提前结束")
        }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            recordPlayback("media_transition", mapOf("next" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(mediaItem?.mediaId.orEmpty()), "reason" to reason.toString()), mediaItem?.mediaId)
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            recordPlayback("audio_session", mapOf("audio_output" to "未知"))
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            recordPlayback("position_discontinuity", mapOf(
                "reason" to reason.toString(), "previous" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(oldPosition.mediaItem?.mediaId.orEmpty()),
                "next" to com.muses.player.core.data.diagnostics.Diagnostics.anonymous(newPosition.mediaItem?.mediaId.orEmpty()),
                "position_ms" to oldPosition.positionMs.toString()), oldPosition.mediaItem?.mediaId)
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { playerIntentGeneration++; return }
            val oldId = oldPosition.mediaItem?.mediaId ?: return
            val song = com.muses.player.core.model.online.OnlineTrackSession.snapshot.value[oldId] ?: return
            val expected = song.durationMs
            val played = oldPosition.positionMs
            recordPlayback("auto_transition_duration", mapOf("expected_ms" to expected.toString(), "position_ms" to played.toString(), "reason" to "auto"), oldId)
            if (earlyEndGuard.isEarlyEnd(played, expected, played)) {
                errorLogStore.log(ErrorLogStore.Level.WARN, "Playback",
                    "疑似提前结束：歌曲="+song.title+"，已播="+played+"ms，目录="+expected+"ms")
                val index = oldPosition.mediaItemIndex
                val nextId = newPosition.mediaItem?.mediaId
                val uri = oldPosition.mediaItem?.localConfiguration?.uri?.toString()
                val opened = lastOpenedOnline?.takeIf { it.first == uri }
                // 回调返回后再恢复；用户随后切歌、暂停或编辑队列时不得抢回。
                val timeline = playerTimelineGeneration
                val intent = playerIntentGeneration
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val player = mediaSession?.player ?: return@post
                    if (timeline != playerTimelineGeneration || intent != playerIntentGeneration || !player.playWhenReady ||
                        player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                        player.currentMediaItem?.mediaId != nextId || index !in 0 until player.mediaItemCount ||
                        player.getMediaItemAt(index).mediaId != oldId) return@post
                    if (opened == null) return@post
                    recoverOnlineSameSong(player, oldId, index, played, opened, "提前结束")
                }
            }
        }

        /**
         * 播放失败自动恢复（规格书 = controller.ts 播放失败分支）：
         * 登记失败曲 → 沿 active order 回绕查找未尝试候选 → seek+prepare+play；
         * 无候选才停止并暴露安全文案。
         */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val player = mediaSession?.player ?: return
            recordPlayback("player_error", mapOf("code" to error.errorCode.toString(),
                "position_ms" to player.currentPosition.toString(), "duration_ms" to player.duration.toString(),
                "http" to (PlaybackErrorCopy.httpResponseCode(error)?.toString() ?: "未知")))
            val resourceChanged = generateSequence<Throwable>(error) { it.cause }
                .any { it is OnlineResourceChangedException }
            val now = android.os.SystemClock.elapsedRealtime()
            val currentId = player.currentMediaItem?.mediaId
            if (resourceChanged && resourceRecoveryGuard.shouldRecover(currentId, now)) {
                recordPlayback("recovery_decision", mapOf("reason" to "resource_changed", "position_ms" to player.currentPosition.toString()))
                val position = player.currentPosition.coerceAtLeast(0L)
                val index = player.currentMediaItemIndex
                val shouldPlay = player.playWhenReady
                // stop 释放旧媒体源与解码器，重新准备后再按时间定位，避免拼接不同资源的字节。
                player.stop()
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = shouldPlay
                return
            }
            val sourceType = PlaybackSourceMetadata.sourceType(player.currentMediaItem)
            android.util.Log.w("MusesTiming", "playerError code=${error.errorCode} type=$sourceType http=${PlaybackErrorCopy.httpResponseCode(error)}")
            val httpCode = PlaybackErrorCopy.httpResponseCode(error)
            val errorCopy = PlaybackErrorCopy.copyFor(error.errorCode, sourceType, httpCode)
            val onlineRef = com.muses.player.core.model.online.OnlineTrackRef.parse(
                player.currentMediaItem?.localConfiguration?.uri?.toString(),
            )

            // 地址能打开但播放器所有 extractor 都无法解封装（实测 kw 高档位 code 3003）：
            // 有界排除该地址并重建一次，让同档其他脚本/跨平台/更低档位接手，而不是直接跳歌。
            val parseFailure = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            val opened = lastOpenedOnline
            val currentTrackUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (parseFailure && onlineRef != null && opened != null && currentTrackUri == opened.first &&
                containerRetryGuard.shouldRetry(player.currentMediaItem?.mediaId, now)
            ) {
                errorLogStore.log(
                    ErrorLogStore.Level.WARN,
                    "Playback",
                    "音频封装无法识别，排除当前地址后重建重试（同曲，不跳歌）",
                    error,
                )
                recordPlayback("recovery_decision", mapOf("reason" to "container", "position_ms" to player.currentPosition.toString()))
                onlineResolverRef?.rejectPlayableUrl(opened.second)
                val position = player.currentPosition.coerceAtLeast(0L)
                val index = player.currentMediaItemIndex
                val shouldPlay = player.playWhenReady
                player.stop()
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = shouldPlay
                return
            }

            // R2 埋点：播放失败留痕（含限流/恢复链分支），供设置页复制反馈
            errorLogStore.log(
                ErrorLogStore.Level.ERROR,
                "Playback",
                "播放失败：$errorCopy（code=${error.errorCode}, source=${sourceType?.name ?: "UNKNOWN"}, http=${httpCode ?: "无"}）" +
                    " 歌曲=${player.currentMediaItem?.mediaMetadata?.title ?: "未知"}" +
                    " 请求平台=${onlineRef?.platform ?: "无"}" +
                    " 脚本音源=${onlineRef?.sourceId ?: "无"}" +
                    " 服务器=${PlaybackErrorCopy.httpRequestHost(error) ?: "未知"}",
                error,
            )

            // 服务级拒绝（限流 429 / 网关故障 5xx）：服务器整体不可用，跳歌只会继续撞墙
            // 并持续触发请求加重限流（实测 465 首队列轮询切歌）——直接停止，等用户手动重试。
            if (httpCode == 429) {
                errorLogStore.log(
                    ErrorLogStore.Level.WARN,
                    "Playback",
                    "触发限流 429（source=${sourceType?.name ?: "UNKNOWN"}）",
                    error,
                )
                player.stop()
                recordPlayback("recovery_decision", mapOf("reason" to "rate_limited", "position_ms" to player.currentPosition.toString()))
                recoveryController.setError(PlaybackErrorCopy.RATE_LIMITED_RETRY)
                return
            }
            if (httpCode in 500..599) {
                player.stop()
                recordPlayback("recovery_decision", mapOf("reason" to "server_error", "position_ms" to player.currentPosition.toString()))
                recoveryController.setError(errorCopy)
                return
            }

            val retryId = player.currentMediaItem?.mediaId
            val retryOpened = currentTrackUri?.let { key -> openedOnlineResources[key]?.let { key to it } }
            if (!parseFailure && onlineRef != null && retryId != null && retryOpened != null && player.currentPosition >= 5_000 &&
                recoverOnlineSameSong(player, retryId, player.currentMediaItemIndex, player.currentPosition, retryOpened, "读取中断")) return

            val failedId = player.currentMediaItem?.mediaId
            if (failedId != null) {
                recoveryController.markAttempted(failedId)
            }
            val mediaIndices = player.currentTimeline.playbackQueueIndices(player.shuffleModeEnabled)
            val order = mediaIndices.map { player.getMediaItemAt(it).mediaId }
            val candidateIndex = recoveryController.selectNextCandidate(order, mediaIndices.indexOf(player.currentMediaItemIndex))
            if (candidateIndex != null) {
                recordPlayback("recovery_decision", mapOf("reason" to "next", "position_ms" to player.currentPosition.toString()))
                recoveryController.recordAttempt(order[candidateIndex])
                recoveryController.clearError()
                com.muses.player.core.ui.components.MusesSnackbar.show(
                    "「${player.currentMediaItem?.mediaMetadata?.title ?: "歌曲"}」播放失败，正在切换下一首"
                )
                // 继续恢复时不清媒体会话，避免异步 clear 覆盖下一首刚写入的 metadata
                player.seekTo(mediaIndices[candidateIndex], 0)
                player.prepare()
                player.playWhenReady = true
            } else {
                recoveryController.setError(errorCopy)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // 替换当前曲的歌词 metadata 也可能产生转场事件，只有 songId 真正变化才重置。
                val currentId = player.currentMediaItem?.mediaId
                if (currentId != transitionSongId) {
                    metadataModified = false
                    appliedLyricMetadata = null
                    transitionSongId = currentId
                    scheduleSnapshotSave(player)
                    // 听歌统计：转场先结算上一段时长（上一曲的时长归到它自己名下），
                    // 播放次数只在「存在播放意图」时计——冷启动恢复队列同样产生转场事件，
                    // 此时 playWhenReady=false，不应误计
                    val playRequested = player.playWhenReady
                    val playingNow = player.isPlaying
                    if (currentId != null) serviceScope.launch {
                        val entity = songDao.getById(currentId)
                        (entity?.toDomain() ?: com.muses.player.core.model.online.OnlineTrackSession.find(currentId))?.let { song ->
                            recentPlaysRepository.record(
                                song.toRecentPlayEntry(System.currentTimeMillis()),
                            )
                            if (playRequested) {
                                playStatsTracker.onSongStarted(
                                    songId = song.id,
                                    title = song.title,
                                    subtitle = listOfNotNull(song.artist, song.album)
                                        .filter { it.isNotBlank() }.joinToString(" - "),
                                    coverUri = song.coverUri,
                                    isPlaying = playingNow,
                                )
                            } else {
                                // 无播放意图（如冷启动恢复队列）：只结算上一段，不记次数
                                playStatsTracker.onPlaybackState(currentId, playingNow)
                            }
                        }
                        // 播放时核对文件版本并读取有效缓存，文件变化后刷新曲库。
                        // 编排收口 U26 共用 [PlaybackLazyScan]；本处只负责读标签（AudioTagReader Range 探测）+ 入库
                        // 封面缺失同样进入（版本已齐也不跳过）：后补内嵌/首次漏读可经 coverBackfill 回填
                        if (entity != null && entity.sourceType != com.muses.player.core.model.SourceType.ONLINE.name) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val tagData = audioTagReader.readTagForUpdate(entity.path, entity.id)
                                    val fileTags = tagData?.let {
                                        com.muses.player.core.media.scanner.PlaybackLazyScan.FileTags(
                                            title = it.title,
                                            artist = it.artist,
                                            album = it.album,
                                            lyrics = it.lyrics,
                                            coverUri = it.coverUri,
                                            durationMs = it.durationMs,
                                            audioQuality = it.audioQuality,
                                        )
                                    }
                                    val song = entity.toDomain()
                                    val merged = com.muses.player.core.media.scanner.PlaybackLazyScan.merge(
                                        song,
                                        fileTags,
                                    )
                                    if (merged != null) {
                                        songRepository.upsert(merged)
                                    } else {
                                        com.muses.player.core.media.scanner.PlaybackLazyScan.coverBackfill(
                                            song,
                                            fileTags?.coverUri,
                                        )?.let { songRepository.upsert(it) }
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // 静默失败保持 tagsVersion=0 下次重试，不阻塞播放；留痕供设置页排查
                                    errorLogStore.log(ErrorLogStore.Level.WARN, "PlaybackLazyScan", "懒扫描失败 id=${entity.id} path=${entity.path.take(80)}: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_TIMELINE_CHANGED)
            ) {
                scheduleSnapshotSave(player)
            }
        }

        /** 播放/暂停翻转：听歌时长结算与起段的主要驱动（切歌另见 [onEvents] 转场分支） */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            recordPlayback("is_playing", mapOf("state" to if(isPlaying) "playing" else "paused"))
            val player = mediaSession?.player ?: return
            serviceScope.launch {
                playStatsTracker.onPlaybackState(player.currentMediaItem?.mediaId, isPlaying)
            }
        }
    }

    private fun applyOnlineNotificationLyrics(document: com.muses.player.core.lyrics.model.LyricsDocument) {
        onlineNotificationDocument = document
        lyricsLines = document.lines
        lyricsRaw = document.sessionLrc()
    }

    /**
     * 回应用启动意图（通知卡片/系统卡片点击用）。
     *
     * 经包名取启动意图，不直引 :app 的 MainActivity——core:media 不能反向依赖 app，
     * 且 muses/miui 双 flavor 包名不同，包名口径自动适配。MainActivity 为 singleTask，
     * NEW_TASK 下点击直接把已有任务抬到前台，不会重复建栈。
     */
    private fun buildSessionActivity(): PendingIntent? {
        return runCatching {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return null
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 销毁前强制落盘一次快照（runBlocking 短超时；服务销毁路径可接受）
        mediaSession?.player?.let { player ->
            runBlocking {
                withTimeoutOrNull(2_000) { saveSnapshotNow(player) }
                withTimeoutOrNull(2_000) { playStatsTracker.flush() }
            }
        }
        desktopLyricsOverlay?.hide()
        desktopLyricsOverlay = null
        volumeBoost.release()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
