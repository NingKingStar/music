package com.muses.player.core.lyrics.di

import com.muses.player.core.lyrics.LyricsMatcher
import com.muses.player.core.lyrics.amll.AMLL_INDEX_TIMEOUT_SEC
import com.muses.player.core.lyrics.amll.AMLL_INDEX_URL
import com.muses.player.core.lyrics.amll.AmllIndexRepository
import com.muses.player.core.lyrics.amll.AmllTtmlDbClient
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.lyrics.lrclib.LrclibProvider
import com.muses.player.core.lyrics.provider.PlatformLyricsProvider
import org.koin.dsl.module
import kotlinx.coroutines.flow.first
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackIdentity

/**
 * 歌词在线搜索装配（P2a Hilt→Koin：原 `@Module @InstallIn(SingletonComponent)`，
 * 任务 08-25-native-lyrics-online L3）。仅装配数据层单例；UI/播放链路接线归后续任务。
 *
 * 09-05-lyrics-kmp X3：随主源码上收 :core:common jvmShared（koin-core DSL 纯 Kotlin，
 * 无安卓依赖）；包名不变，app/AppKoinModule 装配零改动。
 */
val lyricsModule = module {

    single { LyricsHttp() }

    single {
        val http: LyricsHttp = get()
        AmllIndexRepository(
            loadFromNetwork = { http.getText(AMLL_INDEX_URL, timeoutSec = AMLL_INDEX_TIMEOUT_SEC) },
        )
    }

    single {
        AmllTtmlDbClient(
            http = get(),
            indexRepository = get(),
        )
    }

    /** 默认回退链：平台五源 → LRCLIB（match.ts defaultFallbackProviders） */
    single {
        val http: LyricsHttp = get()
        val lrclibProvider: LrclibProvider = get()
        val fallbacks = buildList {
            addAll(PlatformLyricsProvider.defaultChain(http))
            add(lrclibProvider)
        }
        LyricsMatcher(get(), fallbacks, playbackCacheDirectory = {
            java.io.File(com.muses.player.core.data.platform.PlatformDirs.cacheDir(), "playback-lyrics-v1")
        }, localLyrics = { query ->
            val keys = query.trackRef?.let { OnlineTrackIdentity.matchKeys(it).toSet() }.orEmpty()
            val saved = if (keys.isEmpty()) null else getOrNull<com.muses.player.core.download.DownloadQueueStore>()
                ?.downloadedSongs?.first()?.firstOrNull { entry ->
                    OnlineTrackRef.parse(entry.track.reference)?.let { ref ->
                        OnlineTrackIdentity.matchKeys(ref).any { it in keys }
                    } == true
                }
            saved?.track?.lyrics?.takeIf { it.isNotBlank() }
                ?: saved?.songId?.let { getOrNull<com.muses.player.core.data.dao.SongDao>()?.observeById(it)?.first()?.lyrics }
        })
    }

    /** M3：全局绑定供 ScrapeModule 消费（此前仅手动构造无绑定） */
    single { LrclibProvider(http = get()) }
}
