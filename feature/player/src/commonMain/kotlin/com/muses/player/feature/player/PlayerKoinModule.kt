package com.muses.player.feature.player

import com.muses.player.core.lyrics.LyricsMatcher
import com.muses.player.core.model.online.NoOpOnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackMetadataResolver
import com.muses.player.core.scrape.cover.CoverMatcher
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 播放页 ViewModel 装配（P2a Hilt→Koin）。 */
val playerModule = module {
    single<com.muses.player.core.model.lyrics.CoverAccentColorProvider> {
        com.muses.player.feature.player.backdrop.CachedCoverAccentColorProvider()
    }
    viewModel {
        // 在线曲目封面/歌词端口与匹配器均可选：宿主未装配（未装洛雪音源 / 未装刮削链）时回落空实现
        PlayerViewModel(
            get(),
            get(),
            getOrNull<OnlineTrackMetadataResolver>() ?: NoOpOnlineTrackMetadataResolver,
            getOrNull<LyricsMatcher>(),
            getOrNull<CoverMatcher>(),
            getOrNull<com.muses.player.core.model.online.OnlineTrackResolver>(),
        )
    }
    viewModel { QueueViewModel(get()) }
}
