package com.muses.player.core.media.playback

import com.muses.player.core.model.playback.PlayerConfig
import com.muses.player.core.model.playback.RepeatMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal fun playbackConfigFromState(repeat: Int, shuffle: Boolean) = PlayerConfig(
    when (repeat) { 0 -> RepeatMode.NONE; 1 -> RepeatMode.ONE; else -> RepeatMode.ALL }, shuffle)

/** 按回调顺序串行写入；连点时合并中间状态，旧写入不得覆盖最终选择。 */
internal class PlaybackConfigWriter(scope: CoroutineScope, write: suspend (PlayerConfig) -> Unit,
    onError: (Exception) -> Unit = {},
) {
    private val pending = Channel<PlayerConfig>(Channel.CONFLATED)
    init { scope.launch {
        for (config in pending) try { write(config) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e) }
    } }
    fun save(config: PlayerConfig) { pending.trySend(config) }
}
