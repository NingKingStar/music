package com.muses.player.feature.shell.platform

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.Song
import com.muses.player.core.search.OnlineArtistService
import com.muses.player.core.search.http.SearchHttp
import com.muses.player.feature.home.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistRefreshRetryTest {
    @Test fun 最后一页刷新失败仍可重试刷新() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("artist-refresh").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = ViewModelStore()
        var calls = 0
        val client = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.contains("/artist/songs")) {
                calls++
                respond(if (calls == 2) """{"code":500}""" else """{"code":200,"songs":[{"id":$calls,"name":"歌曲"}],"more":false}""")
            } else respond("""{"code":200,"artist":{"id":6452,"name":"歌手","briefDesc":"介绍"}}""")
        })
        fun <T> unused(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> error("意外调用：" + method.name) })
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "cache.preferences_pb") })
            val info = """{"id":"1","artistRefs":"[{\"platform\":\"wy\",\"id\":\"6452\",\"name\":\"歌手\"}]"}"""
            val seed = Song("seed", "online", OnlineTrackRef("wy", info, "online").encode(), "歌曲", "歌手")
            val vm = OnlineArtistViewModel(OnlineArtistService(SearchHttp(client)), ArtistPageCache(store), unused(PlaybackPort::class.java), unused(SettingsRepository::class.java), seed)
            models.put("test", vm)
            withTimeout(10_000) { vm.state.first { !it.loading && it.songs.isNotEmpty() } }
            assertFalse(vm.state.value.hasMore)
            vm.refresh()
            withTimeout(10_000) { vm.state.first { !it.loading && it.error != null } }
            vm.retry()
            withTimeout(10_000) { vm.state.first { !it.loading && it.error == null && it.songs.singleOrNull()?.songId == "3" } }
            assertEquals(3, calls)
        } finally {
            models.clear(); client.close(); scope.cancel(); scope.coroutineContext[Job]?.join(); dir.deleteRecursively(); Dispatchers.resetMain()
        }
    }
}
