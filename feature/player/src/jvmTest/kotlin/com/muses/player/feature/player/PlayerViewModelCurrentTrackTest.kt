package com.muses.player.feature.player

import androidx.lifecycle.viewModelScope
import com.muses.player.core.data.dao.SongDao
import com.muses.player.core.data.db.SongAlbumCrossRef
import com.muses.player.core.data.db.SongArtistCrossRef
import com.muses.player.core.data.db.SongEntity
import com.muses.player.core.lyrics.LyricsMatcher
import com.muses.player.core.lyrics.amll.AmllIndexRepository
import com.muses.player.core.lyrics.amll.AmllTtmlDbClient
import com.muses.player.core.lyrics.http.LyricsHttp
import com.muses.player.core.model.Song
import com.muses.player.core.model.SourceType
import com.muses.player.core.model.lyrics.AmllFailReason
import com.muses.player.core.model.lyrics.AmllMatchQuery
import com.muses.player.core.model.lyrics.AmllMatchResult
import com.muses.player.core.model.lyrics.LyricsProvider
import com.muses.player.core.model.lyrics.OnlineLyricsProviderHit
import com.muses.player.core.model.lyrics.OnlineLyricsQuery
import com.muses.player.core.model.lyrics.OnlineLyricsSource
import com.muses.player.core.model.online.OnlineTrackLyrics
import com.muses.player.core.model.online.OnlineTrackMetadataResolver
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.model.playback.PlayerConfig
import com.muses.player.core.model.playback.RepeatMode
import com.muses.player.core.playback.PlaybackMeta
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.playback.PlaybackStates
import com.muses.player.core.model.scrape.MatchConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播放页「当前曲元数据」编排测试（在线曲目封面/歌词上屏链路）。
 *
 * 直接复现用户报障的场景：**在线曲目播放时沉浸页有没有歌词**。
 * 覆盖三条路径：
 * 1. 脚本 `lyric` 有歌词 → 用脚本的（含逐字）；
 * 2. 脚本没有歌词（多数洛雪脚本只声明 musicUrl）→ 回退 Muses 在线歌词匹配；
 * 3. 曲库曲目 → 仍走曲库列（确认改造没回归）。
 *
 * 用 [runBlocking] 而非 runTest：VM 内部的歌词解析走 `withContext(Dispatchers.Default)`
 * 真实线程池，需要真实等待（runTest 的虚拟时间会让轮询等待失效）。
 */
class PlayerViewModelCurrentTrackTest {
    @Test fun `封面URL非空但加载失败后排除坏地址再找下一源`() = runBlocking {
        val song = onlineSong(id = "online:kw:bad-cover")
        OnlineTrackSession.remember(listOf(song))
        var calls = 0
        fun provider(source: com.muses.player.core.scrape.cover.OnlineCoverSource, url: String) = object : com.muses.player.core.scrape.cover.CoverProvider {
            override val id = source
            override suspend fun searchCoverUrl(query: com.muses.player.core.scrape.cover.OnlineCoverQuery): String {
                calls++
                return url
            }
        }
        val matcher = com.muses.player.core.scrape.cover.CoverMatcher(listOf(
            provider(com.muses.player.core.scrape.cover.OnlineCoverSource.WY, song.coverUri!!),
            provider(com.muses.player.core.scrape.cover.OnlineCoverSource.TX, "https://cdn.test/backup.jpg"),
        ))
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), FakeMetadataResolver(), null, matcher)
        try {
            port.setCurrentSong(song.id)
            awaitUntil { vm.stickyCover.value == song.coverUri }
            vm.onCoverLoadFailed(song.coverUri!!)
            awaitUntil { vm.stickyCover.value == "https://cdn.test/backup.jpg" }
            assertEquals(2, calls)
            vm.onCoverLoadFailed(song.coverUri!!)
            assertEquals(2, calls)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun switchingTracksDoesNotRetainOldArtwork() = runBlocking {
        val first = onlineSong(id = "online:kw:first", title = "First")
        val second = onlineSong(id = "online:kw:second", title = "Second").copy(coverUri = null)
        OnlineTrackSession.remember(listOf(first, second))
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), FakeMetadataResolver(), null, null)
        try {
            port.setCurrentSong(first.id)
            awaitUntil { vm.stickyCover.value == first.coverUri }
            port.setCurrentSong(second.id)
            awaitUntil { vm.nowPlayingMeta.value?.songId == second.id }
            assertEquals("Second", vm.nowPlayingMeta.value?.title)
            assertEquals(null, vm.stickyCover.value)
            assertEquals(null, vm.lyricsDocument.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun knownCoverDoesNotWaitForSlowScript() = runBlocking {
        val song = onlineSong(id = "online:kw:slow")
        OnlineTrackSession.remember(listOf(song))
        val slow = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? { delay(3000); return null }
            override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? = null
        }
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), slow, null, null)
        try {
            port.setCurrentSong(song.id)
            awaitUntil(timeoutMs = 1000) { vm.stickyCover.value == song.coverUri }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun lyricsAppearWithoutWaitingForSlowCover() = runBlocking {
        val song = onlineSong(id = "online:kw:slow-cover")
        OnlineTrackSession.remember(listOf(song))
        val resolver = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? { delay(3000); return null }
            override suspend fun resolveLyrics(ref: OnlineTrackRef) = OnlineTrackLyrics(lyric = "[00:01.000]及时显示")
        }
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), resolver, null, null)
        try {
            port.setCurrentSong(song.id)
            awaitUntil(timeoutMs = 1000) { vm.lyricsDocument.value?.lines?.firstOrNull()?.text == "及时显示" }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun oldMetadataIsNotAppliedToNewSong() = runBlocking {
        val first = onlineSong(id = "online:kw:old", title = "Old")
        val second = onlineSong(id = "online:kw:new", title = "New").copy(coverUri = null)
        OnlineTrackSession.remember(listOf(first, second))
        val slow = object : OnlineTrackMetadataResolver {
            override suspend fun resolveCover(ref: OnlineTrackRef): String? { delay(500); return "https://old.example/cover" }
            override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? = null
        }
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), slow, null, null)
        try {
            port.setCurrentSong(first.id)
            awaitUntil { vm.nowPlayingMeta.value?.songId == first.id }
            port.setCurrentSong(second.id)
            awaitUntil { vm.nowPlayingMeta.value?.songId == second.id }
            assertEquals(null, vm.stickyCover.value)
            assertEquals("New", vm.nowPlayingMeta.value?.title)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun 歌词结束后播放进度仍继续更新() = runBlocking {
        val entity = SongEntity(id = "progress", sourceId = "s1", sourceType = SourceType.LOCAL.name,
            path = "/music/progress.mp3", title = "测试歌曲", lyrics = ttml)
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(mapOf(entity.id to entity)), FakeMetadataResolver(), null, null)
        try {
            port.setCurrentSong(entity.id)
            port.setPlaying(true)
            awaitUntil { vm.lyricsDocument.value != null }
            port.positionMs = 10_000
            awaitUntil { vm.position.value == 10_000L }
            port.positionMs = 11_000
            awaitUntil { vm.position.value == 11_000L }
            assertTrue(vm.lyricPosition.value <= 2_000L)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun 返回前台清除中断的拖动且继续轮询() = runBlocking {
        val port = FakePort()
        val vm = PlayerViewModel(port, FakeSongDao(), FakeMetadataResolver(), null, null)
        try {
            port.setPlaying(true)
            vm.onSeekStart()
            port.positionMs = 30_000
            vm.onPlaybackUiResumed()
            assertEquals(false, vm.isSeeking.value)
            assertEquals(30_000L, vm.position.value)
            port.positionMs = 31_000
            awaitUntil { vm.position.value == 31_000L }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun 曲库缺少译文时不向在线歌词源补取() = runBlocking {
        for (sourceType in listOf(SourceType.LOCAL, SourceType.WEBDAV)) {
            val entity = SongEntity(
                id = "song:" + sourceType.name,
                sourceId = "s1",
                sourceType = sourceType.name,
                path = "/music/translated.mp3",
                title = "Hello",
                artist = "Singer",
                lyrics = "[00:01.000]Hello",
            )
            var onlineRequests = 0
            val provider = object : LyricsProvider {
                override val id = OnlineLyricsSource.WY
                override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit {
                    onlineRequests++
                    return OnlineLyricsProviderHit(
                        text = "[00:01.000]Hello",
                        format = com.muses.player.core.model.lyrics.OnlineLyricsFormat.LRC,
                        translationText = "[00:01.000]你好",
                    )
                }
            }
            val matcher = LyricsMatcher(FakeAmll(), listOf(provider))
            val port = FakePort()
            val viewModel = PlayerViewModel(
                port,
                FakeSongDao(mapOf(entity.id to entity)),
                FakeMetadataResolver(),
                matcher,
                null,
            )
            port.setCurrentSong(entity.id)
            awaitUntil { viewModel.lyricsDocument.value?.lines?.firstOrNull()?.text == "Hello" }
            delay(50)
            assertEquals(null, viewModel.lyricsDocument.value?.lines?.first()?.translation)
            assertEquals(0, onlineRequests)
            viewModel.viewModelScope.cancel()
        }
    }

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div>
            <p begin="00:00.000" end="00:02.000">匹配到的歌词</p>
          </div></body>
        </tt>
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { OnlineTrackSession.clear() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { OnlineTrackSession.clear() }
        Dispatchers.resetMain()
    }

    // ── 测试替身 ──────────────────────────────────────────

    private class FakePort : PlaybackPort {
        val publishedArtwork = mutableListOf<Pair<String, String>>()
        override fun updateArtwork(songId: String, coverUri: String) {
            publishedArtwork += songId to coverUri
        }
        @Volatile var positionMs = 0L
        fun setPlaying(value: Boolean) { _isPlaying.value = value }
        override val playbackState: StateFlow<Int> = MutableStateFlow(PlaybackStates.STATE_READY)
        override val playbackError: StateFlow<String?> = MutableStateFlow(null)
        override val playerConfig: StateFlow<PlayerConfig> = MutableStateFlow(PlayerConfig())
        private val _isPlaying = MutableStateFlow(false)
        private val _currentSongId = MutableStateFlow<String?>(null)
        private val _artworkUri = MutableStateFlow<String?>(null)
        private val _currentMeta = MutableStateFlow<PlaybackMeta?>(null)
        private val _duration = MutableStateFlow(0L)
        private val _queue = MutableStateFlow<List<String>>(emptyList())

        override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
        override val currentSongId: StateFlow<String?> = _currentSongId.asStateFlow()
        override val artworkUri: StateFlow<String?> = _artworkUri.asStateFlow()
        override val currentMeta: StateFlow<PlaybackMeta?> = _currentMeta.asStateFlow()
        override val duration: StateFlow<Long> = _duration.asStateFlow()
        override val queueSongIds: StateFlow<List<String>> = _queue.asStateFlow()

        fun setCurrentSong(id: String?) {
            _currentSongId.value = id
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun enqueue(ids: List<String>, index: Int) = Unit
        override fun addToQueue(songs: List<com.muses.player.core.model.Song>) = Unit
        override fun setRepeatMode(mode: Int) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit
        override fun setShuffleEnabled(enabled: Boolean) = Unit
        override fun play(songId: String, songs: List<Song>) = Unit
        override fun playPause() = Unit
        override fun playAtIndex(index: Int) = Unit
        override fun removeQueueItemAt(index: Int) = Unit
        override fun removeFromQueue(songIds: Set<String>) = Unit
        override fun clearQueueItems() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun currentPosition(): Long = positionMs
        override fun clearPlaybackError() = Unit
        override fun resetRecovery() = Unit
    }

    /** 只服务 observeById 的曲库替身（其余成员本用例不触达） */
    private class FakeSongDao(private val songs: Map<String, SongEntity> = emptyMap()) : SongDao {
        override suspend fun insertAll(songs: List<SongEntity>) = error("unused")
        override suspend fun upsert(song: SongEntity) = error("unused")
        override fun observeAll(): Flow<List<SongEntity>> = flowOf(songs.values.toList())
        override fun observeSearch(query: String): Flow<List<SongEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<SongEntity> = songs.values.toList()
        override suspend fun getUntaggedSongIds(): List<String> = emptyList()
        override suspend fun getById(id: String): SongEntity? = songs[id]
        override suspend fun getByIds(ids: List<String>): List<SongEntity> = ids.mapNotNull { songs[it] }
        override suspend fun getExistingIds(ids: List<String>): List<String> = ids.filter { songs.containsKey(it) }
        override fun observeById(id: String): Flow<SongEntity?> = flowOf(songs[id])
        override fun observeByIds(ids: List<String>): Flow<List<SongEntity>> = flowOf(ids.mapNotNull { songs[it] })
        override suspend fun searchByTitle(query: String): List<SongEntity> = emptyList()
        override suspend fun getBySource(sourceId: String): List<SongEntity> = emptyList()
        override suspend fun markAllMissing(sourceId: String) = error("unused")
        override suspend fun deleteBySource(sourceId: String) = error("unused")
        override suspend fun deleteById(id: String) = error("unused")
        override suspend fun markFileMissing(id: String, sourceId: String) = error("unused")
        override suspend fun count(): Int = songs.size
        override suspend fun clearSongAlbumRefs() = error("unused")
        override suspend fun clearSongArtistRefs() = error("unused")
        override suspend fun insertSongAlbumRefs(refs: List<SongAlbumCrossRef>) = error("unused")
        override suspend fun insertSongArtistRefs(refs: List<SongArtistCrossRef>) = error("unused")
    }

    private class FakeMetadataResolver(
        var cover: String? = null,
        var lyrics: OnlineTrackLyrics? = null,
    ) : OnlineTrackMetadataResolver {
        override suspend fun resolveCover(ref: OnlineTrackRef): String? = cover
        override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? = lyrics
    }

    private class FakeAmll(var result: AmllMatchResult = AmllMatchResult.Fail(AmllFailReason.NO_MATCH)) :
        AmllTtmlDbClient(http = LyricsHttp(), indexRepository = AmllIndexRepository { "" }) {
        override suspend fun match(query: AmllMatchQuery): AmllMatchResult = result
    }

    private class HitProvider(private val hit: OnlineLyricsProviderHit) : LyricsProvider {
        override val id: OnlineLyricsSource = OnlineLyricsSource.KW
        override suspend fun searchLyrics(query: OnlineLyricsQuery): OnlineLyricsProviderHit = hit
    }

    private fun onlineSong(id: String = "online:kw:1", title: String = "在线曲目"): Song = Song(
        id = id,
        sourceId = "s1",
        path = OnlineTrackRef(platform = "kw", musicInfoJson = """{"songmid":"x"}""", sourceId = "s1").encode(),
        title = title,
        artist = "在线歌手",
        album = "在线专辑",
        durationMs = 215_000L,
        durationSec = 215L,
        coverUri = "http://cdn.test/search-cover.jpg",
        sourceType = SourceType.ONLINE,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    // ── 用例 ──────────────────────────────────────────────

    @Test
    fun `在线曲目 脚本无歌词时回退 Muses 匹配并把歌词送上屏`() = runBlocking {
        val song = onlineSong()
        OnlineTrackSession.remember(listOf(song))
        val dao = FakeSongDao() // 在线曲目不入库
        // 脚本不支持 lyric（多数洛雪脚本如此）
        val resolver = FakeMetadataResolver(cover = null, lyrics = null)
        val matcher = LyricsMatcher(
            FakeAmll(
                AmllMatchResult.Ok(ttml = ttml, rawLyricFile = "a.ttml", score = 100, confidence = MatchConfidence.HIGH),
            ),
            emptyList(),
        )
        val port = FakePort()
        val viewModel = PlayerViewModel(port, dao, resolver, matcher, null)

        port.setCurrentSong(song.id)
        awaitUntil { viewModel.lyricsDocument.value != null }

        assertEquals("在线曲目", viewModel.nowPlayingMeta.value?.title)
        assertEquals(true, viewModel.nowPlayingMeta.value?.isOnline)
        assertEquals("匹配到的歌词", viewModel.lyricsDocument.value?.lines?.first()?.text)
        assertTrue(viewModel.parsedLines.value.isNotEmpty())
        // 封面：搜索结果自带封面优先（零请求），无需脚本 pic
        assertEquals("http://cdn.test/search-cover.jpg", viewModel.stickyCover.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `在线曲目 脚本有歌词时优先用脚本（逐字）`() = runBlocking {
        val song = onlineSong(id = "online:kw:2")
        OnlineTrackSession.remember(listOf(song))
        val resolver = FakeMetadataResolver(
            cover = "http://cdn.test/script-cover.jpg",
            lyrics = OnlineTrackLyrics(lxlyric = "[00:01.000]<1000,200>脚<1200,300>本"),
        )
        val matcher = LyricsMatcher(FakeAmll(), listOf(HitProvider(OnlineLyricsProviderHit("[00:09.000]不该用我", com.muses.player.core.model.lyrics.OnlineLyricsFormat.LRC))))
        val port = FakePort()
        val viewModel = PlayerViewModel(port, FakeSongDao(), resolver, matcher, null)

        port.setCurrentSong(song.id)
        // 渐进加载允许先到的行歌词先显示；脚本逐字到达后必须升级，不能停留在低档结果。
        awaitUntil { viewModel.lyricsDocument.value?.lines?.firstOrNull()?.syllables?.size == 2 }

        val line = viewModel.lyricsDocument.value?.lines?.first()
        assertEquals("脚本", line?.text)
        assertEquals(2, line?.syllables?.size)
        // 脚本封面优先于搜索结果封面
        assertEquals("http://cdn.test/script-cover.jpg", viewModel.stickyCover.value)
        assertTrue(song.id to "http://cdn.test/script-cover.jpg" in port.publishedArtwork)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `脚本先返回行歌词再返回逐字时继续重试并替换匹配结果`() = runBlocking {
        // 复刻实测到的脚本时序（星海音乐源）：`lyric` 只是 `musicUrl` 的副产品，
        // 直链未解析前问 lyric 恒为空；解析完成后才有值。
        val song = onlineSong(id = "online:kw:3")
        OnlineTrackSession.remember(listOf(song))
        val resolver = object : OnlineTrackMetadataResolver {
            private var lyricCalls = 0
            override suspend fun resolveCover(ref: OnlineTrackRef): String? = "https://cover.test/cover.jpg"
            override suspend fun resolveLyrics(ref: OnlineTrackRef): OnlineTrackLyrics? {
                lyricCalls++
                // 脚本带真实逐字 → 值得替换掉行级匹配结果
                return if (lyricCalls >= 3) {
                    OnlineTrackLyrics(lxlyric = "[00:01.000]<1000,200>脚<1200,200>本")
                } else if (lyricCalls == 2) {
                    OnlineTrackLyrics(lyric = "[00:01.000]脚本行歌词")
                } else {
                    null
                }
            }
        }
        val matcher = LyricsMatcher(
            FakeAmll(AmllMatchResult.Ok(ttml = ttml, rawLyricFile = "a.ttml", score = 100)),
            emptyList(),
        )
        val port = FakePort()
        val viewModel = PlayerViewModel(port, FakeSongDao(), resolver, matcher, null)

        port.setCurrentSong(song.id)
        // ① 不等待：立即用匹配结果兜底上屏
        awaitUntil { viewModel.lyricsDocument.value?.lines?.first()?.text == "匹配到的歌词" }
        // ② 重试拿到脚本逐字歌词后替换掉匹配结果
        awaitUntil(timeoutMs = 8_000L) {
            viewModel.lyricsDocument.value?.lines?.first()?.text == "脚本"
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `脚本行级歌词不会覆盖匹配结果（避免脏时间轴）`() = runBlocking {
        // 实测：脚本给的平台 LRC 会把末尾 credits 全标成 [00:00.00]，覆盖后歌词会停在制作名单上
        val song = onlineSong(id = "online:kw:4")
        OnlineTrackSession.remember(listOf(song))
        val resolver = FakeMetadataResolver(lyrics = OnlineTrackLyrics(lyric = "[00:00.000]录音室：Show Music"))
        val matcher = LyricsMatcher(
            FakeAmll(AmllMatchResult.Ok(ttml = ttml, rawLyricFile = "a.ttml", score = 100)),
            emptyList(),
        )
        val port = FakePort()
        val viewModel = PlayerViewModel(port, FakeSongDao(), resolver, matcher, null)

        port.setCurrentSong(song.id)
        awaitUntil { viewModel.lyricsDocument.value != null }
        delay(2_500) // 跨过第一次重试窗口，确认没被脚本行级歌词换掉
        assertEquals("匹配到的歌词", viewModel.lyricsDocument.value?.lines?.first()?.text)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `曲库曲目 歌词仍来自曲库列`() = runBlocking {
        val entity = SongEntity(
            id = "local:1",
            sourceId = "s1",
            sourceType = SourceType.LOCAL.name,
            path = "/music/a.mp3",
            title = "本地曲目",
            artist = "本地歌手",
            lyrics = "[00:00.000]本地歌词",
            coverUri = "file:///cover.jpg",
        )
        val port = FakePort()
        val viewModel = PlayerViewModel(port, FakeSongDao(mapOf(entity.id to entity)), FakeMetadataResolver(), null, null)

        port.setCurrentSong(entity.id)
        awaitUntil { viewModel.lyricsDocument.value != null }

        assertEquals("本地曲目", viewModel.nowPlayingMeta.value?.title)
        assertEquals(false, viewModel.nowPlayingMeta.value?.isOnline)
        assertEquals("本地歌词", viewModel.lyricsDocument.value?.lines?.first()?.text)
        assertEquals("file:///cover.jpg", viewModel.stickyCover.value)

        viewModel.viewModelScope.cancel()
    }
}
