package com.muses.player.core.media.playback

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.muses.player.core.data.repository.FavoriteSongsStore
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SongRepository
import com.muses.player.core.media.R
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineTrackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 通知按钮只读取权威歌曲信息，避免把通知歌词当作歌曲标题收藏。 */
@UnstableApi
internal class MediaNotificationActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val favorites: FavoriteSongsStore,
    private val songs: SongRepository,
    private val settings: SettingsRepository,
) : MediaSession.Callback {
    private val toggleMutex = Mutex()
    private val currentSong = MutableStateFlow<Song?>(null)
    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    private val lyricsCommand = SessionCommand(ACTION_LYRICS, Bundle.EMPTY)

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> =
        Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(likeCommand).add(lyricsCommand).build())
                .build(),
        )

    fun bind(session: MediaSession) {
        val ids = MutableStateFlow(session.player.currentMediaItem?.mediaId)
        session.player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                ids.value = player.currentMediaItem?.mediaId
            }
        })
        scope.launch {
            combine(ids, OnlineTrackSession.snapshot) { id, online -> id to online[id] }
                .distinctUntilChanged()
                .collectLatest { (id, online) ->
                    currentSong.value = null
                    currentSong.value = id?.let { songs.getSong(it) ?: online }
                }
        }
        scope.launch {
            combine(currentSong, favorites.state, settings.desktopLyricsEnabled) { song, state, lyrics ->
                listOf(
                    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setCustomIconResId(if (song != null && state.isLiked(song)) R.drawable.ic_notification_liked else R.drawable.ic_notification_like)
                        .setDisplayName(when {
                            song != null && state.isLiked(song) -> "取消我喜欢"
                            else -> "标记我喜欢"
                        })
                        .setEnabled(song != null && state.loaded)
                        .setSessionCommand(likeCommand)
                        .setSlots(CommandButton.SLOT_OVERFLOW).build(),
                    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setCustomIconResId(if (lyrics) R.drawable.ic_notification_lyrics_on else R.drawable.ic_notification_lyrics)
                        .setDisplayName(if (lyrics) "关闭桌面歌词" else "开启桌面歌词")
                        .setSessionCommand(lyricsCommand)
                        .setSlots(CommandButton.SLOT_OVERFLOW).build(),
                )
            }.collect { session.setMediaButtonPreferences(it) }
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != ACTION_LIKE && customCommand.customAction != ACTION_LYRICS) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        val songId = session.player.currentMediaItem?.mediaId
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            try {
                val code = toggleMutex.withLock {
                    if (customCommand.customAction == ACTION_LYRICS) {
                        val enabled = settings.desktopLyricsEnabled.first()
                        if (!enabled && !Settings.canDrawOverlays(context)) {
                            showMessage("请先在设置中允许悬浮窗权限，再开启桌面歌词")
                            SessionResult.RESULT_ERROR_PERMISSION_DENIED
                        } else {
                            settings.setDesktopLyricsEnabled(!enabled)
                            SessionResult.RESULT_SUCCESS
                        }
                    } else {
                        val song = songId?.let { songs.getSong(it) ?: OnlineTrackSession.find(it) }
                        if (song == null || session.player.currentMediaItem?.mediaId != songId) {
                            SessionResult.RESULT_ERROR_INVALID_STATE
                        } else {
                            val state = favorites.snapshot()
                            if (session.player.currentMediaItem?.mediaId != songId) {
                                SessionResult.RESULT_ERROR_INVALID_STATE
                            } else {
                                favorites.setLiked(song, !state.isLiked(song))
                                SessionResult.RESULT_SUCCESS
                            }
                        }
                    }
                }
                result.set(SessionResult(code))
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MediaNotificationActions", "通知操作失败", e)
                showMessage("操作失败，请重试")
                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return result
    }

    private fun showMessage(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_LIKE = "com.muses.player.TOGGLE_LIKE"
        const val ACTION_LYRICS = "com.muses.player.TOGGLE_DESKTOP_LYRICS"
    }
}
