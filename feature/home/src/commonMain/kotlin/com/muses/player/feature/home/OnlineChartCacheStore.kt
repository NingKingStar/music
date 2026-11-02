package com.muses.player.feature.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muses.player.core.search.OnlineChart
import com.muses.player.core.search.OnlineSearchResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

data class CachedChartCatalog(val charts: List<OnlineChart>, val updatedAt: Long)

data class CachedChartSongs(
    val songs: List<OnlineSearchResult>,
    val page: Int,
    val hasMore: Boolean,
    val updatedAt: Long,
)

/** 持久化榜单目录与歌曲页，避免每次启动或重新进入都请求网络。 */
class OnlineChartCacheStore(private val dataStore: DataStore<Preferences>) {
    suspend fun loadCover(platform: String, chartId: String): String? {
        val raw = dataStore.data.first()[COVERS_KEY] ?: return null
        val entry = runCatching { Json.parseToJsonElement(raw).jsonObject["$platform:$chartId"] as? JsonObject }.getOrNull() ?: return null
        val age = Clock.System.now().toEpochMilliseconds() - (entry.long("at") ?: 0L)
        return entry.string("url")?.takeIf { it.isNotBlank() && age in 0..CATALOG_COVER_TTL_MS }
    }

    suspend fun saveCover(platform: String, chartId: String, url: String) {
        if (url.isBlank()) return
        dataStore.edit { prefs ->
            val entries = runCatching { Json.parseToJsonElement(prefs[COVERS_KEY] ?: "{}").jsonObject.toMutableMap() }.getOrDefault(mutableMapOf())
            entries["$platform:$chartId"] = buildJsonObject { put("url", JsonPrimitive(url)); put("at", JsonPrimitive(Clock.System.now().toEpochMilliseconds())) }
            val bounded = entries.entries.sortedByDescending { (it.value as? JsonObject)?.long("at") ?: 0L }.take(128).associate { it.toPair() }
            prefs[COVERS_KEY] = JsonObject(bounded).toString()
        }
    }

    suspend fun loadCatalogs(): Map<String, CachedChartCatalog> = runCatching {
        val raw = dataStore.data.first()[CATALOG_KEY] ?: return emptyMap()
        val root = Json.parseToJsonElement(raw).jsonObject
        root.mapNotNull { (platform, value) ->
            val record = value as? JsonObject
            val chartArray = when (value) {
                is JsonArray -> value // 兼容早期仅保存榜单列表的缓存，按过期处理一次
                else -> record?.get("charts") as? JsonArray
            } ?: return@mapNotNull null
            val charts = chartArray.mapNotNull { element ->
                val chart = element as? JsonObject ?: return@mapNotNull null
                val chartId = chart.string("chartId") ?: return@mapNotNull null
                OnlineChart(
                    platform = platform,
                    chartId = chartId,
                    name = chart.string("name") ?: return@mapNotNull null,
                    coverUrl = chart.string("coverUrl"),
                    updateInfo = chart.string("updateInfo"),
                )
            }
            platform to CachedChartCatalog(charts, record?.long("updatedAt") ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun saveCatalogs(catalogs: Map<String, CachedChartCatalog>) {
        val root = buildJsonObject {
            catalogs.forEach { (platform, catalog) ->
                put(platform, buildJsonObject {
                    put("updatedAt", JsonPrimitive(catalog.updatedAt))
                    put("charts", buildJsonArray { catalog.charts.forEach { add(it.toJson()) } })
                })
            }
        }
        dataStore.edit { it[CATALOG_KEY] = Json.encodeToString(JsonObject.serializer(), root) }
    }

    suspend fun loadSongs(platform: String, chartId: String): CachedChartSongs? = runCatching {
        val raw = dataStore.data.first()[SONGS_KEY] ?: return null
        val item = Json.parseToJsonElement(raw).jsonObject[cacheId(platform, chartId)] as? JsonObject ?: return null
        val songs = (item["songs"] as? JsonArray)?.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            OnlineSearchResult(
                platform = song.string("platform") ?: return@mapNotNull null,
                songId = song.string("songId") ?: return@mapNotNull null,
                name = song.string("name") ?: return@mapNotNull null,
                artist = song.string("artist"),
                album = song.string("album"),
                durationMs = song.long("durationMs"),
                coverUrl = song.string("coverUrl"),
                musicInfoJson = song.string("musicInfoJson") ?: return@mapNotNull null,
            )
        } ?: return null
        CachedChartSongs(
            songs = songs,
            page = item.long("page")?.toInt() ?: 1,
            hasMore = item.boolean("hasMore") ?: false,
            updatedAt = item.long("updatedAt") ?: 0L,
        )
    }.getOrNull()

    suspend fun saveSongs(platform: String, chartId: String, cache: CachedChartSongs) {
        val key = cacheId(platform, chartId)
        dataStore.edit { preferences ->
            val root = runCatching {
                Json.parseToJsonElement(preferences[SONGS_KEY].orEmpty()).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            val updated = buildJsonObject {
                root.forEach { (id, value) -> if (id != key) put(id, value) }
                put(key, buildJsonObject {
                    put("updatedAt", JsonPrimitive(cache.updatedAt))
                    put("page", JsonPrimitive(cache.page))
                    put("hasMore", JsonPrimitive(cache.hasMore))
                    put("songs", buildJsonArray { cache.songs.forEach { add(it.toJson()) } })
                })
            }
            val bounded = updated.entries
                .sortedByDescending { (_, value) -> (value as? JsonObject)?.long("updatedAt") ?: 0L }
                .take(MAX_CACHED_CHARTS)
                .associate { it.key to it.value }
            preferences[SONGS_KEY] = Json.encodeToString(JsonObject.serializer(), JsonObject(bounded))
        }
    }

    fun isCatalogFresh(updatedAt: Long, now: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        isFresh(updatedAt, CATALOG_TTL_MS, now)

    fun isSongsFresh(
        updatedAt: Long,
        updateInfo: String?,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = isFresh(updatedAt, songsTtl(updateInfo), now)

    private fun isFresh(updatedAt: Long, ttl: Long, now: Long): Boolean =
        updatedAt > 0L && now >= updatedAt && now - updatedAt < ttl

    private fun cacheId(platform: String, chartId: String) = "$platform:$chartId"

    private fun OnlineChart.toJson() = buildJsonObject {
        put("chartId", JsonPrimitive(chartId))
        put("name", JsonPrimitive(name))
        coverUrl?.let { put("coverUrl", JsonPrimitive(it)) }
        updateInfo?.let { put("updateInfo", JsonPrimitive(it)) }
    }

    private fun OnlineSearchResult.toJson() = buildJsonObject {
        put("platform", JsonPrimitive(platform))
        put("songId", JsonPrimitive(songId))
        put("name", JsonPrimitive(name))
        artist?.let { put("artist", JsonPrimitive(it)) }
        album?.let { put("album", JsonPrimitive(it)) }
        durationMs?.let { put("durationMs", JsonPrimitive(it)) }
        coverUrl?.let { put("coverUrl", JsonPrimitive(it)) }
        put("musicInfoJson", JsonPrimitive(musicInfoJson))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        val COVERS_KEY = stringPreferencesKey("online_chart_cover_cache")
        const val CATALOG_COVER_TTL_MS = 24L * 60 * 60 * 1000
        val CATALOG_KEY = stringPreferencesKey("online_chart_catalog_cache")
        val SONGS_KEY = stringPreferencesKey("online_chart_songs_cache")
        const val CATALOG_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_CACHED_CHARTS = 20

        fun songsTtl(updateInfo: String?): Long {
            val info = updateInfo.orEmpty().lowercase()
            return when {
                "月" in info || "month" in info -> 30L * 24 * 60 * 60 * 1000
                "周" in info || "week" in info -> 7L * 24 * 60 * 60 * 1000
                "日" in info || "daily" in info || "day" in info -> 24L * 60 * 60 * 1000
                else -> 24L * 60 * 60 * 1000
            }
        }
    }
}
