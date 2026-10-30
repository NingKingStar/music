package com.muses.player.feature.player.lyric

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.muses.player.core.lyrics.model.LyricsDocument

val LocalPlayerLyricsCoverUri = staticCompositionLocalOf<String?> { null }

val LocalPlaybackLyricsRetry = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalPlaybackLyricsTranslationSource = staticCompositionLocalOf<String?> { null }
val LocalPlaybackLyricsRomanizationSource = staticCompositionLocalOf<String?> { null }
val LocalPlaybackLyricsLoading = staticCompositionLocalOf { false }

@Composable
fun LyricsPanel(
    document: LyricsDocument?,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onAtTopChange: (Boolean) -> Unit = {},
) {
    val state = PlaybackUiState(
        mediaId = null,
        title = "",
        artist = "",
        album = "",
        durationMs = 0L,
        positionMs = positionMs,
        isPlaying = isPlaying,
        artworkUrl = null,
        seekTo = onSeek,
    )
    LyricsPanel(
        state = state,
        modifier = modifier,
        active = true,
        externalDocument = document,
        onAtTopChange = onAtTopChange,
    )
}
