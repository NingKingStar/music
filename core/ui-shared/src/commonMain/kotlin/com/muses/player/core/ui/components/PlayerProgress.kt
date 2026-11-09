package com.muses.player.core.ui.components

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `.progress-range` + `.player-page__time-row` —— 播放进度条（m-range 自绘双轨 + 时间行）。
 *
 * 纯 UI + 回调：[positionMs]/[durationMs] 只读展示，拖动/点击经 [onSeekStart]/[onSeekEnd] 回调，
 * 外层据此禁用 pager 横滑（见 [onSeekDragActive]）。
 *
 * 手势铁律（09-03-fix-player-seek-pager-conflict 血泪）：
 * 同一 `pointerInput` 内禁止串行 `detectTapGestures` + `detectDragGestures`
 * （前者内部 `awaitEachGesture` 永不返回，后者成死代码，拖动冒泡给 pager 变切页）；
 * tap + 水平拖动必须在同一个 `awaitEachGesture` 内处理、越过 slop 即 `consume`。
 */
@Composable
fun PlayerProgress(
    positionMs: Long,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 20.dp,
    // 副歌节点：进度条上的一个小点，点进度条就能跳过去（与 QQ 音乐/网易云一致）。
    chorusMs: Long? = null,
    // 进度条手势活跃状态：按下即 true（tap/拖动均算），抬手/取消即 false。
    // 手机布局据此禁用 HorizontalPager 横滑，杜绝 seek 拖动被当成切页。
    onSeekDragActive: (Boolean) -> Unit = {},
) {
    val contentColor = LocalPlayerContentColor.current
    var previewMs by remember { mutableStateOf<Long?>(null) }
    val displayPos = previewMs ?: positionMs
    val max = durationMs.coerceAtLeast(1L)
    val canSeek = durationMs > 0L
    // 兼容 Capacitor：k-range 细轨（4px 白） + thumbWrap 隐藏（无圆球），
    // 完全自绘——不依赖 Material3 Slider 的 disabled 灰轨/默认 thumb
    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .pointerInput(canSeek, max) {
                    if (!canSeek) return@pointerInput
                    fun fractionAt(offset: androidx.compose.ui.geometry.Offset): Float =
                        (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    // 注意：不可在此串行调用 detectTapGestures + detectDragGestures——
                    // 前者内部 awaitEachGesture 无限循环永不返回，后者会成死代码
                    // （这正是此前拖动失效、手势冒泡给 pager 切页的根因）。
                    // 单一 awaitEachGesture 同时处理 tap + 水平拖动，越过 slop 即 consume。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 按在进度条上即视为 seek 意图：先通知上层禁用 pager 横滑，关掉抢手势的窗口
                        onSeekDragActive(true)
                        try {
                            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (slop == null) {
                                // 未越过 slop 即抬手 → 点击 seek（对齐原 detectTapGestures 行为）
                                onSeekStart()
                                onSeekEnd((fractionAt(down.position) * max).toLong().coerceIn(0L, durationMs))
                            } else {
                                // 拖动：跟手预览，松手 commit（对齐原 detectDragGestures 语义）
                                previewMs = (fractionAt(slop.position) * max).toLong()
                                onSeekStart()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    previewMs = (fractionAt(change.position) * max).toLong()
                                }
                                onSeekEnd((previewMs ?: positionMs).coerceIn(0L, durationMs))
                            }
                        } finally {
                            previewMs = null
                            onSeekDragActive(false)
                        }
                    }
                }
                .drawBehind {
                    // 底轨 rgba(255,255,255,0.25) + 填充 #fff，4dp 圆角细轨（对齐 .progress-range 全局样式）
                    val trackH = 4.dp.toPx()
                    val top = (size.height - trackH) / 2f
                    val fraction = (displayPos.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = contentColor.copy(alpha = 0.25f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
                    )
                    drawRoundRect(
                        color = contentColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width * fraction, trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
                    )
                    // 副歌节点：实心小点 + 一圈细淡环，压在轨道中线，跟播放进度区分。
                    chorusMs?.let { ms ->
                        val cx = size.width * (ms.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                        val cy = top + trackH / 2f
                        val center = androidx.compose.ui.geometry.Offset(cx, cy)
                        drawCircle(color = contentColor.copy(alpha = .32f), radius = 5.dp.toPx(), center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                        drawCircle(color = contentColor, radius = 2.5.dp.toPx(), center = center)
                    }
                },
        )
        // time-row：对齐 Capacitor 12px tabular-nums rgba0.68，margin-top 2px（缓冲提示已按需求移除）
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(displayPos),
                color = contentColor.copy(alpha = 0.68f),
                style = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
            )
            Text(
                text = if (durationMs > 0) formatPlayerTime(durationMs) else "--:--",
                color = contentColor.copy(alpha = 0.68f),
                style = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** 对齐 Capacitor formatTime：分钟补零（"03:45"）。手写补零，commonMain 无 String.format。 */
fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    val m = (totalSec / 60).toString().padStart(2, '0')
    val s = (totalSec % 60).toString().padStart(2, '0')
    return "$m:$s"
}
