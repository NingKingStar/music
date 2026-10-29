package com.muses.player.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muses.player.core.ui.components.MarqueeText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 目录平台确认的原唱标记。 */
@Composable
fun SongPerformanceBadge(label: String?) {
    if (label == null) return
    val scheme = MiuixTheme.colorScheme
    Text(label, fontSize = 10.sp, color = scheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(scheme.primary.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 2.dp))
}

/** 版本小标紧跟歌名，长标题先省略，为标签保留空间。 */
@Composable
fun SongTitleWithBadge(
    title: String,
    label: String?,
    style: TextStyle = MiuixTheme.textStyles.body2,
    color: Color = MiuixTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f, fill = false), style = style,
            color = color, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            SongPerformanceBadge(label)
        }
    }
}

/** 副标题依次展示喜欢标记、音质小标、艺术家与专辑。 */
@Composable
fun SongSubtitleWithQuality(
    subtitle: String,
    label: String?,
    style: TextStyle = MiuixTheme.textStyles.footnote1,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    isLiked: Boolean = false,
) {
    if (subtitle.isBlank() && label == null && !isLiked) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (isLiked) {
            SongFavoriteMark()
            if (label != null || subtitle.isNotBlank()) Spacer(Modifier.width(5.dp))
        }
        if (label != null) {
            SongQualityBadge(com.muses.player.core.model.shortAudioQualityLabel(label) ?: label)
            if (subtitle.isNotBlank()) Spacer(Modifier.width(5.dp))
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, modifier = Modifier.weight(1f), style = style.copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ), color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 音质与格式标签共用紧凑字标；格式标签只表示格式，不据此推断音质。 */
@Composable
private fun SongQualityBadge(label: String) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    // 保留 QQ 音乐的绿色、橙色与金色分档，使用柔和色块底，不加描边。
    val foreground = when (label) {
        "HQ" -> if (dark) Color(0xFF45D99A) else Color(0xFF20BC78)
        "无损", "SQ" -> if (dark) Color(0xFFFFB15C) else Color(0xFFFF8A16)
        "Hi-Res" -> if (dark) Color(0xFFEAC66D) else Color(0xFFAB812B)
        else -> if (dark) Color(0xFFAEB2B8) else Color(0xFF777C83)
    }
    val fill = if (label == "Hi-Res") {
        Brush.linearGradient(if (dark) listOf(Color(0xFF665022), Color(0xFF54421E))
            else listOf(Color(0xFFFFE793), Color(0xFFF6CE5B)))
    } else {
        val background = foreground.copy(alpha = if (dark) 0.22f else 0.13f)
        Brush.linearGradient(listOf(background, background))
    }
    Box(
        // 副标题的可见字形略低于行框中心，字标随之下移以保持视觉居中。
        modifier = Modifier.offset(y = 1.dp).clip(RoundedCornerShape(2.dp))
            .background(fill)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (label == "HQ" || label == "SQ") {
            Canvas(Modifier.size(11.dp, 7.dp).semantics { contentDescription = label }) {
                // 以 16 × 10 的字标坐标绘制，避免不同系统字体改变字形。
                val sx = size.width / 16f
                val sy = size.height / 10f
                val letters = Path().apply {
                    fun move(x: Float, y: Float) = moveTo(x * sx, y * sy)
                    fun line(x: Float, y: Float) = lineTo(x * sx, y * sy)
                    if (label == "HQ") {
                        move(1f, 0.8f); line(1f, 9.2f)
                        move(6f, 0.8f); line(6f, 9.2f)
                        move(1f, 5f); line(6f, 5f)
                    } else {
                        move(6f, 1f); line(1.8f, 1f); line(1f, 1.8f)
                        line(1f, 4.2f); line(1.8f, 5f); line(5.2f, 5f)
                        line(6f, 5.8f); line(6f, 8.2f); line(5.2f, 9f); line(1f, 9f)
                    }
                    move(10f, 1f); line(13.8f, 1f); line(14.5f, 1.7f)
                    line(14.5f, 8.3f); line(13.8f, 9f); line(10f, 9f)
                    line(9.3f, 8.3f); line(9.3f, 1.7f); close()
                    move(12.5f, 7f); line(15.5f, 9.5f)
                }
                drawPath(letters, foreground, style = Stroke(width = 1.4f * sx))
            }
        } else if (label == "FLAC" || label == "MP3") {
            FormatWordmark(label, foreground)
        } else {
            Text(label,
                modifier = Modifier.semantics { contentDescription = label },
                style = TextStyle(fontSize = 9.sp, lineHeight = 10.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    fontWeight = if (label == "Hi-Res") FontWeight.Medium else FontWeight.Bold),
                color = foreground, maxLines = 1)
        }
    }
}

/** 与 HQ / SQ 一致的几何字形，避免普通字体的上下留白影响标签对齐。 */
@Composable
private fun FormatWordmark(label: String, color: Color) {
    val width = label.length * 7f - 2f
    Canvas(Modifier.size((width * 0.7f).dp, 7.dp).semantics { contentDescription = label }) {
        val sx = size.width / width
        val sy = size.height / 10f
        val letters = Path().apply {
            label.forEachIndexed { index, character ->
                val origin = index * 7f
                fun move(x: Float, y: Float) = moveTo((origin + x) * sx, y * sy)
                fun line(x: Float, y: Float) = lineTo((origin + x) * sx, y * sy)
                when (character) {
                    'F' -> {
                        move(5f, 1f); line(0.7f, 1f); line(0.7f, 9f)
                        move(0.7f, 5f); line(4.5f, 5f)
                    }
                    'L' -> { move(0.7f, 1f); line(0.7f, 9f); line(5f, 9f) }
                    'A' -> {
                        move(0.7f, 9f); line(0.7f, 2f); line(1.7f, 1f)
                        line(4f, 1f); line(5f, 2f); line(5f, 9f)
                        move(0.7f, 5.5f); line(5f, 5.5f)
                    }
                    'C' -> {
                        move(5f, 1f); line(1.7f, 1f); line(0.7f, 2f)
                        line(0.7f, 8f); line(1.7f, 9f); line(5f, 9f)
                    }
                    'M' -> {
                        move(0.7f, 9f); line(0.7f, 1f); line(2.85f, 4.5f)
                        line(5f, 1f); line(5f, 9f)
                    }
                    'P' -> {
                        move(0.7f, 9f); line(0.7f, 1f); line(4f, 1f)
                        line(5f, 2f); line(5f, 4f); line(4f, 5f); line(0.7f, 5f)
                    }
                    '3' -> {
                        move(0.7f, 1f); line(4f, 1f); line(5f, 2f)
                        line(5f, 8f); line(4f, 9f); line(0.7f, 9f)
                        move(2f, 5f); line(5f, 5f)
                    }
                }
            }
        }
        drawPath(letters, color, style = Stroke(width = 1.4f * sx))
    }
}
