package com.muses.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.outline.Volume
import com.muses.player.core.model.Song
import com.muses.player.core.model.playback.PlaybackMode
import com.muses.player.core.model.playback.RepeatMode
import com.muses.player.core.ui.components.MusesCover
import com.muses.player.core.ui.components.MusesCoverRadius
import com.muses.player.core.ui.icons.TablerIcons
import com.muses.player.desktop.DesktopRuntime
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DesktopPlayerBar(knownSongs: List<Song>) {
    val scheme = MiuixTheme.colorScheme
    val hook = remember { DesktopRuntime.playerHook() }
    val isPlaying by hook.isPlaying.collectAsState()
    val currentId by hook.currentSongId.collectAsState()
    val position by hook.positionMs.collectAsState()
    val duration by hook.durationMs.collectAsState()
    val meta by hook.currentMeta.collectAsState()
    val volume by hook.volume.collectAsState()
    val config by hook.playerConfig.collectAsState()
    val song = knownSongs.firstOrNull { it.id == currentId }
    val title = meta?.title?.takeIf { it.isNotBlank() } ?: song?.title ?: "未在播放"
    val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: song?.artist ?: "选择一首歌开始播放"
    val cover = meta?.coverUri ?: song?.coverUri
    val mode = PlaybackMode.from(config)
    val hasSong = currentId != null || meta != null
    val effectiveDuration = duration.coerceAtLeast(1L)
    val positionFraction = (position.toFloat() / effectiveDuration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().height(88.dp).background(scheme.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onBackground.copy(alpha = 0.07f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                MusesCover(cover, size = 52.dp, radius = MusesCoverRadius.MD)
                Column(Modifier.padding(start = 11.dp).weight(1f)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, fontSize = 10.sp, color = scheme.onBackgroundVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                PlayerIconButton(TablerIcons.Heart, "喜欢", 26.dp, enabled = hasSong) {}
            }

            Column(Modifier.weight(1f).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIconButton(
                        when {
                            mode == PlaybackMode.SHUFFLE -> TablerIcons.Shuffle
                            mode == PlaybackMode.SINGLE -> TablerIcons.RepeatOne
                            mode == PlaybackMode.SEQUENTIAL -> TablerIcons.FormatListBulleted
                            else -> TablerIcons.Repeat
                        },
                        "播放模式：${mode.label}",
                        24.dp,
                        enabled = hasSong,
                        tint = if (mode == PlaybackMode.SEQUENTIAL || mode == PlaybackMode.SHUFFLE) scheme.onBackgroundVariant else scheme.primary,
                    ) {
                        when (mode) {
                            PlaybackMode.SEQUENTIAL -> { hook.setRepeatMode(RepeatMode.ALL); hook.setShuffleEnabled(false) }
                            PlaybackMode.LOOP -> { hook.setRepeatMode(RepeatMode.ONE); hook.setShuffleEnabled(false) }
                            PlaybackMode.SINGLE -> { hook.setRepeatMode(RepeatMode.ALL); hook.setShuffleEnabled(true) }
                            PlaybackMode.SHUFFLE -> { hook.setRepeatMode(RepeatMode.NONE); hook.setShuffleEnabled(false) }
                        }
                    }
                    PlayerIconButton(TablerIcons.SkipPreviousFill, "上一曲", 28.dp, enabled = hasSong) { hook.previous() }
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (hasSong) scheme.onBackground else scheme.onBackground.copy(alpha = 0.25f))
                            .clickable(enabled = hasSong, role = Role.Button) { hook.togglePlayPause() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (isPlaying) TablerIcons.PauseFill else TablerIcons.PlayFill, if (isPlaying) "暂停" else "播放",
                            tint = scheme.surface, modifier = Modifier.size(18.dp))
                    }
                    PlayerIconButton(TablerIcons.SkipNextFill, "下一曲", 28.dp, enabled = hasSong) { hook.next() }
                    Spacer(Modifier.width(10.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlaybackTime(position), fontSize = 9.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(34.dp))
                    SeekBar(
                        fraction = positionFraction,
                        modifier = Modifier.weight(1f),
                        onSeek = { if (hasSong) hook.seekTo((it * duration).toLong()) },
                    )
                    Text(formatPlaybackTime(duration), fontSize = 9.sp, color = scheme.onBackgroundVariant, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }

            Row(Modifier.width(250.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                val volumeInteraction = remember { MutableInteractionSource() }
                Icon(Tabler.Outline.Volume, "音量", tint = scheme.onBackgroundVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                SeekBar(
                    fraction = (volume / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.width(72.dp),
                    onSeek = { hook.setVolume((it * 100).toInt()) },
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    if (song?.audioQuality.isNullOrBlank()) "标准" else song.audioQuality!!,
                    fontSize = 9.sp,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(scheme.surfaceContainerHigh).padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                PlayerIconButton(TablerIcons.MoreHorizontal, "更多播放操作", 26.dp) {}
            }
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) scheme.onBackground.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = (tint ?: scheme.onBackgroundVariant).copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.64f))
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier.height(16.dp)
            .pointerFraction { onSeek(it) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(scheme.onBackground.copy(alpha = 0.13f)))
        Box(Modifier.fillMaxWidth(safe).height(3.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
        if (safe > 0f) {
            Box(Modifier.fillMaxWidth(safe), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
            }
        }
    }
}

private fun Modifier.pointerFraction(onFraction: (Float) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val width = size.width.toFloat().coerceAtLeast(1f)
            fun report(value: Float) = onFraction(value.coerceIn(0f, 1f))
            report(down.position.x / width)
            var pointer = down
            while (pointer.pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                report(change.position.x / width)
                change.consume()
                pointer = change
            }
        }
    }
