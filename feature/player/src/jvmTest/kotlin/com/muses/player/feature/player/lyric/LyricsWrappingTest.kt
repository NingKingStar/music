package com.muses.player.feature.player.lyric

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.muses.player.core.lyrics.model.LyricLine
import com.muses.player.core.lyrics.model.LyricSyllable
import com.muses.player.core.ui.components.LocalPlayerContentColor
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class LyricsWrappingTest {
    private val samples = listOf(
        "And we will never, ever, ever be apart",
        "Don't need no Starbucks, you're always here",
        "When I was thirteen I had my first love",
        "너의 목소리가 들려 오늘도 함께 걸어요",
        "君の声が聞こえる。いつまでも、ここにいる。",
        "听见你的声音，仍然陪你走过每一个清晨。",
    )
    @Test fun realRendererWrapsWithoutSplittingWordsOrChangingText() = runBlocking {
        var cases = 0
        for ((sampleIndex, text) in samples.withIndex()) for (width in listOf(320, 360, 430)) for (font in listOf(18, 24, 32)) {
            var result: TextLayoutResult? = null
            val line = LyricLine(0, text = text, syllables = listOf(LyricSyllable(text, 0, 4000)))
            val scene = ImageComposeScene(width = width, height = 700, coroutineContext = coroutineContext) {
                CompositionLocalProvider(LocalPlayerContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        AmllWordLyricText(line, { 2000L }, true, font / 24f, true, 1f, .4f,
                            LyricsRenderingQuality.High, false, showRuby = false, onTextLayout = { result = it })
                    }
                }
            }
            try {
                scene.render().close()
                val layout = assertNotNull(result)
                val protected = LyricTextLayout.protectWords(text)
                assertEquals(text, layout.layoutInput.text.text.replace("\u2060", ""))
                assertFalse(layout.hasVisualOverflow, "宽度=$width 字号=$font：$text")
                for (row in 0 until layout.lineCount - 1) {
                    val end = protected.originalOffset(layout.getLineEnd(row, visibleEnd = true))
                    val next = protected.originalOffset(layout.getLineStart(row + 1))
                    if (end in 1 until text.length && next < text.length && sampleIndex < 4) {
                        val before = text[end - 1]; val after = text[next]
                        assertFalse(before.isLetter() && after.isLetter() && text.substring(end, next).none { it.isWhitespace() },
                            "不能把同一个词拆开：宽度=$width 字号=$font，$text")
                    }
                }
                cases++
            } finally { scene.close() }
        }
        File("build/reports/lyrics-wrap-matrix.txt").apply { parentFile.mkdirs() }
            .writeText("真实逐字渲染器，6种句子 × 3种屏宽 × 3种字号，共" + cases + "组；不等同全曲库或安卓实机验收。")
        assertEquals(54, cases)
    }
}
