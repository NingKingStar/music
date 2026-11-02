package com.muses.player.core.lyrics.provider

import com.muses.player.core.lyrics.LyricRomanizationPolicy
import com.muses.player.core.lyrics.LyricTranslationPolicy
import com.muses.player.core.lyrics.parseLyricsMatch
import com.muses.player.core.model.lyrics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 按实际解析覆盖率继续补缺，不能因接口返回原词就认定查找完成。 */
internal suspend fun needsAnnotations(hit: OnlineLyricsProviderHit, source: OnlineLyricsSource): Boolean {
    val document = parseLyricsMatch(OnlineLyricsMatchResult.Ok(hit.text, hit.format, source, hit.translationText,
        romanizationText = hit.romanizationText, romanizationBaseText = hit.romanizationBaseText,
        romanizationFallbackText = hit.romanizationFallbackText)) ?: return true
    return (source == OnlineLyricsSource.WY && LyricTranslationPolicy.needsEnrichment(document, source.wire)) ||
        LyricRomanizationPolicy.needsEnrichment(document)
}

/** 每个网络步骤独立限时，超时不吞上层切歌取消。 */
internal suspend fun <T> annotationRequest(block: suspend () -> T?): T? = try {
    withTimeoutOrNull(2_500) { block() }
} catch (e: CancellationException) { throw e } catch (_: Exception) { null }
