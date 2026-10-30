package com.muses.player.feature.player.lyric

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.feature.player.backdrop.FlowingLightBackdrop
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

internal object LyricsSheetWindowPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/** 独立的播放器弹层，不借用应用设置页的白底与全局强调色。 */
@Composable
internal fun ImmersiveLyricsSettingsSheet(
    coverUri: String?, onDismiss: () -> Unit, content: @Composable (() -> Unit) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var panelHeight by remember { mutableIntStateOf(0) }
    val dismiss = remember { { closing = true } }
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(closing) {
        progress.animateTo(if (closing) 0f else 1f,
            tween(if (closing) 180 else 260, easing = FastOutSlowInEasing))
        if (closing) latestDismiss()
    }
    Popup(popupPositionProvider = LyricsSheetWindowPosition,
        onDismissRequest = dismiss, properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = "歌词显示" }) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = .28f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
            ImmersiveLyricsSurface(coverUri,
                Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * .9f).onSizeChanged { panelHeight = it.height }
                    .graphicsLayer {
                        translationY = panelHeight * (1f - progress.value)
                        alpha = progress.value
                    }) { content(dismiss) }
        }
    }
}

@Composable
internal fun ImmersiveLyricsSurface(coverUri: String?, modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(modifier.clip(shape).border(1.dp, Color.White.copy(alpha = .1f), shape)) {
        // 与播放器复用同一封面处理；只绘制静态帧，避免弹层再开一条持续流光循环。
        FlowingLightBackdrop(coverUri, true, Modifier.matchParentSize(), flowSpeed = 0f)
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .2f)))
        Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            content()
        }
    }
}

private val LyricsInk = Color.White
private val LyricsSecondary = Color.White.copy(alpha = .74f)

@Composable
internal fun LyricsSettingsContent(
    translation: Boolean, romanization: Boolean, translated: Boolean, romanized: Boolean,
    translationHint: String, textSize: Long, spacing: Long, wordHighlight: Boolean, loading: Boolean,
    onTranslation: (Boolean) -> Unit, onRomanization: (Boolean) -> Unit,
    onTextSize: (Long) -> Unit, onSpacing: (Long) -> Unit, onHighlight: (Boolean) -> Unit,
    onRetry: (() -> Unit)?, previewLine: LyricLine? = null,
    nextPreviewLine: LyricLine? = null, onClose: () -> Unit = {}, romanizationHint: String = if (romanized) "" else "暂无读音",
) {
    var previewSize by remember(textSize) { mutableLongStateOf(textSize) }
    var previewSpacing by remember(spacing) { mutableLongStateOf(spacing) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        val dismissDistance = with(LocalDensity.current) { 36.dp.toPx() }
        val latestClose by rememberUpdatedState(onClose)
        Box(Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
                    if (dragged > dismissDistance) latestClose()
                }) { change, delta -> change.consume(); dragged += delta }
            }.semantics {
                contentDescription = "收起歌词设置"
                onClick("收起") { latestClose(); true }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp, 3.dp).background(LyricsInk.copy(alpha = .24f), RoundedCornerShape(2.dp)))
        }
        Text("歌词显示", color = LyricsInk, fontSize = 19.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            // 预览当前实际歌词，不生成读音或占位译文；拖动滑杆时立即反映显示变化。
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(LyricsInk.copy(alpha = .055f)).padding(16.dp)) {
                Text("当前句预览", color = LyricsSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                Text(previewLine?.text?.takeIf { it.isNotBlank() } ?: "暂无歌词", color = LyricsInk,
                    fontSize = previewSize.toFloat().sp, lineHeight = (previewSize * 1.35f).sp,
                    fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (romanization && !previewLine?.romanization.isNullOrBlank()) Text(previewLine!!.romanization!!,
                    color = LyricsSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (translation && !previewLine?.translation.isNullOrBlank()) Text(previewLine!!.translation!!,
                    color = LyricsSecondary, fontSize = (previewSize * .65f).sp,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (nextPreviewLine != null) {
                    Spacer(Modifier.height(previewSpacing.toFloat().dp))
                    Text(nextPreviewLine.text, color = LyricsInk.copy(alpha = .48f),
                        fontSize = (previewSize * .85f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LyricsDisplayChoice("译文", translation, translated, onTranslation, Modifier.weight(1f))
                LyricsDisplayChoice("音译", romanization, romanized, onRomanization, Modifier.weight(1f))
            }
            val statusHints = listOf(translationHint, romanizationHint).filter { it.isNotBlank() }
            if (statusHints.isNotEmpty()) Text(statusHints.joinToString(" · "),
                color = LyricsSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            Spacer(Modifier.height(16.dp))
            PlayerLyricSlider("字号", textSize, 18L..32L, 2, "小", "大", { previewSize = it }, onTextSize)
            PlayerLyricSlider("句间距", spacing, 12L..36L, 4, "紧凑", "宽松", { previewSpacing = it }, onSpacing)
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("逐字高亮", color = LyricsInk, fontSize = 14.sp)
                    Text("跟随演唱点亮歌词", color = LyricsSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Switch(checked = wordHighlight, onCheckedChange = onHighlight,
                    colors = SwitchDefaults.switchColors(
                        checkedThumbColor = Color(0xFF20222A), checkedTrackColor = LyricsInk,
                        uncheckedThumbColor = LyricsInk.copy(alpha = .8f),
                        uncheckedTrackColor = LyricsInk.copy(alpha = .18f)),
                    modifier = Modifier.semantics { contentDescription = "逐字高亮" })
            }
            if (onRetry != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp)
                .height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !loading, role = Role.Button) { onRetry(); onClose() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if (loading) "正在重新获取…" else "重新获取歌词", fontSize = 12.sp, color = LyricsSecondary)
            }
        }
    }
}

@Composable
private fun LyricsDisplayChoice(label: String, checked: Boolean, available: Boolean,
    onChecked: (Boolean) -> Unit, modifier: Modifier,
) {
    val selected = checked && available
    Box(modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
        .background(LyricsInk.copy(alpha = if (selected) .16f else .035f))
        .border(1.dp, LyricsInk.copy(alpha = if (selected) .45f else .12f), RoundedCornerShape(12.dp))
        .clickable(enabled = available, role = Role.Switch) { onChecked(!checked) }
        .semantics {
            contentDescription = label
            this.selected = selected
            stateDescription = if (!available) "暂无" else if (selected) "已开启" else "已关闭"
        }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = LyricsInk.copy(alpha = if (available) 1f else .5f),
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlayerLyricSlider(title: String, value: Long, range: LongRange, step: Long,
    low: String, high: String, onPreview: (Long) -> Unit, onCommit: (Long) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = LyricsInk, fontSize = 14.sp)
            Text(current.toLong().toString(), color = LyricsSecondary, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(low, fontSize = 11.sp, color = LyricsSecondary)
            Slider(current, onValueChange = {
                current = (range.first + ((it - range.first) / step).roundToInt() * step).coerceIn(range).toFloat()
                onPreview(current.toLong())
            }, valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = ((range.last - range.first) / step - 1).toInt(),
                onValueChangeFinished = { onCommit(current.toLong()) },
                colors = SliderDefaults.sliderColors(foregroundColor = LyricsInk.copy(alpha = .82f),
                    backgroundColor = LyricsInk.copy(alpha = .14f), thumbColor = LyricsInk),
                modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = title })
            Text(high, fontSize = 11.sp, color = LyricsSecondary)
        }
    }
}
