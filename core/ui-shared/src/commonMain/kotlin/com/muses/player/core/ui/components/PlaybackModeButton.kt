package com.muses.player.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.muses.player.core.model.playback.PlaybackMode
import com.muses.player.core.ui.icons.TablerIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalSelectPlaybackMode = staticCompositionLocalOf<((PlaybackMode) -> Unit)?> { null }

/** 气泡贴近按钮且避开窗口边缘；空间不足时放到按钮下方。 */
class PlaybackModeBubblePosition(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val minX = margin.coerceAtMost(maxX)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val above = anchorBounds.top - popupContentSize.height - gap
        return IntOffset(preferredX.coerceIn(minX, maxX),
            (if (above >= margin) above else anchorBounds.bottom + gap).coerceIn(margin.coerceAtMost(maxY), maxY))
    }
}

@Composable
fun PlaybackModeButton(repeatMode: Int, shuffleEnabled: Boolean, onCycleFallback: () -> Unit) {
    val actual = when { shuffleEnabled -> PlaybackMode.SHUFFLE; repeatMode == 1 -> PlaybackMode.SINGLE; repeatMode == 0 -> PlaybackMode.SEQUENTIAL; else -> PlaybackMode.LOOP }
    val selectMode = LocalSelectPlaybackMode.current
    var pending by remember { mutableStateOf<PlaybackMode?>(null) }
    val mode = pending ?: actual
    // 等待播放器确认；快速连续点击按最后一次选择推进，失败时恢复真实状态。
    LaunchedEffect(actual, pending) {
        if (pending == actual) pending = null
        else if (pending != null) { delay(800); pending = null }
    }
    var opened by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(opened, interaction) { if (opened) { delay(3000); opened = false } }
    val colors = MiuixTheme.colorScheme
    val tint = LocalPlayerContentColor.current
    val darkSurface = colors.surface.luminance() < .5f
    val bubbleSurface = lerp(colors.surface, colors.onSurface, if (darkSurface) .30f else .035f).copy(alpha = .96f)
    val bubbleText = colors.onSurface
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(opened) { visibility.targetState = opened }
    val visibilityTransition = rememberTransition(visibility, label = "播放模式气泡显隐")
    val entrance by visibilityTransition.animateFloat(transitionSpec = { tween(if (targetState) 180 else 130) }, label = "气泡透明度") { if (it) 1f else 0f }
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .94f else 1f, tween(100), label = "播放模式按压反馈")
    fun choose(selected: PlaybackMode) {
        if (selectMode != null) { pending = selected; selectMode(selected) }
        else onCycleFallback()
        opened = true; interaction++
    }
    Box {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 先记录按钮触摸，防止 Popup 把同一次按下误判为外部关闭。
                interaction++
            }
        }.semantics {
            contentDescription = "播放模式：${mode.label}"
        }.combinedClickable(interactionSource = pressSource, indication = null, role = Role.Button, onClickLabel = "切换播放模式", onLongClickLabel = "选择播放模式",
            onClick = { choose(PlaybackMode.entries[(mode.ordinal + 1) % PlaybackMode.entries.size]) },
            onLongClick = { opened = true; interaction++ }), contentAlignment = Alignment.Center) {
            AnimatedContent(mode, modifier = Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }, transitionSpec = {
                (fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 4 }) togetherWith
                    (fadeOut(tween(90)) + slideOutVertically(tween(150)) { -it / 4 })
            }, label = "播放模式图标切换") { current ->
                Icon(modeIcon(current), null, tint = tint.copy(alpha = .85f), modifier = Modifier.size(22.dp))
            }
        }
        if (visibility.currentState || visibility.targetState) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val position = remember(density) { with(density) { PlaybackModeBubblePosition(12.dp.roundToPx(), 8.dp.roundToPx()) } }
            Popup(popupPositionProvider = position, onDismissRequest = {
                val requestedAt = interaction
                scope.launch {
                    // 外部点按先到 Popup，再传到按钮；给按钮的按下事件留出处理时间。
                    delay(80)
                    if (interaction == requestedAt) opened = false
                }
            }, properties = PopupProperties(focusable = false)) {
                val highlightY by animateDpAsState(48.dp * mode.ordinal, spring(dampingRatio = .86f, stiffness = 620f), label = "播放模式选中位置")
                Box(Modifier.width(IntrinsicSize.Max).padding(4.dp).graphicsLayer {
                    alpha = entrance
                    scaleX = .96f + .04f * entrance
                    scaleY = .96f + .04f * entrance
                    translationY = with(density) { 5.dp.toPx() } * (1f - entrance)
                    transformOrigin = TransformOrigin(0f, 1f)
                }.shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp)).background(bubbleSurface)
                    .border(1.dp, bubbleText.copy(alpha = .07f), RoundedCornerShape(18.dp)).padding(4.dp)) {
                    Box(Modifier.fillMaxWidth().offset(y = highlightY).height(48.dp)
                        .clip(RoundedCornerShape(12.dp)).background(bubbleText.copy(alpha = if (darkSurface) .10f else .07f)))
                    Column {
                    PlaybackMode.entries.forEach { item ->
                        val active = item == mode
                        val foreground by animateColorAsState(bubbleText.copy(alpha = if (active) 1f else .65f), tween(160), label = "播放模式文字颜色")
                        val iconScale = remember { Animatable(1f) }
                        LaunchedEffect(active) {
                            if (active) {
                                iconScale.animateTo(1.06f, tween(90))
                                iconScale.animateTo(1f, spring(dampingRatio = .8f, stiffness = 650f))
                            } else iconScale.animateTo(1f, tween(90))
                        }
                        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                            .selectable(selected = active, role = Role.RadioButton, onClick = { choose(item) })
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(modeIcon(item), null, tint = foreground, modifier = Modifier.size(20.dp).graphicsLayer {
                                scaleX = iconScale.value; scaleY = iconScale.value
                            })
                            Text(item.label, fontSize = 13.sp, color = foreground, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun modeIcon(mode: PlaybackMode) = when(mode) {
    PlaybackMode.SEQUENTIAL -> TablerIcons.FormatListBulleted
    PlaybackMode.LOOP -> TablerIcons.Repeat
    PlaybackMode.SINGLE -> TablerIcons.RepeatOne
    PlaybackMode.SHUFFLE -> TablerIcons.Shuffle
}
