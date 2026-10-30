package com.muses.player.feature.player.lyric

import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.muses.player.core.ui.components.LocalPlayerContentColor
import java.text.BreakIterator
import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.muses.player.core.lyrics.model.LyricHighlightStrategy
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.lyrics.processor.LyricTimelineProcessor
import com.muses.player.core.lyrics.aligner.LyricRomanizationAligner
import com.muses.player.core.lyrics.model.LyricsDocument
import com.muses.player.core.lyrics.model.withPseudoTiming
import com.muses.player.feature.player.lyric.SettingsRuntime
import com.muses.player.feature.player.lyric.AppVisibility
import com.muses.player.feature.player.lyric.LocalFontFamily
import com.muses.player.feature.player.lyric.LanTingProFontFamily
import com.muses.player.feature.player.lyric.LyricsRenderingQuality
import com.muses.player.feature.player.lyric.LyricAnnotationDisplayMode
import com.muses.player.feature.player.lyric.LyricsGroupingMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt


/** Three staggered instrumental bars, matching the report's 750ms wave entry. */
@Composable
internal fun LyricInstrumentalWave(
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalPlayerContentColor.current
    val infiniteTransition = rememberInfiniteTransition(label = "instrumental-wave")
    val phase0 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "instrumental-wave-0",
    )
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, delayMillis = 50, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "instrumental-wave-1",
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, delayMillis = 100, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "instrumental-wave-2",
    )
    val phases = listOf(phase0, phase1, phase2)
    Canvas(modifier.fillMaxWidth().height(46.dp)) {
        val barWidth = 7.dp.toPx()
        val gap = 9.dp.toPx()
        val totalWidth = barWidth * 3f + gap * 2f
        val startX = (size.width - totalWidth) / 2f
        phases.forEachIndexed { index, phase ->
            val barHeight = 20.dp.toPx() + index * 7.dp.toPx()
            drawRoundRect(
                color = contentColor.copy(alpha = phase.coerceIn(0f, 1f)),
                topLeft = Offset(
                    startX + index * (barWidth + gap),
                    (size.height - barHeight) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
internal fun UpstreamLyricLine(
    line: LyricLine,
    playbackTimeProvider: () -> Long,
    supportsTimedLyrics: Boolean,
    fontScale: Float,
    reduceMotion: Boolean,
    focusProgress: Float,
    timingEffectsStrength: Float,
    timedUnplayedAlpha: Float,
    visualScale: Float,
    focusScaleReservePadding: Dp,
    visualOffsetPx: Float,
    rowAlpha: Float,
    distanceBlurDp: Float,
    focusBlurDp: Float,
    renderingQuality: LyricsRenderingQuality,
    showRuby: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    reserveTranslation: Boolean,
    reserveRomanization: Boolean,
    tapSeekEnabled: Boolean,
    onMeasured: (Int) -> Unit,
    onClick: () -> Unit,
) {
    val contentColor = LocalPlayerContentColor.current
    val flipped = line.agent?.alignment == com.muses.player.core.lyrics.model.LyricAgentAlignment.Flipped
    val lineAlignment = if (flipped) Alignment.End else Alignment.Start
    val lineTextAlign = if (flipped) TextAlign.End else TextAlign.Start
    val requestedBlur = max(max(distanceBlurDp, focusBlurDp), 0f)
    val effectiveBlur = when (renderingQuality) {
        LyricsRenderingQuality.Low -> 0f
        LyricsRenderingQuality.Balanced -> requestedBlur * .55f
        LyricsRenderingQuality.High -> requestedBlur
    }
    val accompanimentBefore = line.accompaniment.filter { it.timeMs < line.timeMs }
    val accompanimentAfter = line.accompaniment.filterNot { it.timeMs < line.timeMs }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height) }
            .graphicsLayer {
                translationY = visualOffsetPx
                scaleX = visualScale
                scaleY = visualScale
                alpha = rowAlpha
                transformOrigin = TransformOrigin(if (flipped) 1f else 0f, 0f)
            }
            .clickable(
                enabled = tapSeekEnabled,
                onClick = { if (tapSeekEnabled) onClick() },
            )
            // The scale origin sits on the flipped-aware start edge, so the
            // reserved gutter goes to the opposite side where the text grows.
            .padding(
                start = if (flipped) 8.dp + focusScaleReservePadding else 8.dp,
                end = if (flipped) 8.dp else 8.dp + focusScaleReservePadding,
            ),
        horizontalAlignment = lineAlignment,
    ) {
        accompanimentBefore.forEach { vocal ->
            TimedAccompaniment(vocal, playbackTimeProvider, fontScale, reduceMotion, focusProgress, renderingQuality, effectiveBlur, showRuby)
        }
        if (showRomanization) {
            RubyLyricText(
                line = line,
                playbackTimeProvider = playbackTimeProvider,
                // Only the current/transitioning line subscribes to the frame
                // clock. Static ruby rows otherwise invalidated every unit at
                // 60 Hz even though their visual result did not change.
                supportsTimedLyrics = supportsTimedLyrics,
                fontScale = fontScale,
                renderingQuality = renderingQuality,
                softBlurDp = effectiveBlur,
                reduceMotion = reduceMotion,
                timingEffectsStrength = timingEffectsStrength,
                unplayedAlpha = timedUnplayedAlpha,
                showRuby = false,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GlyphLyricText(
                line = line,
                playbackTimeProvider = playbackTimeProvider,
                supportsTimedLyrics = supportsTimedLyrics,
                fontScale = fontScale,
                //  iOS sends pseudo-timed LRC through this same
                // renderer. Only genuine long tones expand; ordinary glyphs
                // merely reveal and lift according to the selected mode.
                reduceMotion = reduceMotion,
                timingEffectsStrength = timingEffectsStrength,
                unplayedAlpha = timedUnplayedAlpha,
                renderingQuality = renderingQuality,
                softBlurDp = effectiveBlur,
                showRuby = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        accompanimentAfter.forEach { vocal ->
            TimedAccompaniment(vocal, playbackTimeProvider, fontScale, reduceMotion, focusProgress, renderingQuality, effectiveBlur, showRuby)
        }

        val romanSize = max(UpstreamLyrics.FONT_SIZE_SP * fontScale * SettingsRuntime.lyricRomanizationFontScale, 13f)
        if (!showRomanization && reserveRomanization) {
            val romanHeight = with(LocalDensity.current) { (romanSize * 1.2f).sp.toDp() }
            Spacer(Modifier.height(romanHeight + UpstreamLyrics.ANNOTATION_SPACING_DP.dp))
        }

        val translationSize = max(UpstreamLyrics.FONT_SIZE_SP * fontScale * SettingsRuntime.lyricTranslationFontScale, 13f)
        if (showTranslation) {
            Text(
                text = line.translation.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = UpstreamLyrics.ANNOTATION_SPACING_DP.dp),
                color = contentColor.copy(alpha = SettingsRuntime.lyricTranslationOpacity),
                textAlign = lineTextAlign,
                fontSize = translationSize.sp,
                lineHeight = translationSize.sp * 1.2f,
                fontWeight = SettingsRuntime.lyricFontWeight.composeWeight,
                style = TextStyle(
                    lineBreak = LyricTextLayout.lineBreak,
                    hyphens = androidx.compose.ui.text.style.Hyphens.None,
                    shadow = if (effectiveBlur > .05f) {
                        Shadow(
                            color = contentColor.copy(alpha = .48f),
                            offset = Offset.Zero,
                            blurRadius = effectiveBlur,
                        )
                    } else null,
                ),
            )
        } else if (reserveTranslation) {
            val translationHeight = with(LocalDensity.current) { (translationSize * 1.2f).sp.toDp() }
            Spacer(Modifier.height(translationHeight + UpstreamLyrics.ANNOTATION_SPACING_DP.dp))
        }
    }
}

@Composable
internal fun TimedAccompaniment(
    vocal: com.muses.player.core.lyrics.model.LyricAccompaniment,
    playbackTimeProvider: () -> Long,
    fontScale: Float,
    reduceMotion: Boolean,
    focusProgress: Float,
    renderingQuality: LyricsRenderingQuality,
    softBlurDp: Float,
    showRuby: Boolean,
) {
    val end = vocal.durationMs?.let { vocal.timeMs + it }
        ?: vocal.syllables.maxOfOrNull { it.endTimeMs }
        ?: vocal.timeMs
    val visible = playbackTimeProvider() in (vocal.timeMs - 600L)..(end + 600L)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 } + expandVertically(tween(600)),
        exit = fadeOut(tween(600)) + slideOutVertically(tween(600)) { it / 2 } + shrinkVertically(tween(600)),
    ) {
        val vocalLine = LyricLine(vocal.timeMs, vocal.durationMs, vocal.text, vocal.syllables, agent = vocal.agent)
        GlyphLyricText(
            line = vocalLine,
            playbackTimeProvider = playbackTimeProvider,
            supportsTimedLyrics = vocal.syllables.isNotEmpty(),
            fontScale = fontScale * .68f,
            reduceMotion = reduceMotion,
            timingEffectsStrength = focusProgress,
            renderingQuality = renderingQuality,
            softBlurDp = softBlurDp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { alpha = .72f },
            isBackground = true,
            showRuby = showRuby,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RubyLyricText(
    line: LyricLine,
    playbackTimeProvider: () -> Long,
    supportsTimedLyrics: Boolean,
    fontScale: Float,
    renderingQuality: LyricsRenderingQuality,
    softBlurDp: Float = 0f,
    reduceMotion: Boolean = false,
    timingEffectsStrength: Float = 1f,
    unplayedAlpha: Float = .4f,
    modifier: Modifier = Modifier,
    showRuby: Boolean = true,
) {
    val contentColor = LocalPlayerContentColor.current
    // 有原生逐词时序则上方注音并整组换行；其余整句读音独立成行。
    val paired = remember(line) { com.muses.player.core.lyrics.aligner.TimedPronunciation.rubyLine(line) }
    Column(modifier = modifier) {
        GlyphLyricText(
            line = paired ?: line,
            playbackTimeProvider = playbackTimeProvider,
            supportsTimedLyrics = supportsTimedLyrics,
            fontScale = fontScale,
            reduceMotion = reduceMotion,
            timingEffectsStrength = timingEffectsStrength,
            unplayedAlpha = unplayedAlpha,
            renderingQuality = renderingQuality,
            softBlurDp = softBlurDp,
            showRuby = paired != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (paired == null && !line.romanization.isNullOrBlank()) {
            Text(
                text = line.romanization.orEmpty(),
                style = TextStyle(lineBreak = LyricTextLayout.lineBreak, hyphens = androidx.compose.ui.text.style.Hyphens.None),
                color = contentColor.copy(alpha = SettingsRuntime.lyricRomanizationOpacity),
                modifier = Modifier.fillMaxWidth().padding(top = UpstreamLyrics.ANNOTATION_SPACING_DP.dp),
                fontSize = max(
                    UpstreamLyrics.FONT_SIZE_SP * fontScale * SettingsRuntime.lyricRomanizationFontScale,
                    13f,
                ).sp,
                lineHeight = (
                    max(
                        UpstreamLyrics.FONT_SIZE_SP * fontScale * SettingsRuntime.lyricRomanizationFontScale,
                        13f,
                    ) * 1.2f
                ).sp,
                fontWeight = SettingsRuntime.lyricFontWeight.composeWeight,
                textAlign = if (line.agent?.alignment == com.muses.player.core.lyrics.model.LyricAgentAlignment.Flipped) TextAlign.End else TextAlign.Start,
            )
        }
    }

}


internal data class LyricInterlude(
    val startTimeMs: Long,
    val countdownEndTimeMs: Long,
    val followingLyricTimeMs: Long,
    val followingLyricIndex: Int,
)

@Composable
internal fun LyricInterludeCountdown(
    interlude: LyricInterlude,
    playbackTimeProvider: () -> Long,
    reduceMotion: Boolean,
    visualScale: Float,
    visualOffsetPx: Float,
    rowAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalPlayerContentColor.current
    Canvas(
        modifier
            .fillMaxWidth(0.28f)
            .height(48.dp)
            .graphicsLayer {
                translationY = visualOffsetPx
                scaleX = visualScale
                scaleY = visualScale
                alpha = rowAlpha
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        val now = playbackTimeProvider()
        if (now < interlude.startTimeMs) return@Canvas
        val duration = (interlude.countdownEndTimeMs - interlude.startTimeMs).coerceAtLeast(1L)
        val elapsed = (now - interlude.startTimeMs).coerceIn(0L, duration)
        val remaining = interlude.countdownEndTimeMs - now
        if (remaining <= 0L) return@Canvas
        val durationFloat = duration.toFloat()
        val elapsedFloat = elapsed.toFloat()
        val baseRadius = 5.dp.toPx()
        val gap = 18.dp.toPx()
        val centerY = size.height / 2f
        repeat(3) { index ->
            val segmentStart = durationFloat * index / 3f
            val progress = ((elapsedFloat - segmentStart) / (durationFloat / 3f)).coerceIn(0.25f, 1f)
            val fadeOut = (remaining / 375f).coerceIn(0f, 1f)
            val breathe = if (reduceMotion) 1f else 1f +
                sin(elapsed.toDouble() / 1_500.0 * 2.0 * Math.PI).toFloat() * 0.05f
            drawCircle(
                color = contentColor.copy(alpha = progress * fadeOut),
                radius = baseRadius * breathe * if (index == 2) (1f + progress * 0.18f) else 1f,
                center = Offset(baseRadius + index * gap, centerY),
            )
        }
    }
}

@Composable
internal fun GlyphLyricText(
    line: LyricLine,
    playbackTimeProvider: () -> Long,
    supportsTimedLyrics: Boolean,
    fontScale: Float,
    reduceMotion: Boolean,
    timingEffectsStrength: Float,
    unplayedAlpha: Float = SettingsRuntime.lyricInactiveOpacity,
    renderingQuality: LyricsRenderingQuality,
    softBlurDp: Float = 0f,
    modifier: Modifier = Modifier,
    isBackground: Boolean = false,
    showRuby: Boolean = true,
) {
    AmllWordLyricText(
        line = line,
        playbackTimeProvider = playbackTimeProvider,
        supportsTimedLyrics = supportsTimedLyrics,
        fontScale = fontScale,
        reduceMotion = reduceMotion,
        timingEffectsStrength = timingEffectsStrength,
        unplayedAlpha = unplayedAlpha,
        renderingQuality = renderingQuality,
        background = isBackground,
        showRuby = showRuby,
        modifier = modifier,
    )
}
