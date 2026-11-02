package com.muses.player.feature.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.muses.player.core.search.ArtistSongPage
import com.muses.player.core.search.ArtistProfile
import com.muses.player.core.data.store.platformNowMs

@Serializable
data class CachedArtistPage(val value: ArtistSongPage, val at: Long)
@Serializable
data class CachedArtistProfile(val value: ArtistProfile, val at: Long)
class ArtistPageCache(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("artist_pages_v1")
    private val profileKey = stringPreferencesKey("artist_profiles_v1")
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun loadProfile(id: String): CachedArtistProfile? {
        val text = store.data.first()[profileKey] ?: "{}"
        return runCatching { json.decodeFromString<Map<String, CachedArtistProfile>>(text)[id] }.getOrNull()
            ?.takeIf { platformNowMs() - it.at in 0..86_400_000 }
    }
    suspend fun saveProfile(id: String, profile: ArtistProfile) {
        store.edit { prefs ->
            val values = runCatching { json.decodeFromString<Map<String, CachedArtistProfile>>(prefs[profileKey] ?: "{}") }.getOrDefault(emptyMap()).toMutableMap()
            values[id] = CachedArtistProfile(profile, platformNowMs())
            prefs[profileKey] = json.encodeToString(values.entries.sortedByDescending { it.value.at }.take(24).associate { it.toPair() })
        }
    }
    private fun decode(text: String?): Map<String, CachedArtistPage> = runCatching {
        json.decodeFromString<Map<String, CachedArtistPage>>(text ?: "{}")
    }.getOrDefault(emptyMap())
    suspend fun load(id: String): CachedArtistPage? = decode(store.data.first()[key])[id]?.takeIf {
        platformNowMs() - it.at in 0..21_600_000
    }
    suspend fun save(id: String, page: ArtistSongPage) {
        store.edit { prefs ->
            val entries = decode(prefs[key]).toMutableMap()
            entries[id] = CachedArtistPage(page, platformNowMs())
            val bounded = entries.entries.sortedByDescending { it.value.at }.take(24).associate { it.toPair() }.toMutableMap()
            var text = json.encodeToString(bounded)
            while (text.length > 1_000_000 && bounded.size > 1) {
                bounded.remove(bounded.keys.last()); text = json.encodeToString(bounded)
            }
            if (text.length <= 1_000_000) prefs[key] = text
        }
    }
}
