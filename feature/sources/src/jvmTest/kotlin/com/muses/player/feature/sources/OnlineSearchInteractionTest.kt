package com.muses.player.feature.sources

import androidx.lifecycle.viewModelScope
import com.muses.player.core.data.repository.*
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.model.Song
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.search.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSearchInteractionTest {
    private class Provider : OnlineSearchProvider {
        override val platform = "kw"
        override val displayName = "测试平台"
        val calls = mutableListOf<Pair<String, Int>>()
        var respond: suspend (String, Int) -> OnlineSearchPage = { keyword, page -> result(keyword, page) }
        override suspend fun search(keyword: String, page: Int, pageSize: Int): OnlineSearchPage {
            calls += keyword to page
            return respond(keyword, page)
        }
    }

    private class Playback {
        var selected: String? = null
        var queue = emptyList<Song>()
        val port = proxy<PlaybackPort> { name, args ->
            check(name == "play") { "意外的播放操作：$name" }
            selected = args[0] as String
            @Suppress("UNCHECKED_CAST")
            queue = args[1] as List<Song>
            Unit
        }
    }

    private fun workflow(block: suspend TestScope.(OnlineSearchViewModel, Provider, Playback) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = Provider()
        val playback = Playback()
        val vm = OnlineSearchViewModel(
            OnlineSearchService(listOf(provider), cacheTtlMs = 0), playback.port, LxScriptRepository(LxCryptoJvm()),
            proxy<SettingsRepository> { name, _ ->
                check(name == "getOnlinePreferredQuality")
                flowOf("320k")
            },
            proxy<SongRepository> { _, _ -> flowOf(emptyList<Song>()) },
            proxy<AlbumRepository> { _, _ -> flowOf(emptyList<com.muses.player.core.model.Album>()) },
            proxy<ArtistRepository> { _, _ -> flowOf(emptyList<com.muses.player.core.model.Artist>()) },
        )
        try { block(vm, provider, playback) } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `暂停输入才搜索且支持单个字母`() = workflow { vm, provider, _ ->
        vm.updateKeyword("a")
        runCurrent()
        advanceTimeBy(349)
        runCurrent()
        assertTrue(provider.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a" to 1), provider.calls)
        vm.updateKeyword("ab")
        runCurrent()
        advanceTimeBy(200)
        vm.updateKeyword("abc")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        assertEquals(listOf("a" to 1, "abc" to 1), provider.calls)
    }

    @Test fun `手动搜索立即执行且不会重复自动提交`() = workflow { vm, provider, _ ->
        vm.updateKeyword("周")
        vm.search()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("周" to 1), provider.calls)
    }

    @Test fun `清空输入撤销搜索并清空结果`() = workflow { vm, provider, _ ->
        vm.updateKeyword("歌曲")
        vm.search()
        runCurrent()
        assertEquals(1, vm.state.value.totalResults)
        vm.updateKeyword("新歌")
        vm.updateKeyword("  ")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("歌曲" to 1), provider.calls)
        assertFalse(vm.state.value.searched)
        assertFalse(vm.state.value.searching)
        assertEquals(0, vm.state.value.totalResults)
    }

    @Test fun `输入改动立即作废尚未返回的旧请求`() = workflow { vm, provider, _ ->
        val old = CompletableDeferred<OnlineSearchPage>()
        provider.respond = { keyword, page ->
            if (keyword == "旧") withContext(NonCancellable) { old.await() } else result(keyword, page)
        }
        vm.updateKeyword("旧")
        vm.search()
        runCurrent()
        vm.updateKeyword("新")
        old.complete(result("旧", 1))
        runCurrent()
        assertEquals(0, vm.state.value.totalResults)
        advanceTimeBy(350)
        runCurrent()
        assertEquals("新", vm.state.value.platforms.single().results.single().name)
    }

    @Test fun `清空时正在执行的请求不能恢复旧结果`() = workflow { vm, provider, _ ->
        val pending = CompletableDeferred<OnlineSearchPage>()
        provider.respond = { _, _ -> withContext(NonCancellable) { pending.await() } }
        vm.updateKeyword("旧")
        vm.search()
        runCurrent()
        vm.updateKeyword("")
        pending.complete(result("旧", 1))
        runCurrent()
        assertFalse(vm.state.value.searched)
        assertEquals(0, vm.state.value.totalResults)
    }

    @Test fun `切换分类使用正在输入的新关键词`() = workflow { vm, provider, _ ->
        vm.updateKeyword("旧")
        vm.search()
        runCurrent()
        vm.updateKeyword("新")
        vm.setCategory(SearchCategory.ALBUMS)
        runCurrent()
        assertEquals("新", vm.state.value.searchedKeyword)
        vm.setCategory(SearchCategory.ALL)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("旧" to 1, "新" to 1), provider.calls)
    }

    @Test fun `修改输入后旧分页不会混入新结果`() = workflow { vm, provider, _ ->
        val pending = CompletableDeferred<OnlineSearchPage>()
        provider.respond = { keyword, page -> if (page == 2) pending.await() else result(keyword, page) }
        vm.updateKeyword("旧")
        vm.search()
        runCurrent()
        vm.loadMore("kw")
        runCurrent()
        vm.updateKeyword("新")
        pending.complete(result("旧分页", 2))
        runCurrent()
        assertEquals(1, vm.state.value.totalResults)
        advanceTimeBy(350)
        runCurrent()
        assertEquals("新", vm.state.value.platforms.single().results.single().name)
    }

    @Test fun `点击结果将对应歌曲和平台队列交给播放端`() = workflow { vm, _, playback ->
        vm.updateKeyword("歌曲")
        vm.search()
        runCurrent()
        vm.playResult("kw", 0)
        assertEquals("online:kw:歌曲-1", playback.selected)
        assertEquals(listOf("歌曲"), playback.queue.map { it.title })
    }

    companion object {
        private fun result(keyword: String, page: Int) = OnlineSearchPage(
            "kw", keyword, page,
            listOf(OnlineSearchResult("kw", "$keyword-$page", keyword, null, null, null, null, "{}")), true,
        )

        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
