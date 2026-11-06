package com.muses.player.desktop.tray

import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.EventQueue
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * 桌面系统托盘（二期预留落地，JvmPlayerPort.setTrayVisible 预留能力的承载者）：
 * - 常驻图标 + 菜单（显示主窗口/播放⇄暂停/上一首/下一首/退出）；
 * - 左键单击图标唤起主窗口；
 * - SystemTray 不可用（无头/不支持）时静默降级为 no-op；
 * - 图标为程序化占位（项目蓝圆底 + M），正式图标随打包 icon 任务替换。
 *
 * 动作以 lambda 注入，本类不感知播放器与窗口实现；AWT 创建与更新均在 EDT。
 */
class DesktopTray(
    private val isPlaying: StateFlow<Boolean>,
    private val onShowMainWindow: () -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onExit: () -> Unit,
    private val tooltip: String = "Muses",
    private val onLyricsSettings: (() -> Unit)? = null,
    private val lyricsVisible: StateFlow<Boolean>? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trayIcon: TrayIcon? = null

    /** 安装托盘；重复调用安全，SystemTray 不可用时 no-op。 */
    fun install() {
        if (!SystemTray.isSupported() || trayIcon != null) return
        EventQueue.invokeLater {
            runCatching {
                val playItem = MenuItem("播放").apply {
                    addActionListener { onTogglePlay() }
                }
                val lyricsItem = onLyricsSettings?.let { adjust -> MenuItem("歌词设置").apply {
                    isEnabled = lyricsVisible?.value ?: true
                    addActionListener { adjust() }
                } }
                val popup = PopupMenu().apply {
                    add(MenuItem("显示主窗口").apply {
                        addActionListener { onShowMainWindow() }
                    })
                    add(playItem)
                    add(MenuItem("上一首").apply {
                        addActionListener { onPrevious() }
                    })
                    add(MenuItem("下一首").apply {
                        addActionListener { onNext() }
                    })
                    addSeparator()
                    lyricsItem?.let { add(it); addSeparator() }
                    add(MenuItem("退出").apply {
                        addActionListener { onExit() }
                    })
                }
                val icon = TrayIcon(createIconImage(), tooltip, popup).apply {
                    isImageAutoSize = true
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.button == MouseEvent.BUTTON1) onShowMainWindow()
                        }
                    })
                }
                SystemTray.getSystemTray().add(icon)
                trayIcon = icon
                scope.launch {
                    isPlaying.collect { playing ->
                        EventQueue.invokeLater {
                            runCatching { playItem.label = if (playing) "暂停" else "播放" }
                        }
                    }
                }
                if (lyricsItem != null && lyricsVisible != null) scope.launch {
                    lyricsVisible.collect { available ->
                        EventQueue.invokeLater { lyricsItem.isEnabled = available }
                    }
                }
            }
        }
    }

    /** 移除托盘并停止状态收集；未安装时安全。应用退出前调用。 */
    fun uninstall() {
        scope.cancel()
        val icon = trayIcon ?: return
        trayIcon = null
        EventQueue.invokeLater {
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
    }

    /**
     * 托盘图标：优先读 classpath 内生成好的图标资源（与 jpackage 同一设计语言），
     * 读取失败（资源缺失/损坏）时回落程序化绘制，保证托盘始终可用。
     */
    private fun createIconImage(): Image = runCatching {
        ImageIO.read(javaClass.getResourceAsStream("/muses/tray-icon.png"))
    }.getOrNull() ?: drawFallbackIcon()

    /** 程序化兜底图标：珊瑚红圆角底 + 奶油色音符精灵。 */
    private fun drawFallbackIcon(): Image {
        val size = 32
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xFF, 0x4F, 0x53)
        g.fillRoundRect(1, 1, size - 2, size - 2, 9, 9)
        g.color = Color(0xFF, 0xF1, 0xDB)
        g.fillOval(7, 6, 18, 18)
        g.fillPolygon(intArrayOf(10, 18, 21), intArrayOf(20, 20, 29), 3)
        g.color = Color(0x77, 0x1C, 0x19)
        g.fillOval(12, 12, 4, 6)
        g.stroke = java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g.drawArc(18, 11, 7, 6, 200, 140)
        g.dispose()
        return img
    }
}
