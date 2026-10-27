package com.muses.player.feature.sources

import com.muses.player.core.playback.PlaybackPort
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 音源页 ViewModel 装配（U20 全量上收：WebDAV 浏览/表单 + 音源页主 VM，双端同模块）。
 * 播放队列清理回调取 PlaybackPort 绑定（安卓=PlayerConnection，桌面=DesktopPlayerHook）。
 */
val sourcesCoreModule = module {
    single { com.muses.player.core.data.repository.SearchHistoryStore(get()) }
    single { LibraryRefreshScanner(get(), get(), get()) }
    viewModel { WebDavBrowseViewModel(get()) }
    // 洛雪自定义音源脚本管理页（脚本增删/启禁用/导入预检）
    viewModel { LxScriptsViewModel(get(), get(), get(), get()) }
    // 在线搜索页（多平台并行搜索 + 点结果直接播放）
    viewModel {
        val port: PlaybackPort = get()
        OnlineSearchViewModel(get(), port, get(), get(), get(), get(), get(), historyStore = get())
    }
    viewModel { WebDavFormViewModel(get(), get(), get()) }
    viewModel {
        val port: PlaybackPort = get()
        SourcesViewModel(
            get(), get(), get(), get(), get(),
            get(), get(), get(), get(),
            get(),
            lxScriptStore = get(),
            lxScriptRepository = get(),
            libraryScanner = get(),
            onRemoveFromQueue = port::removeFromQueue,
        )
    }
}
