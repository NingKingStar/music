package com.muses.player.core.search.di

import com.muses.player.core.search.OnlineChartService
import com.muses.player.core.search.CrossPlatformTrackMatcher
import com.muses.player.core.model.online.OnlineTrackCandidateProvider
import com.muses.player.core.model.online.OnlinePlayableUrlProbe
import com.muses.player.core.search.OnlineSearchService
import com.muses.player.core.search.OnlinePlaylistService
import com.muses.player.core.search.OnlineSongVersionService
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.core.search.provider.KgChartProvider
import com.muses.player.core.search.provider.KgSearchProvider
import com.muses.player.core.search.provider.KwSearchProvider
import com.muses.player.core.search.provider.MgSearchProvider
import com.muses.player.core.search.provider.QqChartProvider
import com.muses.player.core.search.provider.TxSearchProvider
import com.muses.player.core.search.provider.WyChartProvider
import com.muses.player.core.search.provider.WySearchProvider
import org.koin.dsl.module

/**
 * 在线搜索/榜单 Koin 装配：5 个搜索 provider + 3 个榜单 provider + 两个聚合服务。
 *
 * 说明：各平台接口均为公开的逆向接口（无需登录/签名），失败语义在 provider 内
 * 统一包装为 [com.muses.player.core.search.OnlineSearchException] /
 * [com.muses.player.core.search.OnlineChartException]，聚合层做「部分失败不阻断」处理。
 *
 * 榜单平台少于搜索：**酷我榜单接口需要 CSRF Token**（实测移动版与 wapi 两路均拒绝），
 * 故首发只上 QQ / 酷狗 / 网易三家（均实测匿名可用）。
 */
fun searchModule() = module {
    single { SearchHttp() }
    single { com.muses.player.core.search.OnlineArtistService(get()) }
    single { OnlinePlaylistService(get()) }
    single { OnlineSongVersionService(get()) }
    single { KwSearchProvider(get()) }
    single { TxSearchProvider(get()) }
    single { WySearchProvider(get()) }
    single { KgSearchProvider(get()) }
    single { MgSearchProvider(get()) }
    single {
        OnlineSearchService(
            providers = listOf(get<KwSearchProvider>(), get<TxSearchProvider>(), get<WySearchProvider>(), get<KgSearchProvider>(), get<MgSearchProvider>()),
        )
    }

    single<OnlineTrackCandidateProvider> { CrossPlatformTrackMatcher(get()) }
    single<OnlinePlayableUrlProbe> {
        val http: SearchHttp = get()
        object : OnlinePlayableUrlProbe {
            override suspend fun canOpen(url: String) = http.canOpenAudio(url)
            override suspend fun canOpen(url: String, expectedDurationMs: Long?) =
                http.canOpenAudio(url, expectedDurationMs)
        }
    }

    // 榜单：与搜索共享 SearchHttp（同 UA/超时/宽松解析口径）
    single { QqChartProvider(get()) }
    single { KgChartProvider(get()) }
    single { WyChartProvider(get()) }
    single {
        OnlineChartService(
            providers = listOf(get<QqChartProvider>(), get<KgChartProvider>(), get<WyChartProvider>()),
        )
    }
}
