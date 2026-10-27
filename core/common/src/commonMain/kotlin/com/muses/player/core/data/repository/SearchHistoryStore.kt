package com.muses.player.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅在本机保存最近搜索词；不发网络请求，不参与云同步。 */
class SearchHistoryStore(private val data: DataStore<Preferences>) {
    private val key = stringPreferencesKey("recent_searches_v1")
    private fun decode(raw: String?): List<String> = raw?.let { Json.decodeFromString(it) } ?: emptyList()
    val entries = data.data.map { it[key] }.distinctUntilChanged().map(::decode)

    suspend fun record(keyword: String, replaceDraft: String? = null) {
        val word = keyword.trim().take(100)
        if (word.isEmpty()) return
        data.edit { prefs ->
            prefs[key] = Json.encodeToString((listOf(word) + decode(prefs[key]).filterNot {
                it.equals(word, ignoreCase = true) || (replaceDraft != null && it.equals(replaceDraft, ignoreCase = true))
            }).take(30))
        }
    }

    suspend fun remove(keyword: String) { data.edit { prefs ->
        prefs[key] = Json.encodeToString(decode(prefs[key]).filterNot { it == keyword })
    } }

    suspend fun clear() { data.edit { it.remove(key) } }
}
