package com.muses.player.core.media.playback

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.muses.player.core.lyrics.DesktopLyricsText
import com.muses.player.core.ui.theme.MusesTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 状态栏歌词：只占用屏幕最顶上那条状态栏高度，显示当前一句。
 *
 * 窗口固定 FLAG_NOT_TOUCHABLE，永远不会截走触摸；也不抢焦点，所以不影响下拉通知栏。
 * 歌词为空、没有悬浮窗权限或服务停止时立即移除窗口。
 */
class StatusBarLyricsOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val line = mutableStateOf<String?>(null)
    private val fontSize = mutableStateOf(13)
    private val tint = mutableStateOf<Color?>(null)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    fun setStyle(size: Int, color: Long) {
        fontSize.value = size.coerceIn(10, 20)
        tint.value = color.takeIf { it != 0L }?.let { Color(it.toInt()) }
    }

    fun update(value: DesktopLyricsText) {
        val primary = value.primary.trim()
        if (primary.isEmpty() || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        line.value = primary
        ensureAttached()
    }

    fun hide() {
        view?.let { runCatching { manager.removeViewImmediate(it) }; it.disposeComposition() }
        owner?.destroy()
        view = null
        owner = null
        params = null
        line.value = null
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val resolved = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        val fallback = (26 * context.resources.displayMetrics.density).toInt()
        return if (resolved > 0) resolved else fallback
    }

    private fun ensureAttached() {
        if (view != null) return
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val lifecycleOwner = OverlayOwner()
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MusesTheme {
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = line.value.orEmpty(),
                            color = tint.value ?: Color.White,
                            fontSize = fontSize.value.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 0.75f), 3f),
                            ),
                        )
                    }
                }
            }
        }
        runCatching { manager.addView(content, layout) }.onSuccess {
            view = content
            owner = lifecycleOwner
            params = layout
        }.onFailure {
            content.disposeComposition()
            lifecycleOwner.destroy()
            view = null
            owner = null
            params = null
        }
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

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
