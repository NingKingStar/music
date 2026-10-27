package com.muses.player.feature.sources

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.muses.player.core.data.repository.*
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.search.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSearchHistoryTest {
    private fun scenario(block: suspend TestScope.(OnlineSearchViewModel, SearchHistoryStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // 本组只控制ViewModel时间；真实文件重启持久化由SongCollectionsTest覆盖。
        val prefs = MutableStateFlow<Preferences>(emptyPreferences())
        val data = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = prefs
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(prefs.value).also { prefs.value = it }
        }
        val history = SearchHistoryStore(data)
        val provider = object : OnlineSearchProvider {
            override val platform = "kw"
            override val displayName = "测试"
            override suspend fun search(keyword: String, page: Int, pageSize: Int) =
                OnlineSearchPage(platform, keyword, page, emptyList(), false)
        }
        fun proxy(type: Class<*>, result: Any?) = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> result }
        val vm = OnlineSearchViewModel(OnlineSearchService(listOf(provider), cacheTtlMs = 0),
            proxy(PlaybackPort::class.java, Unit) as PlaybackPort, LxScriptRepository(LxCryptoJvm()),
            proxy(SettingsRepository::class.java, flowOf("320k")) as SettingsRepository,
            proxy(SongRepository::class.java, flowOf(emptyList<Any>())) as SongRepository,
            proxy(AlbumRepository::class.java, flowOf(emptyList<Any>())) as AlbumRepository,
            proxy(ArtistRepository::class.java, flowOf(emptyList<Any>())) as ArtistRepository, historyStore = history)
        try { block(vm, history) } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `自动搜索连续输入只留下最后的草稿词`() = scenario { vm, history ->
        vm.updateKeyword("周")
        advanceTimeBy(1_551); runCurrent()
        assertEquals("周", vm.state.value.searchedKeyword)
        assertEquals(listOf("周"), history.entries.first(), vm.state.value.toString())
        vm.updateKeyword("周杰伦")
        advanceTimeBy(1_551); runCurrent()
        assertEquals(listOf("周杰伦"), history.entries.first())
    }

    @Test fun `点击历史立即搜且清空不会被延迟回调恢复`() = scenario { vm, history ->
        history.record("晴天")
        vm.searchHistory("晴天"); runCurrent()
        assertEquals("晴天", vm.state.value.searchedKeyword)
        history.entries.first { it == listOf("晴天") }
        vm.clearHistory(); runCurrent()
        history.entries.first { it.isEmpty() }
        advanceTimeBy(3_000); runCurrent()
        assertTrue(history.entries.first().isEmpty())
    }

    @Test fun `明确提交的词不会被后续输入当草稿删除`() = scenario { vm, history ->
        vm.updateKeyword("晴天"); vm.search(); runCurrent()
        history.entries.first { "晴天" in it }
        vm.updateKeyword("夜曲"); advanceTimeBy(1_551); runCurrent()
        assertEquals(listOf("夜曲", "晴天"), history.entries.first())
    }

    @Test fun `自动搜索不同歌曲保留两条完整历史`() = scenario { vm, history ->
        vm.updateKeyword("晴天"); advanceTimeBy(1_551); runCurrent()
        assertEquals(listOf("晴天"), history.entries.first())
        vm.updateKeyword("夜曲"); advanceTimeBy(1_551); runCurrent()
        assertEquals(listOf("夜曲", "晴天"), history.entries.first())
    }
}
