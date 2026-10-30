package com.muses.player.feature.player.lyric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.muses.player.core.lyrics.model.LyricAgentAlignment
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.ui.components.LocalPlayerContentColor

private data class AmllPiece(
    val layout: TextLayoutResult,
    val origin: Offset,
    val bounds: Rect,
    val character: Int,
    val isRuby: Boolean = false,
)

private data class AmllFragment(
    val bounds: Rect,
    val pieces: List<AmllPiece>,
    val offsetInWord: Float,
    val rtl: Boolean,
)

/** 普通词保留整词塑形，只有上游标记为强调的词才拆成完整字素。 */
@Composable
internal fun AmllWordLyricText(
    line: LyricLine,
    playbackTimeProvider: () -> Long,
    supportsTimedLyrics: Boolean,
    fontScale: Float,
    reduceMotion: Boolean,
    timingEffectsStrength: Float,
    unplayedAlpha: Float,
    renderingQuality: LyricsRenderingQuality,
    background: Boolean,
    modifier: Modifier = Modifier,
    showRuby: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val contentColor = LocalPlayerContentColor.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val fontFamily = LocalFontFamily.current
    val systemDurationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val motionDisabled = reduceMotion || systemDurationScale == 0f
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val style = TextStyle(
            color = contentColor,
            fontFamily = fontFamily,
            fontSize = (UpstreamLyrics.FONT_SIZE_SP * fontScale).sp,
            lineHeight = (UpstreamLyrics.LINE_HEIGHT_SP * fontScale).sp,
            fontWeight = SettingsRuntime.lyricFontWeight.composeWeight,
            lineBreak = LyricTextLayout.lineBreak,
            hyphens = androidx.compose.ui.text.style.Hyphens.None,
            textAlign = if (line.agent?.alignment == LyricAgentAlignment.Flipped) TextAlign.End else TextAlign.Start,
        )
        val protectedText = remember(line.text) { LyricTextLayout.protectWords(line.text) }
        val words = remember(line) { AmllWordEffects.words(line) }
        val rubyLayouts = remember(words, style, showRuby) {
            words.map { word ->
                if (!showRuby || word.ruby.isEmpty()) null else {
                    val base = textMeasurer.measure(AnnotatedString(line.text.substring(word.range.start, word.range.end)),
                        style.copy(textAlign = TextAlign.Start), softWrap = false)
                    val ruby = textMeasurer.measure(AnnotatedString(word.ruby.joinToString("") { it.text }),
                        style.copy(fontSize = style.fontSize * .5f, lineHeight = style.fontSize * .5f,
                            textAlign = TextAlign.Start), softWrap = false)
                    base to ruby
                }
            }
        }
        // 把注音和主字作为一个不可拆行单元，给较宽的注音预留真实宽度。
        val placeholders = remember(words, rubyLayouts, density) {
            words.mapIndexedNotNull { index, word ->
                rubyLayouts[index]?.let { (base, ruby) ->
                    AnnotatedString.Range(Placeholder(
                        with(density) { maxOf(base.size.width, ruby.size.width).toSp() },
                        with(density) { (base.size.height + ruby.size.height).toSp() },
                        PlaceholderVerticalAlign.Bottom,
                    ), protectedText.layoutOffset(word.range.start), protectedText.layoutOffset(word.range.end))
                }
            }
        }
        val layout = remember(line.text, width, style, placeholders) {
            textMeasurer.measure(AnnotatedString(protectedText.text), style,
                constraints = Constraints(minWidth = width, maxWidth = width), placeholders = placeholders)
        }
        androidx.compose.runtime.SideEffect { onTextLayout?.invoke(layout) }
        val emPx = with(density) { style.fontSize.toPx() }
        val fragments = remember(words, layout, style) {
            var rubyIndex = 0
            words.mapIndexed { wordIndex, word ->
                val rubyPair = rubyLayouts[wordIndex]
                if (rubyPair != null) {
                    val bounds = layout.placeholderRects[rubyIndex++] ?: Rect.Zero
                    val (base, ruby) = rubyPair
                    val baseOrigin = Offset((bounds.width - base.size.width) / 2f, ruby.size.height.toFloat())
                    val rubyOrigin = Offset((bounds.width - ruby.size.width) / 2f, 0f)
                    val basePieces = if (word.emphasis == null) listOf(
                        AmllPiece(base, baseOrigin, Rect(baseOrigin, Size(base.size.width.toFloat(), base.size.height.toFloat())), 0),
                    ) else word.characters.mapIndexed { character, range ->
                        val box = base.getBoundingBox(range.start - word.range.start)
                        val glyph = textMeasurer.measure(AnnotatedString(line.text.substring(range.start, range.end)),
                            style.copy(textAlign = TextAlign.Start), softWrap = false)
                        AmllPiece(glyph, baseOrigin + Offset(box.left, base.firstBaseline - glyph.firstBaseline),
                            box.translate(baseOrigin), character)
                    }
                    return@mapIndexed listOf(AmllFragment(bounds, basePieces +
                        AmllPiece(ruby, rubyOrigin, Rect(rubyOrigin, Size(ruby.size.width.toFloat(), ruby.size.height.toFloat())), 0, isRuby = true),
                        0f, layout.getBidiRunDirection(protectedText.layoutOffset(word.range.start)) == ResolvedTextDirection.Rtl))
                }
                var widthBefore = 0f
                word.characters.withIndex().groupBy { layout.getLineForOffset(protectedText.layoutOffset(it.value.start)) }.map { (lineIndex, characters) ->
                    val boxes = characters.map { layout.getBoundingBox(protectedText.layoutOffset(it.value.start)) }
                    val bounds = Rect(boxes.minOf { it.left }, layout.getLineTop(lineIndex),
                        boxes.maxOf { it.right }, layout.getLineBottom(lineIndex))
                    val ranges = if (word.emphasis != null) characters.map { it.index to it.value }
                    else listOf(0 to AmllWordEffects.TextRange(characters.first().value.start, characters.last().value.end))
                    val pieces = ranges.map { (character, range) ->
                        val pieceBoxes = (range.start until range.end).map { layout.getBoundingBox(protectedText.layoutOffset(it)) }
                        val pieceBounds = Rect(pieceBoxes.minOf { it.left }, bounds.top,
                            pieceBoxes.maxOf { it.right }, bounds.bottom)
                        val pieceLayout = textMeasurer.measure(
                            AnnotatedString(line.text.substring(range.start, range.end)),
                            style.copy(textAlign = TextAlign.Start), softWrap = false,
                        )
                        AmllPiece(pieceLayout,
                            Offset(pieceBounds.left - bounds.left, layout.getLineBaseline(lineIndex) - bounds.top - pieceLayout.firstBaseline),
                            pieceBounds.translate(-bounds.left, -bounds.top), character)
                    }
                    AmllFragment(bounds, pieces, widthBefore,
                        layout.getBidiRunDirection(protectedText.layoutOffset(characters.first().value.start)) == ResolvedTextDirection.Rtl)
                        .also { widthBefore += bounds.width }
                }
            }
        }
        val mask = remember(words, fragments) {
            AmllWordEffects.MaskTimeline(words, fragments.map { row -> row.sumOf { it.bounds.width.toDouble() }.toFloat() })
        }
        val untimedRanges = remember(line.text, words) {
            AmllWordEffects.graphemes(line.text).filter { range ->
                words.none { range.start >= it.range.start && range.start < it.range.end } &&
                    line.text.substring(range.start, range.end).isNotBlank()
            }
        }
        val layerPaint = remember { Paint() }
        val height = with(density) { layout.size.height.toDp() }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if ((!supportsTimedLyrics && placeholders.isEmpty()) || words.isEmpty()) {
                drawText(layout, contentColor)
                return@Canvas
            }
            val time = if (supportsTimedLyrics) playbackTimeProvider() else 0L
            // 避免不完整来源数据中的未定时标点消失。
            untimedRanges.forEach { range ->
                val box = layout.getBoundingBox(protectedText.layoutOffset(range.start))
                clipRect(box.left, box.top, box.right, box.bottom) {
                    drawText(layout, contentColor.copy(alpha = if (supportsTimedLyrics) unplayedAlpha else 1f))
                }
            }
            words.forEachIndexed { wordIndex, word ->
                val parent = AmllWordEffects.visual(word, 0, time, background, motionDisabled || !supportsTimedLyrics)
                fragments[wordIndex].forEach { fragment ->
                    val fade = (rubyLayouts[wordIndex]?.first?.size?.height?.toFloat() ?: fragment.bounds.height) * .5f
                    val front = mask.front(wordIndex, time, fade) - fragment.offsetInWord
                    // 遮罩属于词容器，字素的上浮和缩放发生在遮罩内；不会让渐变跟着每个字重新起步。
                    withTransform({ translate(fragment.bounds.left, fragment.bounds.top - parent.parentLiftEm * emPx) }) {
                        val padding = emPx
                        val layerBounds = Rect(-padding, -padding, fragment.bounds.width + padding, fragment.bounds.height + padding)
                        drawContext.canvas.saveLayer(layerBounds, layerPaint)
                        try {
                            fragment.pieces.forEach { piece ->
                                val visual = AmllWordEffects.visual(word, piece.character, time, background,
                                    motionDisabled || !supportsTimedLyrics || piece.isRuby)
                                withTransform({
                                    translate(visual.translateXEm * emPx, visual.translateYEm * emPx)
                                    scale(visual.scale, visual.scale, piece.bounds.center)
                                }) {
                                    if (visual.glowAlpha > .0001f && SettingsRuntime.lyricGlowEnabled &&
                                        renderingQuality != LyricsRenderingQuality.Low) {
                                        drawText(piece.layout, color = Color.Transparent, topLeft = piece.origin,
                                            shadow = Shadow(
                                                contentColor.copy(alpha = (visual.glowAlpha * SettingsRuntime.lyricGlowStrength).coerceIn(0f, 1f)),
                                                Offset.Zero, (visual.glowRadiusEm * emPx).coerceAtLeast(.01f),
                                            ))
                                    }
                                    drawText(piece.layout, contentColor, topLeft = piece.origin)
                                }
                            }
                            val floor = if (supportsTimedLyrics) unplayedAlpha.coerceIn(0f, 1f) else 1f
                            val maskBrush = if (fragment.rtl) {
                                Brush.horizontalGradient(listOf(Color.White.copy(alpha = floor), Color.White),
                                    fragment.bounds.width - front - fade, fragment.bounds.width - front)
                            } else {
                                Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = floor)), front, front + fade)
                            }
                            drawRect(maskBrush, Offset(-padding, -padding),
                                Size(layerBounds.width, layerBounds.height), blendMode = BlendMode.DstIn)
                        } finally {
                            drawContext.canvas.restore()
                        }
                    }
                }
            }
        }
    }
}
