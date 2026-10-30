package com.muses.player.navigation

import androidx.compose.runtime.*
import com.muses.player.core.data.repository.FavoriteSongsStore
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.download.DownloadQueueStore
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.sameSong
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.ui.components.MusesSnackbar
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.lxsdk.LxQuality
import com.muses.player.feature.library.CollectionSongsScreen
import com.muses.player.feature.library.DOWNLOADED_COLLECTION
import com.muses.player.feature.library.LIKED_COLLECTION
import com.muses.player.download.DownloadManager
import com.muses.player.download.DeleteDownloadedSongDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

internal fun applyImportedPlaylistQuality(queue: List<Song>, quality: String): List<Song> = queue.map { song ->
    val ref = OnlineTrackRef.parse(song.path)
    // 跟随设置时重新盖章，同时清掉显式标记：这是「按设置」而不是用户的本次指定。
    if (ref == null) song else song.copy(path = ref.copy(quality = quality, qualityExplicit = false).encode())
}

@Composable
internal fun UserCollectionRoute(collectionId: String, onBack: () -> Unit, onManageDownloads: () -> Unit) {
    val playlists = koinInject<UserPlaylistStore>()
    val favoriteStore = koinInject<FavoriteSongsStore>()
    val downloadStore = koinInject<DownloadQueueStore>()
    val repository = koinInject<SongRepository>()
    val playback = koinInject<PlaybackPort>()
    val favorites by favoriteStore.state.collectAsState()
    val userPlaylists by playlists.playlists.collectAsState(initial = null)
    val downloaded by downloadStore.downloadedSongs.collectAsState(initial = null)
    val currentSongId by playback.currentSongId.collectAsState()
    val scope = rememberCoroutineScope()
    val downloadManager = koinInject<DownloadManager>()
    val settings = koinInject<SettingsRepository>()
    var deleteEntry by remember { mutableStateOf<com.muses.player.core.model.download.DownloadedSong?>(null) }
    var showBatchQuality by remember { mutableStateOf(false) }
    var batchSelection by remember(collectionId) { mutableStateOf<PlaylistDownloadSelection?>(null) }
    val metadataManager = koinInject<PlaylistMetadataManager>()
    val searchHttp = koinInject<com.muses.player.core.search.http.SearchHttp>()
    val searchService = koinInject<com.muses.player.core.search.OnlineSearchService>()
    val preparingBatch by downloadManager.preparingPlaylistDownloads.collectAsState()
    val downloadQuality by settings.downloadPreferredQuality.collectAsState(initial = "320k")
    val selected = userPlaylists.orEmpty().firstOrNull { it.id == collectionId }
    val isLiked = collectionId == LIKED_COLLECTION
    val isDownloaded = collectionId == DOWNLOADED_COLLECTION
    val title = when {
        isLiked -> "我喜欢"
        isDownloaded -> "已下载"
        else -> selected?.name ?: "歌单"
    }
    val songs = remember(favorites, selected, downloaded, collectionId) {
        val saved = downloaded.orEmpty().map { it.toSong() }
        val raw = when {
            isLiked -> favorites.likedSongs
            isDownloaded -> saved
            else -> selected?.tracks.orEmpty().map { it.toSong() }.asReversed()
        }
        raw.map { song ->
            // 收藏关系保持不变；已下载的同曲优先使用实际文件播放，在线曲目仍可独立保存。
            if (song.sourceType == SourceType.ONLINE) saved.firstOrNull { sameSong(it, song) } ?: song
            else favorites.library.firstOrNull { it.id == song.id } ?: song
        }.distinctBy { it.id }
    }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { MusesSnackbar.show("操作失败，请重试") }
        }
    }
    suspend fun prepare(queue: List<Song>) {
        // 设备下载目录不属于扫描音源。播放前登记其持久文件引用，供服务和桌面按 ID 回查。
        withContext(Dispatchers.IO) {
            repository.prepareDownloads(queue.filter { song -> downloaded.orEmpty().any { it.toSong().id == song.id } })
        }
    }
    suspend fun playbackQueue(queue: List<Song>): List<Song> {
        if (selected?.followPreferredQuality != true) return queue
        val quality = settings.onlinePreferredQuality.first()
        return applyImportedPlaylistQuality(queue, quality)
    }
    val removeSong: ((Song) -> Unit)? = when {
        isDownloaded -> { song -> deleteEntry = downloaded.orEmpty().firstOrNull { it.toSong().id == song.id } }
        isLiked -> { song -> perform { favoriteStore.setLiked(song, false) } }
        selected != null -> { song -> perform { playlists.removeFrom(collectionId, song) } }
        else -> null
    }
    CollectionSongsScreen(
        title = title, songs = songs, currentSongId = currentSongId,
        loaded = favorites.loaded && userPlaylists != null && downloaded != null,
        onBack = onBack,
        onPlay = { song, queue -> perform { val prepared = playbackQueue(queue); prepare(prepared); playback.play(song.id, prepared) } },
        onQueue = { song -> perform { val prepared = playbackQueue(listOf(song)); prepare(prepared); playback.addToQueue(prepared); MusesSnackbar.show("已加入播放队列") } },
        onRemove = removeSong, removeLabel = if (isDownloaded) "删除下载" else if (isLiked) "从我喜欢移除" else "从当前歌单移除",
        onManageDownloads = if (isDownloaded) onManageDownloads else null,
        onRepairMetadata = if (selected != null) ({
            perform { playlists.retryMetadata(collectionId); metadataManager.start() }
        }) else null,
        repairingMetadata = selected?.let { (it.followPreferredQuality || it.metadataRepairRequested) && it.metadataRepairVersion < 1 } == true,
        metadataStatus = selected?.let { playlist ->
            if ((playlist.followPreferredQuality || playlist.metadataRepairRequested) && playlist.metadataRepairVersion < 1)
                "正在自动完善信息 · ${playlist.metadataRepairCursor}/${playlist.tracks.size}"
            else playlist.tracks.count { com.muses.player.core.search.needsSongMetadata(it.toSong()) }.takeIf { it > 0 }?.let { "还有 $it 首信息待补全" }
        },
        songStatus = { song -> if (selected != null && song.sourceType == SourceType.ONLINE &&
            com.muses.player.core.search.needsSongMetadata(song)) "信息待补全" else null },
        onDownloadAll = if (selected != null) ({
            batchSelection = PlaylistDownloadSelection.from(selected.tracks.asReversed().map { it.toSong() })
            showBatchQuality = false
        }) else null,
        downloadAllEnabled = selected?.tracks?.isNotEmpty() == true && !preparingBatch,
    )
    batchSelection?.let { selection ->
        PlaylistDownloadPicker(selection, onChange = { batchSelection = it },
            onDismiss = { if (showBatchQuality) showBatchQuality = false else batchSelection = null },
            onDownload = { if (selection.selectedIds.isNotEmpty() && !preparingBatch) showBatchQuality = true },
            qualityContent = {
        if (showBatchQuality) {
        MusesActionsSheet(
            opened = true,
            onDismiss = { showBatchQuality = false },
            label = "下载 ${selection.selectedIds.size} 首 · 选择音质",
            renderInRootScaffold = false,
            items = com.muses.player.core.lxsdk.musicQualitySelections.map { quality ->
                MusesActionItem(quality.label + if (quality.key == downloadQuality) "（默认）" else "") {
                    showBatchQuality = false
                    val entries = selection.selectedSongs
                    batchSelection = null
                    if (entries.isNotEmpty() && !preparingBatch) downloadManager.downloadPlaylist(entries, quality.key)
                }
            },
        )
        }
        })
    }
    deleteEntry?.let { DeleteDownloadedSongDialog(it, downloadManager) { deleteEntry = null } }
}
