package com.muses.player.core.media.playback

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.muses.player.core.lyrics.DesktopLyricsText
import com.muses.player.core.ui.components.DesktopLyricsCard
import com.muses.player.core.ui.theme.MusesTheme
import kotlin.math.roundToInt

/** 由播放服务持有，不获取焦点；关闭或服务停止时立即移除窗口。 */
class DesktopLyricsOverlay(private val context: Context, private val onClose: () -> Unit,
    private val onLock: () -> Unit, private val onPrevious: () -> Unit,
    private val onPlayPause: () -> Unit, private val onNext: () -> Unit,
    private val onColor: (Long) -> Unit, private val onFontSize: (Int) -> Unit) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferences = context.getSharedPreferences("desktop_lyrics_window", Context.MODE_PRIVATE)
    private val text = mutableStateOf(DesktopLyricsText("Muses", null))
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private val accentColor = mutableStateOf<Color?>(null)
    private val controlsVisible = mutableStateOf(false)
    private val locked = mutableStateOf(false)
    private val fontSize = mutableStateOf(22)
    private val selectedColor = mutableStateOf<Color?>(null)
    private val title = mutableStateOf("Muses")
    private val handler = Handler(Looper.getMainLooper())
    private var editingStyle = false
    private var contentHeightPx = 0
    private var anchorY = 0
    private var dragX = 0f
    private var dragY = 0f
    private val hideControls = Runnable { controlsVisible.value = false; editingStyle = false }
    private val passThroughAlpha: Float
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f

    fun setAccentColor(argb: Int?) { accentColor.value = argb?.let { Color(it) } }
    fun setStyle(size: Int, color: Long) {
        fontSize.value = size.coerceIn(14, 40)
        selectedColor.value = color.takeIf { it != 0L }?.let { Color(it.toInt()) }
        resizeWindow()
    }
    fun setTitle(value: String) { title.value = value }
    private fun windowHeight(): Int {
        if (contentHeightPx > 0) return contentHeightPx
        val metrics = context.resources.displayMetrics
        return ((fontSize.value + 6 + (if (text.value.secondary != null) fontSize.value * 0.64f + 6 else 0f)) *
            metrics.scaledDensity + 12 * metrics.density).roundToInt()
    }
    private fun resizeWindow() {
        val layout = params ?: return
        layout.height = windowHeight()
        // 展开时临时避让底边，收起后回到用户拖放的位置。
        layout.y = anchorY.coerceIn(0, (context.resources.displayMetrics.heightPixels - layout.height).coerceAtLeast(0))
        view?.let { manager.updateViewLayout(it, layout) }
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (!controlsVisible.value || editingStyle) return
        val timeout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(8000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)
        else 8000
        handler.postDelayed(hideControls, timeout.toLong())
    }
    private fun interact(action: () -> Unit) {
        handler.removeCallbacks(hideControls)
        action()
        scheduleHide()
    }

    private fun toggleControls() {
        controlsVisible.value = !controlsVisible.value
        editingStyle = false
        scheduleHide()
    }

    fun setLocked(value: Boolean) {
        if (locked.value == value) return
        locked.value = value
        controlsVisible.value = false
        editingStyle = false
        handler.removeCallbacks(hideControls)
        params?.let { layout ->
            layout.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (value) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
            layout.alpha = if (value) passThroughAlpha else 1f
            view?.let { runCatching { manager.updateViewLayout(it, layout) } }
        }
        resizeWindow()
    }

    private fun endDrag() {
        params?.let { preferences.edit().putInt("x", it.x).putInt("y", anchorY).apply() }
        scheduleHide()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val layout = params ?: return
        val display = context.resources.displayMetrics
        dragX = (dragX + dx).coerceIn(0f, (display.widthPixels - layout.width).coerceAtLeast(0).toFloat())
        dragY = (dragY + dy).coerceIn(0f, (display.heightPixels - layout.height).coerceAtLeast(0).toFloat())
        layout.x = dragX.roundToInt()
        layout.y = dragY.roundToInt()
        anchorY = layout.y
        view?.let { manager.updateViewLayout(it, layout) }
    }

    fun update(value: DesktopLyricsText) {
        if (!Settings.canDrawOverlays(context)) { hide(); return }
        text.value = value
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels - (24 * metrics.density).toInt()).coerceAtMost((520 * metrics.density).toInt())
        // 给 Compose 的主题浮层明确高度，避免根布局按整屏测量后把窗口位置夹到顶部。
        val windowHeight = windowHeight()
        view?.let { current ->
            params?.let { layout ->
                val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
                val maxY = (metrics.heightPixels - windowHeight).coerceAtLeast(0)
                if (layout.width != width || layout.height != windowHeight || layout.x > maxX || layout.y > maxY) {
                    layout.width = width
                    layout.height = windowHeight
                    layout.x = layout.x.coerceIn(0, maxX)
                    layout.y = anchorY.coerceIn(0, maxY)
                    manager.updateViewLayout(current, layout)
                }
            }
            return
        }
        val layout = WindowManager.LayoutParams(width, windowHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (locked.value) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
            PixelFormat.TRANSLUCENT).apply {
            alpha = if (locked.value) passThroughAlpha else 1f
            gravity = Gravity.TOP or Gravity.LEFT
            x = preferences.getInt("x", (12 * metrics.density).toInt()).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = preferences.getInt("y", (metrics.heightPixels * 0.18f).toInt()).coerceIn(0, (metrics.heightPixels - windowHeight).coerceAtLeast(0))
        }
        anchorY = layout.y
        val lifecycleOwner = OverlayOwner()
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MusesTheme {
                    DesktopLyricsCard(text.value.primary, text.value.secondary, onClose,
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(onDragStart = {
                                handler.removeCallbacks(hideControls)
                                dragX = params?.x?.toFloat() ?: 0f
                                dragY = params?.y?.toFloat() ?: 0f
                            },
                                onDragEnd = { endDrag() }, onDragCancel = { endDrag() }) { change, delta ->
                                change.consume()
                                moveBy(delta.x, delta.y)
                            }
                        }, words = text.value.words, positionMs = text.value.positionMs, isPlaying = text.value.isPlaying,
                        accentColor = accentColor.value, controlsVisible = controlsVisible.value,
                        onTap = { toggleControls() }, onLock = { setLocked(true); onLock() },
                        title = title.value, fontSize = fontSize.value, selectedColor = selectedColor.value,
                        onPrevious = { interact(onPrevious) }, onPlayPause = { interact(onPlayPause) }, onNext = { interact(onNext) },
                        onColor = { color -> interact { onColor(color) } },
                        onFontSize = { size -> interact { onFontSize(size) } },
                        onEditingStyleChanged = { editingStyle = it; scheduleHide() },
                        // 悬浮条不放样式调节：颜色/字号/锁定都在歌词显示弹层里改，锁定后也能调回来。
                        showStyleControls = false,
                        onContentHeightChanged = { height ->
                            if (contentHeightPx != height) {
                                contentHeightPx = height
                                handler.post { resizeWindow() }
                            }
                        })
                }
            }
        }
        try {
            manager.addView(content, layout)
            view = content
            owner = lifecycleOwner
            params = layout
        } catch (e: Exception) {
            hide()
            content.disposeComposition()
            lifecycleOwner.destroy()
            throw e
        }
    }

    fun hide() {
        handler.removeCallbacks(hideControls)
        view?.let { runCatching { manager.removeViewImmediate(it) }; it.disposeComposition() }
        owner?.destroy()
        view = null
        owner = null
        params = null
        controlsVisible.value = false
        editingStyle = false
        contentHeightPx = 0
    }


    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
