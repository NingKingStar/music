package com.muses.player.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muses.player.core.model.lyrics.DesktopLyricsWord
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 两端透明悬浮歌词：单击展开控制面板，拖动仅作用于歌词区域。
 *
 * 歌词底色是透明桌面，文字用轻薄阴影保证可读；已播放行跟随封面强调色，
 * 但强调色本身是「提色 + 大量白色柔化」的结果（偏粉白），直接用在歌词上会显得浅，
 * 因此这里按同色系还原成更浓的版本。
 */
@Composable
fun DesktopLyricsCard(primary: String, secondary: String?, onClose: () -> Unit, modifier: Modifier = Modifier,
    words: List<DesktopLyricsWord> = emptyList(), positionMs: Long = 0, isPlaying: Boolean = false,
    accentColor: Color? = null, controlsVisible: Boolean = false,
    onLock: (() -> Unit)? = null, onTap: (() -> Unit)? = null,
    title: String = "Muses", fontSize: Int = 22, selectedColor: Color? = null,
    onPrevious: () -> Unit = {}, onPlayPause: () -> Unit = {}, onNext: () -> Unit = {},
    onColor: (Long) -> Unit = {}, onFontSize: (Int) -> Unit = {},
    onContentHeightChanged: (Int) -> Unit = {}, onEditingStyleChanged: (Boolean) -> Unit = {}, showStyleControls: Boolean = true) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 0.75f), 3f)
    val vivid = selectedColor ?: accentColor?.let(::vividLyricColor)
    val interaction = remember { MutableInteractionSource() }
    var editingStyle by remember(controlsVisible) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
        .onSizeChanged { onContentHeightChanged(it.height) }.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(
            interactionSource = interaction, indication = null,
            onClickLabel = if (controlsVisible) "收起歌词操作" else "显示歌词操作", onClick = onTap) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally) {
            DesktopWordLyrics(primary, words, positionMs, isPlaying,
                style = MiuixTheme.textStyles.main.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, shadow = shadow, textAlign = TextAlign.Center), accentColor = vivid)
            secondary?.let {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MarqueeText(it, textAlign = TextAlign.Center,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = (fontSize * 0.64f).sp, lineHeight = (fontSize * 0.64f + 6).sp,
                        color = vivid ?: Color.White, shadow = shadow, textAlign = TextAlign.Center),
                    color = vivid ?: Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (controlsVisible) {
            // 悬浮操作条：只留最常按的播放控制与锁定/关闭，样式调节归到歌词显示弹层。
            Row(Modifier.wrapContentWidth().clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.52f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                OverlayGlyph(TablerIcons.SkipPreviousFill, "上一首", onClick = onPrevious)
                OverlayGlyph(if (isPlaying) TablerIcons.PauseFill else TablerIcons.PlayFill,
                    if (isPlaying) "暂停" else "播放", emphasized = true, onClick = onPlayPause)
                OverlayGlyph(TablerIcons.SkipNextFill, "下一首", onClick = onNext)
                OverlayDivider()
                if (showStyleControls) OverlayGlyph(TablerIcons.Settings,
                    if (editingStyle) "收起歌词样式" else "调整歌词样式", active = editingStyle) {
                    editingStyle = !editingStyle
                    onEditingStyleChanged(editingStyle)
                }
                onLock?.let { lock -> OverlayGlyph(TablerIcons.Lock, "锁定后点击穿透", onClick = lock) }
                OverlayGlyph(TablerIcons.Close, "关闭桌面歌词", onClick = onClose)
            }
            if (showStyleControls && editingStyle) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().wrapContentHeight(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf(0xFF007AFFL to "蓝色", 0xFF00C853L to "绿色", 0xFFE53935L to "红色", 0xFFFFFFFFL to "白色").forEach { (argb, label) ->
                            val chosen = selectedColor == Color(argb.toInt())
                            Box(Modifier.size(28.dp).clip(CircleShape)
                                .clickable(onClickLabel = "歌词颜色：$label") { onColor(argb) }
                                .semantics { selected = chosen }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(22.dp)
                                    .background(Color(argb.toInt()), CircleShape)
                                    .border(if (chosen) 2.dp else 1.dp,
                                        if (chosen) Color.White else Color.White.copy(alpha = 0.28f), CircleShape))
                            }
                        }
                        OverlayGlyph(TablerIcons.Refresh, "跟随封面自动配色", active = selectedColor == null) { onColor(0L) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (selectedColor == null) "自动配色" else "自选颜色", modifier = Modifier.weight(1f),
                            style = MiuixTheme.textStyles.body2, color = Color.White.copy(alpha = 0.72f))
                        OverlayGlyph(TablerIcons.Remove, "缩小歌词", enabled = fontSize > 14) { onFontSize(fontSize - 2) }
                        Text("$fontSize", style = MiuixTheme.textStyles.body2, color = Color.White)
                        OverlayGlyph(TablerIcons.Add, "放大歌词", enabled = fontSize < 40) { onFontSize(fontSize + 2) }
                    }
                }
            }
        }
    }
}

/** 悬浮条上的单个图标按钮：小触控区、无涟漪、按压轻微缩放，贴合条形容器。 */
@Composable
private fun OverlayGlyph(icon: ImageVector, label: String, emphasized: Boolean = false, active: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "overlay-glyph-scale")
    Box(Modifier.size(if (emphasized) 36.dp else 32.dp).scale(scale).clip(CircleShape)
        .background(if (pressed) Color.White.copy(alpha = 0.14f) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.32f)
                active || emphasized -> Color.White.copy(alpha = 0.95f)
                else -> Color.White.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(if (emphasized) 20.dp else 17.dp))
    }
}

@Composable
private fun OverlayDivider() {
    Box(Modifier.padding(horizontal = 2.dp).size(1.dp, 16.dp).background(Color.White.copy(alpha = 0.16f)))
}

/**
 * 封面提色最终产出 pastel = 0.78 + 0.22 × 原色（见 coverContentColor），
 * 用在透明桌面上偏浅发灰。这里反解回原色、拉满饱和后再少量回白，
 * 得到同色系但更浓、仍保证亮度的歌词色。
 */
internal fun vividLyricColor(color: Color): Color {
    fun recover(channel: Float) = ((channel - 0.78f) / 0.22f).coerceIn(0f, 1f)
    val red = recover(color.red)
    val green = recover(color.green)
    val blue = recover(color.blue)
    val peak = maxOf(red, green, blue)
    if (peak <= 0.02f) return Color.White
    val scale = 1f / peak
    fun vivid(channel: Float) = 0.18f + 0.82f * (channel * scale).coerceIn(0f, 1f)
    return Color(vivid(red), vivid(green), vivid(blue), 1f)
}

/** 按实际字形区域逐字填色，支持换行；帧时钟只补足播放进度采样之间的空隙。 */
@Composable
private fun DesktopWordLyrics(text: String, words: List<DesktopLyricsWord>, positionMs: Long, isPlaying: Boolean, style: TextStyle,
    accentColor: Color?) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var displayPosition by remember(text) { mutableStateOf(positionMs) }
    val masks = remember(text, words, layout) {
        val result = layout
        words.map { word ->
            val start = word.startOffset.coerceIn(0, text.length)
            val end = word.endOffset.coerceIn(start, text.length)
            word to if (result == null) emptyList() else (start until end).mapNotNull { offset ->
                val line = result.getLineForOffset(offset)
                if (offset >= result.getLineEnd(line, visibleEnd = true)) null
                else result.getBoundingBox(offset).takeIf { it.width > 0f }?.let { bounds ->
                    bounds to (result.getBidiRunDirection(offset) == ResolvedTextDirection.Rtl)
                }
            }
        }
    }
    LaunchedEffect(text, words, positionMs, isPlaying) {
        displayPosition = positionMs
        if (isPlaying && words.isNotEmpty()) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { frame -> displayPosition = positionMs + (frame - start) / 1_000_000 }
            }
        }
    }
    // 滚动内容按自然宽度测量，短歌词由外层居中，长歌词仍占满可用宽度滚动。
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(Modifier.autoMarquee()) {
        Text(text, modifier = Modifier.fillMaxWidth(), style = style,
            color = if (words.isEmpty()) accentColor ?: Color.White else Color.White.copy(alpha = 0.45f),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, onTextLayout = { layout = it })
        if (words.isNotEmpty()) {
            Text(text, style = style.copy(shadow = null), color = accentColor ?: Color(0xFF7FD4FF), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }.drawWithContent {
                    val path = Path()
                    for ((word, characters) in masks) {
                        val progress = word.progressAt(displayPosition)
                        if (progress <= 0f) continue
                        var remaining = characters.sumOf { it.first.width.toDouble() }.toFloat() * progress
                        for ((bounds, rtl) in characters) {
                            val filled = remaining.coerceIn(0f, bounds.width)
                            if (filled > 0f) {
                                path.addRect(bounds.copy(
                                    left = if (rtl) bounds.right - filled else bounds.left,
                                    right = if (rtl) bounds.right else bounds.left + filled))
                            }
                            remaining -= bounds.width
                        }
                    }
                    clipPath(path) { this@drawWithContent.drawContent() }
                })
        }
    }
    }
}
