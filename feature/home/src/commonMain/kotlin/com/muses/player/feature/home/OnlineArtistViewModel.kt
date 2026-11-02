package com.muses.player.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.model.Song
import com.muses.player.core.model.online.OnlineArtistRef
import com.muses.player.core.model.online.OnlineTrackSession
import com.muses.player.core.playback.PlaybackPort
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.muses.player.core.data.store.platformNowMs

data class OnlineArtistState(val artist: OnlineArtistRef? = null, val candidates: List<OnlineArtistRef> = emptyList(),
    val confirmed: Boolean = false, val songs: List<OnlineSearchResult> = emptyList(), val loading: Boolean = true,
    val hasMore: Boolean = false, val nextPage: Int = 1, val error: String? = null, val cached: Boolean = false,
    val profile: ArtistProfile? = null, val profileLoading: Boolean = false, val profileError: Boolean = false)

class OnlineArtistViewModel(private val service: OnlineArtistService, private val cache: ArtistPageCache,
    private val playback: PlaybackPort, private val settings: SettingsRepository, private val seed: Song) : ViewModel() {
    private val mutable = MutableStateFlow(OnlineArtistState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var resolutionJob: Job? = null
    private var profileJob: Job? = null
    private var retryRefresh = false
    private var generation = 0L
    init { resolve() }
    fun resolve() {
        val token = ++generation; resolutionJob?.cancel(); job?.cancel(); profileJob?.cancel()
        mutable.value = OnlineArtistState()
        resolutionJob = viewModelScope.launch {
            try {
                val result = service.resolve(seed)
                if (token != generation) return@launch
                mutable.value = OnlineArtistState(candidates = result.artists, confirmed = result.confirmed, loading = false,
                    error = if (result.artists.isEmpty()) "暂未找到歌手，可稍后重试" else null)
                if (result.confirmed && result.artists.size == 1) select(result.artists.single())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.value = mutable.value.copy(loading = false, error = "歌手信息加载失败，请重试") }
        }
    }
    fun select(artist: OnlineArtistRef) {
        resolutionJob?.cancel(); job?.cancel(); profileJob?.cancel(); ++generation; retryRefresh = false
        mutable.value = mutable.value.copy(artist = artist, songs = emptyList(), loading = false, hasMore = true, nextPage = 1, error = null, cached = false,
            profile = null, profileLoading = true, profileError = false)
        load()
        loadProfile()
    }
    fun retry() { if (state.value.artist == null) resolve() else load(retryRefresh) }
    fun refresh() { load(true); loadProfile(true) }
    fun loadProfile(force: Boolean = false) {
        val artist = state.value.artist ?: return
        val token = generation
        profileJob?.cancel()
        mutable.update { it.copy(profileLoading = true, profileError = false) }
        profileJob = viewModelScope.launch {
            try {
                val key = "${artist.platform}:${artist.id}"
                val saved = try { cache.loadProfile(key) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (token != generation) return@launch
                if (saved != null) mutable.update { it.copy(profile = saved.value) }
                val profile = if (!force && saved != null && platformNowMs() - saved.at in 0..21_600_000) saved.value else service.profile(artist, force)
                if (token != generation) return@launch
                mutable.update { it.copy(profile = profile, profileLoading = false, profileError = false) }
                try { cache.saveProfile(key, profile) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(profileLoading = false, profileError = true) } }
        }
    }
    fun load(refresh: Boolean = false) {
        val current = state.value
        val artist = current.artist ?: return
        if (current.loading || (!refresh && !current.hasMore)) return
        val page = if (refresh) 1 else current.nextPage
        retryRefresh = refresh
        val token = generation
        mutable.value = current.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val key = "${artist.platform}:${artist.id}:$page"
                val saved = try { cache.load(key) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (token != generation) return@launch
                if (page == 1 && saved != null && state.value.songs.isEmpty()) {
                    mutable.value = state.value.copy(songs = saved.value.songs, cached = true)
                }
                val result = if (!refresh && saved != null && platformNowMs() - saved.at in 0..1_800_000) saved.value else service.songs(artist, page, refresh)
                if (token != generation) return@launch
                val previous = if (page == 1) emptyList() else state.value.songs
                val merged = (previous + result.songs).distinctBy { it.platform to it.songId }
                mutable.value = state.value.copy(songs = merged, loading = false, nextPage = page+1,
                    hasMore = result.hasMore && result.songs.isNotEmpty() && (page == 1 || merged.size > previous.size), error = null, cached = false)
                try { cache.save(key, result) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.value = state.value.copy(loading = false,
                error = if (state.value.cached) "当前显示缓存，刷新失败，可重试" else "歌曲加载失败，请重试") }
        }
    }
    fun play(results: List<OnlineSearchResult>, index: Int) {
        val snapshot = results.toList()
        if (index !in snapshot.indices) return
        viewModelScope.launch {
            val quality = settings.onlinePreferredQuality.first()
            val songs = snapshot.map { it.toSong("online", quality) }
            OnlineTrackSession.remember(songs); playback.play(songs[index].id, songs)
        }
    }
}
