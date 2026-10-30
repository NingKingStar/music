package com.muses.player.feature.player.lyric

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.LyricSupplementMode
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.ui.components.*
import com.muses.player.core.ui.icons.TablerIcons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Text

/** 歌词页只承担显示与播放暂停，选曲和进度仍留在封面页。 */
@Composable
fun PlaybackLyricsPanel(
    document: LyricsDocument?, positionMs: Long, isPlaying: Boolean,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, onAtTopChange: (Boolean) -> Unit = {},
) {
    val settings = koinInject<SettingsRepository>()
    val supplement by settings.lyricSupplementMode.collectAsStateWithLifecycle(initialValue = LyricSupplementMode.Translation)
    val translation = supplement == LyricSupplementMode.Translation
    val romanization = supplement == LyricSupplementMode.Romanization
    val textSize by settings.lyricTextSize.collectAsStateWithLifecycle(initialValue = 24L)
    val spacing by settings.lyricLineSpacing.collectAsStateWithLifecycle(initialValue = 28L)
    val wordHighlight by settings.lyricWordHighlightEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    val translated = document?.lines?.any { !it.translation.isNullOrBlank() } == true
    val romanized = document?.lines?.any { !it.romanization.isNullOrBlank() } == true
    val retry = LocalPlaybackLyricsRetry.current
    val loading = LocalPlaybackLyricsLoading.current
    var showSettings by remember { mutableStateOf(false) }
    val translationHint = when {
        !translated && loading -> "正在查找译文"
        !translated -> "暂无译文"
        else -> ""
    }
    val onTranslation: (Boolean) -> Unit = { value -> scope.launch { settings.setLyricTranslationEnabled(value) } }
    val onRomanization: (Boolean) -> Unit = { value -> scope.launch { settings.setLyricRomanizationEnabled(value) } }
    Column(modifier) {
        LyricsPanel(document, positionMs, isPlaying, onSeek,
            Modifier.weight(1f).fillMaxWidth(), onAtTopChange)
        LyricsToolbar(translation, romanization, translated, romanized, isPlaying,
            onTranslation = { if (translated) onTranslation(!translation) },
            onRomanization = { if (romanized) onRomanization(!romanization) },
            onSettings = { showSettings = true }, onPlayPause = onPlayPause,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(), loading = loading)
    }
    if (showSettings) ImmersiveLyricsSettingsSheet(
        coverUri = LocalPlayerLyricsCoverUri.current,
        onDismiss = { showSettings = false },
    ) { close ->
        LyricsSettingsContent(
            translation, romanization, translated, romanized, translationHint,
            textSize, spacing, wordHighlight, loading,
            onTranslation, onRomanization,
            onTextSize = { scope.launch { settings.setLyricTextSize(it) } },
            onSpacing = { scope.launch { settings.setLyricLineSpacing(it) } },
            onHighlight = { value -> scope.launch { settings.setLyricWordHighlightEnabled(value) } },
            onRetry = retry,
            romanizationHint = when {
                romanized -> ""
                loading -> "正在查找读音"
                else -> "暂未获取读音"
            },
            previewLine = document?.lines?.lastOrNull { it.timeMs <= positionMs }
                ?: document?.lines?.firstOrNull(),
            nextPreviewLine = document?.lines?.firstOrNull { it.timeMs > positionMs },
            onClose = close,
        )
    }
}

@Composable
internal fun LyricsToolbar(
    translation: Boolean, romanization: Boolean, translated: Boolean, romanized: Boolean,
    playing: Boolean, onTranslation: () -> Unit, onRomanization: () -> Unit,
    onSettings: () -> Unit, onPlayPause: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false,
) {
    val ink = LocalPlayerContentColor.current
    Row(modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        LyricToolButton("译", translation && translated, translated, "显示译词", ink, onTranslation, loading)
        LyricToolButton("音", romanization && romanized, romanized, "显示音译", ink, onRomanization, loading)
        LyricToolButton("词", false, true, "歌词设置", ink, onSettings)
        Spacer(Modifier.weight(1f))
        MusesIconButton(onClick = onPlayPause,
            modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape),
            imageVector = if (playing) TablerIcons.PauseFill else TablerIcons.PlayFill,
            contentDescription = if (playing) "暂停" else "播放",
            size = MusesIconButtonSize.LG, tint = ink)
    }
}

@Composable
private fun LyricToolButton(
    label: String, selected: Boolean, available: Boolean, description: String,
    ink: Color, onClick: () -> Unit, loading: Boolean = false,
) {
    // 大触控区不绘制方形反馈，圆角反馈仅限可见按钮。缺资源禁用，不跳转。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, tween(130))
    val pressFill by animateColorAsState(ink.copy(alpha = if (pressed) .18f else 0f), tween(130))
    val tint by animateColorAsState(ink.copy(alpha = if (selected) 1f else if (available) .78f else .5f), tween(180))
    val fill by animateColorAsState(ink.copy(alpha = if (selected) .18f else 0f), tween(180))
    Box(Modifier.size(44.dp).clickable(interactionSource = interaction, indication = null,
        enabled = available, role = Role.Button, onClick = onClick).semantics {
        contentDescription = if (available) description else description + if (loading) "（查找中）" else "（暂无）"
        this.selected = selected
        if (label != "词") stateDescription = if (!available) { if (loading) "查找中" else "暂无" } else if (selected) "已开启" else "已关闭"
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(7.dp)).background(fill)
            .background(pressFill)
            .border(1.dp, tint.copy(alpha = if (selected) .8f else .48f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            Text(label, color = tint, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
