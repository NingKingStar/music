package com.muses.player.core.lxsdk.di

import com.muses.player.core.lxsdk.LxOnlineMetadataResolver
import com.muses.player.core.lxsdk.LxOnlineTrackResolver
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.crypto.LxCrypto
import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.http.LxHttpClient
import com.muses.player.core.lxsdk.store.FileLxScriptStore
import com.muses.player.core.lxsdk.store.LxScriptStore
import com.muses.player.core.model.online.CachedOnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackResolver
import org.koin.dsl.module
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lxsdk.store.BuiltinLxSourceUpdater
import kotlinx.coroutines.flow.first

/**
 * 洛雪自定义音源（在线音源）Koin 装配。
 *
 * 装配链：
 * ```
 * LxScriptStore（文件持久化）
 *   → LxScriptRepository（多脚本管理 + 按 platform 路由）
 *     → LxOnlineTrackResolver（适配 :core:common 的 OnlineTrackResolver 端口）
 *       → 双端播放器（Android PlayerConnection / 桌面 JvmPlayerPort）
 * ```
 *
 * ## 为什么脚本同步是「懒」的
 * 仓库经 `storedScriptsProvider` 在**首次真正使用时**才去存储拉取脚本，
 * 而不是在构造期或 `scope.launch` 里 eager 读取。原因：
 * - Android 的 `PlatformDirs.appDataDir()` 依赖 `initPlatformDirs(context)` 先执行；
 * - 播放端口链路（PlaybackPort → PlayerConnection → OnlineTrackResolver →
 *   LxScriptRepository → LxScriptStore）在 `MainViewModel` 创建时就会被 Koin 解析，
 *   此时目录初始化可能尚未完成，eager 访问会直接崩（实测：
 *   `IllegalArgumentException: initPlatformDirs 未初始化就调用 appDataDir`）。
 * 懒同步后，构造只创建轻量对象，任何目录访问都发生在用户真正播放/管理脚本时。
 */
fun lxSdkModule() = module {
    single<LxCrypto> { LxCryptoJvm() }
    single { LxHttpClient() }
    single { FileLxScriptStore() }
    single<LxScriptStore> { get<FileLxScriptStore>() }
    single {
        val store: LxScriptStore = get()
        val settings: SettingsRepository = get()
        LxScriptRepository(
            crypto = get(),
            httpClient = get(),
            preferredScriptIdsProvider = {
                val builtinEnabled = settings.builtinLxSourcesEnabled.first()
                val active = store.list().filter { it.enabled && (builtinEnabled || !it.isBuiltin) }
                com.muses.player.core.lxsdk.LxSourcePreference.preferredIds(settings.preferredLxSource.first(), active)
            },
            storedScriptsProvider = {
                // 只登记已启用脚本；引擎仍按需加载（首次请求某平台时才建 QuickJS runtime）
                val builtinEnabled = settings.builtinLxSourcesEnabled.first()
                store.list().filter { it.enabled && (builtinEnabled || !it.isBuiltin) }.map { it.id to it.source }
            },
        )
    }
    single { BuiltinLxSourceUpdater(get(), get(), get(), get()) }
    single<OnlineTrackResolver> {
        val settings: SettingsRepository = get()
        val repository: LxScriptRepository = get()
        LxOnlineTrackResolver(get(), candidateProvider = getOrNull(), urlProbe = getOrNull(),
            preferredQualityProvider = { com.muses.player.core.lxsdk.LxQuality.fromKey(settings.onlinePreferredQuality.first()) },
            // 首选选择或脚本集合一变化，短期复用自然失效。
            cacheKeySaltProvider = { repository.preferenceFingerprint() })
    }

    /**
     * 播放页封面/歌词端口（脚本 `pic` / `lyric` 动作）。
     * 与直链端口同源共享 [LxScriptRepository]，复用同一批已加载引擎，不额外拉起 QuickJS runtime。
     *
     * 外层套 [CachedOnlineTrackMetadataResolver]：播放页与迷你条各自订阅当前曲，
     * 同一首歌只应真正拉一次脚本（Koin 单例共享同一份缓存）。
     */
    single<OnlineTrackMetadataResolver> {
        val settings: SettingsRepository = get()
        CachedOnlineTrackMetadataResolver(LxOnlineMetadataResolver(get()),
            namespaceProvider = { settings.preferredLxSource.first() })
    }
}
