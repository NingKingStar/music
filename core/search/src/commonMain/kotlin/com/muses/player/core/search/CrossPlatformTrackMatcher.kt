package com.muses.player.core.search

import com.muses.player.core.model.online.OnlineTrackCandidateProvider
import com.muses.player.core.model.online.OnlineTrackRef
import com.muses.player.core.search.provider.long
import com.muses.player.core.search.provider.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 只回退到歌名、歌手、版本一致的候选，避免把同名翻唱或现场版当成原曲。 */
class CrossPlatformTrackMatcher(private val search: OnlineSearchService) : OnlineTrackCandidateProvider {
    override suspend fun candidates(ref: OnlineTrackRef, platforms: List<String>): List<OnlineTrackRef> {
        val targets = platforms.filter { it != ref.platform && it in search.platforms }.distinct()
        if (targets.isEmpty()) return emptyList()
        val info = runCatching { Json.parseToJsonElement(ref.musicInfoJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val name = info.str("name") ?: info.str("songName") ?: info.str("title") ?: return emptyList()
        val artist = info.str("singer") ?: info.str("artist") ?: return emptyList()
        val expectedArtists = artists(artist)
        if (expectedArtists.isEmpty()) return emptyList()
        val duration = info.long("interval") ?: info.long("duration")
        val album = normalize(info.str("album").orEmpty())
        val results = coroutineScope {
            targets.map { platform -> async {
                try {
                    withTimeoutOrNull(6_000) {
                        search.search(platform, "$name $artist", pageSize = 20).results
                    }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            } }.flatMap { it.await() }
        }
        return results.filter { candidate ->
            normalize(candidate.name) == normalize(name) && artists(candidate.artist.orEmpty()) == expectedArtists &&
                (duration == null || candidate.durationMs == null || abs(candidate.durationMs / 1000 - duration) <= 10)
        }.sortedByDescending { normalize(it.album.orEmpty()) == album && album.isNotEmpty() }
            .groupBy { it.platform }.values.flatMap { it.take(2) }
            .map { it.toTrackRef(ref.sourceId, ref.quality).copy(qualityExplicit = ref.qualityExplicit) }
    }

    private fun artists(value: String): Set<String> = value.split(Regex("[/、,，&;；]+"))
        .map(::normalize).filter { it.isNotEmpty() }.toSet()

    private fun normalize(value: String): String = value.lowercase()
        .replace('（', '(').replace('）', ')').filter { it.isLetterOrDigit() }
}
