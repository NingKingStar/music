package com.muses.player.navigation

import com.muses.player.core.data.repository.UserPlaylistStore
import com.muses.player.core.search.PlaylistMetadataRepair
import com.muses.player.core.search.needsSongMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect

/** 任务属于应用，不绑定页面；每批回写游标，进程重启后继续。只取目录资料，不解析音频。 */
class PlaylistMetadataManager(
    private val store: UserPlaylistStore,
    private val repair: PlaylistMetadataRepair,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false
    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch { store.playlists.collect { wake.trySend(Unit) } }
        scope.launch {
            for (signal in wake) {
                while (isActive) {
                    val playlist = store.snapshot().firstOrNull { (it.followPreferredQuality || it.metadataRepairRequested) && it.metadataRepairVersion < 1 } ?: break
                    val cursor = playlist.metadataRepairCursor.coerceAtMost(playlist.tracks.size)
                    if (cursor == playlist.tracks.size) {
                        val retry = playlist.tracks.any { needsSongMetadata(it.toSong()) } && playlist.metadataRepairPass < 2
                        store.metadataProgress(playlist.id, if (retry) 0 else cursor, playlist.metadataRepairPass + 1, !retry)
                        if (retry) delay(5_000L * (playlist.metadataRepairPass + 1))
                        continue
                    }
                    val batch = playlist.tracks.drop(cursor).take(10)
                    try {
                        val missing = batch.filter { needsSongMetadata(it.toSong()) }
                        if (missing.isNotEmpty()) {
                            val result = repair.inspect(missing.map { it.toSong() })
                            store.applyMetadata(playlist.id, missing, result.songs)
                        }
                        store.metadataProgress(playlist.id, cursor + batch.size, playlist.metadataRepairPass)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        // 单批失败不会卡住后续歌曲；下一轮最多再试两次，已写入内容保留。
                        store.metadataProgress(playlist.id, cursor + batch.size, playlist.metadataRepairPass)
                    }
                    delay(200)
                }
            }
        }
    }
}
