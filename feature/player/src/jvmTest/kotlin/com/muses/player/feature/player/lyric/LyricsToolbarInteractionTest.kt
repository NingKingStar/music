package com.muses.player.feature.player.lyric

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.muses.player.core.ui.theme.MusesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/** 真正发送指针事件，验证缺资源按钮不会触发跳转回调。 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsToolbarInteractionTest {
    @Test fun unavailableReadingAndTranslationDoNotNavigateButSettingsStillOpens() = runBlocking {
        var translationClicks = 0; var readingClicks = 0; var settingsClicks = 0
        val scene = ImageComposeScene(320, 80, coroutineContext = coroutineContext) {
            MusesTheme(useDarkTheme = true) {
                LyricsToolbar(true, true, false, false, false,
                    { translationClicks++ }, { readingClicks++ }, { settingsClicks++ }, {})
            }
        }
        try {
            scene.render().close()
            suspend fun click(x: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 32f))
                scene.render().close()
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, 32f))
                delay(20); scene.render().close()
            }
            click(22f); click(66f)
            assertEquals(0, translationClicks)
            assertEquals(0, readingClicks)
            click(110f)
            assertEquals(1, settingsClicks)
        } finally { scene.close() }
    }
}
