package com.muses.player.desktop

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.muses.player.core.ui.icons.TablerIcons
import java.awt.MouseInfo
import java.awt.Toolkit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自绘标题栏（无边框窗口的拖拽/控制条）：
 * - 配色走 Salt 令牌（surface1 底 / text 标题 / text2 按钮），跟随系统明暗
 * - 图标走 TablerIcons（最小化 / 最大化 / 还原 / 关闭），不再用字符画
 *
 * 手势自己实现，不用 WindowDraggableArea：官方拖拽区与内层双击手势互相消费
 * 事件（拖拽被饿死、双击识别不出）。拖拽直接调 AWT 窗口定位（物理像素，
 * 无视系统缩放偏差）；最大化手动铺满窗口所在屏的工作区（避开任务栏，
 * undecorated 窗口走 placement 会盖住任务栏）；最大化状态本地维护，
 * placement 不是 Compose State，读它界面不会刷新。
 */
@Composable
fun WindowScope.DesktopTitleBar(
    windowState: WindowState,
    onClose: () -> Unit,
    title: String = "Muses",
) {
    val scheme = MiuixTheme.colorScheme
    val awtWindow = window
    var isMaximized by remember { mutableStateOf(false) }
    var restoredBounds by remember { mutableStateOf<WindowBounds?>(null) }

    fun maximize() {
        restoredBounds = WindowBounds(
            x = awtWindow.x,
            y = awtWindow.y,
            width = awtWindow.width,
            height = awtWindow.height,
        )
        val gc = awtWindow.graphicsConfiguration
        val screen = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        awtWindow.setBounds(
            screen.x + insets.left,
            screen.y + insets.top,
            screen.width - insets.left - insets.right,
            screen.height - insets.top - insets.bottom,
        )
        isMaximized = true
    }

    fun restore() {
        restoredBounds?.let { awtWindow.setBounds(it.x, it.y, it.width, it.height) }
        restoredBounds = null
        isMaximized = false
    }

    fun toggleMaximize() {
        if (isMaximized) restore() else maximize()
    }

    // 拖拽开始：最大化时先还原，窗口跟到鼠标下
    // （Windows 行为：保持鼠标在标题栏的横向比例）
    var dragAnchorWinX = 0
    var dragAnchorWinY = 0
    var dragAnchorMouseX = 0
    var dragAnchorMouseY = 0

    fun AwaitPointerEventScope.beginDragMove() {
        if (isMaximized) {
            val restored = restoredBounds
            val mouse = MouseInfo.getPointerInfo().location
            val targetWidth = restored?.width ?: awtWindow.width
            val ratio = if (awtWindow.width > 0) {
                (mouse.x - awtWindow.x).toFloat() / awtWindow.width
            } else {
                0.5f
            }
            restore()
            awtWindow.setLocation(
                (mouse.x - targetWidth * ratio).toInt(),
                (mouse.y - 20).coerceAtLeast(0),
            )
        }
        dragAnchorWinX = awtWindow.x
        dragAnchorWinY = awtWindow.y
        val mouse = MouseInfo.getPointerInfo().location
        dragAnchorMouseX = mouse.x
        dragAnchorMouseY = mouse.y
    }

    // 拖拽循环：读鼠标物理坐标推窗口（无视系统缩放偏差），
    // 按下手指抬起即结束
    suspend fun AwaitPointerEventScope.dragMoveLoop(pointerId: PointerId) {
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val mouse = MouseInfo.getPointerInfo().location
            awtWindow.setLocation(
                dragAnchorWinX + mouse.x - dragAnchorMouseX,
                dragAnchorWinY + mouse.y - dragAnchorMouseY,
            )
        } while (event.changes.any { it.id == pointerId && it.pressed })
    }

    Column(modifier = Modifier.fillMaxWidth().background(scheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 可拖拽区：应用标识 + 标题（拖拽移动，双击切换最大化）
            //
            // 单个手势状态机：tap 与 drag 必须串行判断——两个 pointerInput
            // 并列时，先执行的会消费按下事件，另一个永远等不到，拖拽/双击必死其一。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // 先判拖拽：位移超 slop 即拖拽；抬起则落到 tap 分支
                            val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (dragStart != null) {
                                beginDragMove()
                                dragMoveLoop(down.id)
                            } else {
                                // slop 等到的是抬起（up 即取消），一次 tap 已完成：
                                // 直接在双击超时内等第二次按下（此处不能再 waitForUp，
                                // 否则会吞掉下一次手势，官方 detectTapGestures 亦如此）。
                                // 超时不用系统默认（约 400ms 体感偏肉），标题栏单击
                                // 本就无动作，250ms 纯赚响应速度。
                                val secondDown = withTimeoutOrNull(
                                    DOUBLE_TAP_TIMEOUT_MILLIS,
                                ) {
                                    awaitFirstDown()
                                }
                                if (secondDown == null) return@awaitEachGesture
                                // 第二次按下后拖拽也算拖拽，否则即双击：
                                // 按下即执行，不等抬起（原生标题栏亦如此，否则
                                // 第二下按住不放会感觉慢半拍）
                                val secondDrag = awaitTouchSlopOrCancellation(secondDown.id) { change, _ ->
                                    change.consume()
                                }
                                if (secondDrag != null) {
                                    beginDragMove()
                                    dragMoveLoop(secondDown.id)
                                } else {
                                    toggleMaximize()
                                }
                            }
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource("muses/window-icon.png"),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = title,
                    color = scheme.onBackground,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // 窗口控制按钮（Windows 惯例：右对齐、无缝贴边）
            CaptionButton(
                icon = TablerIcons.WindowMinimize,
                contentDescription = "最小化",
                onClick = { windowState.isMinimized = true },
            )
            CaptionButton(
                icon = if (isMaximized) TablerIcons.WindowRestore else TablerIcons.WindowMaximize,
                contentDescription = if (isMaximized) "还原" else "最大化",
                // Tabler 的 Copy 与原生还原图标呈镜像，水平翻转对齐原生观感
                flipHorizontally = isMaximized,
                onClick = { toggleMaximize() },
            )
            CaptionButton(
                icon = TablerIcons.Close,
                contentDescription = "关闭",
                danger = true,
                onClick = onClose,
            )
        }
    }
}

private data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** 标题栏双击等待第二下的超时：系统默认约 400ms 体感偏肉，单击本就无动作，250ms 纯赚响应 */
private const val DOUBLE_TAP_TIMEOUT_MILLIS = 250L

@Composable
private fun CaptionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    flipHorizontally: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        danger && hovered -> scheme.error
        hovered -> scheme.surfaceVariant
        else -> Color.Transparent
    }
    val foreground = if (danger && hovered) Color.White else scheme.onBackgroundVariant

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(background)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = foreground,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer(scaleX = if (flipHorizontally) -1f else 1f),
        )
    }
}
