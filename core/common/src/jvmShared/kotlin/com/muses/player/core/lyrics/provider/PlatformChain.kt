package com.muses.player.core.lyrics.provider

import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.model.lyrics.LyricsProvider
import com.muses.player.core.model.lyrics.OnlineLyricsProviderHit
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import com.muses.player.core.model.lyrics.OnlineLyricsSource

/**
 * 平台歌词默认链（规格书 = src/features/lyrics/providers/platform.ts）：
 * kw → tx → wy → kg → mg；注册到 matchOnlineLyrics fallback（LRCLIB 之前由调用方组合）。
 *
 * 各 provider 与 Web 一致：失败返回 null 由链上下一源承接，异常向上抛由编排层归 network。
 */
class PlatformLyricsProvider(
    override val id: OnlineLyricsSource,
    private val http: LyricsHttp,
) : LyricsProvider {
    override suspend fun searchProgressively(query: OnlineLyricsQuery, onHit: suspend (OnlineLyricsProviderHit) -> Unit): Boolean {
        return when (id) {
            OnlineLyricsSource.WY -> searchWyLyricsProgressively(http, query, onHit)
            OnlineLyricsSource.TX -> searchTxLyricsProgressively(http, query, onHit)
            else -> super.searchProgressively(query, onHit)
        }
    }

    override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit? = when (id) {
        OnlineLyricsSource.KW -> searchKwLyrics(http, query)
        OnlineLyricsSource.TX -> searchTxLyrics(http, query)
        OnlineLyricsSource.WY -> searchWyLyrics(http, query)
        OnlineLyricsSource.KG -> searchKgLyrics(http, query)
        OnlineLyricsSource.MG -> searchMgLyrics(http, query)
        else -> throw IllegalArgumentException("platform 链不含 $id")
    }

    companion object {
        /** platform.ts platformLyricsProviders 默认顺序 */
        fun defaultChain(http: LyricsHttp): List<PlatformLyricsProvider> = listOf(
            PlatformLyricsProvider(OnlineLyricsSource.KW, http),
            PlatformLyricsProvider(OnlineLyricsSource.TX, http),
            PlatformLyricsProvider(OnlineLyricsSource.WY, http),
            PlatformLyricsProvider(OnlineLyricsSource.KG, http),
            PlatformLyricsProvider(OnlineLyricsSource.MG, http),
        )
    }
}
