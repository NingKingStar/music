package com.muses.player.feature.player.lyric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.ui.components.LocalPlayerContentColor
import com.muses.player.core.ui.theme.MusesTheme
import kotlinx.coroutines.runBlocking
import java.io.File

/** 手动渲染播放器专用弹层，背景与真实组件共用；不冒充手机截图。 */
@OptIn(ExperimentalComposeUiApi::class)
object LyricsControlsDesignPreview {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val output = File(args.first()).apply { mkdirs() }
        val preview = LyricLine(0, text = "生まれてきちゃってごめん", romanization = "umarete kichatte gomen",
            translation = "抱歉我生于世上")
        for (dark in listOf(false, true)) for (width in listOf(320, 390)) {
            val scene = ImageComposeScene(width, 800, coroutineContext = coroutineContext) {
                MusesTheme(useDarkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF10131D))) {
                        ImmersiveLyricsSurface(null, Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().heightIn(max = 780.dp)) {
                            LyricsSettingsContent(false, true, true, true, "", 24, 28, true, false,
                                {}, {}, {}, {}, {}, {}, previewLine = preview, nextPreviewLine = LyricLine(1000, text = "Chu！あざとくてごめん"))
                        }
                    }
                }
            }
            try { scene.render().use { image -> image.encodeToData()!!.use { data ->
                File(output, "settings-" + (if (dark) "dark" else "light") + "-$width.png").writeBytes(data.bytes)
            } } } finally { scene.close() }
        }
        val scene = ImageComposeScene(390, 120, coroutineContext = coroutineContext) {
            MusesTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF10131D)).padding(horizontal = 20.dp)) {
                    CompositionLocalProvider(LocalPlayerContentColor provides Color.White) {
                        LyricsToolbar(true, false, true, true, false, {}, {}, {}, {}, Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        try { scene.render().use { image -> image.encodeToData()!!.use { data ->
            File(output, "toolbar-dark.png").writeBytes(data.bytes)
        } } } finally { scene.close() }
        println("已生成320/390宽度、系统明暗模式下的播放器专用弹层参考图；封面为空时使用播放器默认流光背景。")
    }
}
