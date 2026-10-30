package com.muses.player.core.lyrics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.muses.player.core.data.repository.DataStoreSettingsRepository
import com.muses.player.core.data.repository.LyricSupplementMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsDisplayPreferencesTest {
    @Test fun translationAndRomanizationAreExclusiveAndPersistAfterRestart() = runBlocking {
        val dir = Files.createTempDirectory("lyric-exclusive").toFile()
        val path = dir.resolve("lyric.preferences_pb").absolutePath.toPath()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path }))
            assertEquals(LyricSupplementMode.Translation, repository.lyricSupplementMode.first())
            assertEquals(false, repository.lyricRomanizationEnabled.first())
            repository.setLyricRomanizationEnabled(true)
            assertEquals(false, repository.lyricTranslationEnabled.first())
            assertEquals(true, repository.lyricRomanizationEnabled.first())
            repository.setLyricTranslationEnabled(true)
            assertEquals(true, repository.lyricTranslationEnabled.first())
            assertEquals(false, repository.lyricRomanizationEnabled.first())
            repository.setLyricTranslationEnabled(false)
            assertEquals(LyricSupplementMode.Original, repository.lyricSupplementMode.first())
            repository.setLyricRomanizationEnabled(true)
            repository.setLyricRomanizationEnabled(false)
            assertEquals(LyricSupplementMode.Original, repository.lyricSupplementMode.first())
            repository.setLyricRomanizationEnabled(true)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reloaded = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path }))
            assertEquals(LyricSupplementMode.Romanization, reloaded.lyricSupplementMode.first())
            assertEquals(false, reloaded.lyricTranslationEnabled.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun legacyBothEnabledDoesNotDisplayBothAndCanSwitchToRomanization() = runBlocking {
        val dir = Files.createTempDirectory("lyric-legacy").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.createWithPath(scope = scope,
                produceFile = { dir.resolve("lyric.preferences_pb").absolutePath.toPath() })
            store.edit {
                it[booleanPreferencesKey("lyric_translation_enabled")] = true
                it[booleanPreferencesKey("lyric_romanization_enabled")] = true
            }
            val repository = DataStoreSettingsRepository(store)
            assertEquals(LyricSupplementMode.Translation, repository.lyricSupplementMode.first())
            assertEquals(false, repository.lyricRomanizationEnabled.first())
            repository.setLyricRomanizationEnabled(true)
            assertEquals(LyricSupplementMode.Romanization, repository.lyricSupplementMode.first())
            assertEquals(false, repository.lyricTranslationEnabled.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun displayPreferencesPersistAcrossStoreRestart() = runBlocking {
        val dir = Files.createTempDirectory("lyric-prefs").toFile()
        val path = dir.resolve("lyric.preferences_pb").absolutePath.toPath()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path }))
            repository.setLyricTextSize(99)
            repository.setLyricLineSpacing(-1)
            repository.setLyricWordHighlightEnabled(false)
            repository.setLyricTranslationEnabled(false)
            repository.setLyricRomanizationEnabled(false)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reloaded = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path }))
            assertEquals(32L, reloaded.lyricTextSize.first())
            assertEquals(12L, reloaded.lyricLineSpacing.first())
            assertEquals(false, reloaded.lyricWordHighlightEnabled.first())
            assertEquals(false, reloaded.lyricTranslationEnabled.first())
            assertEquals(false, reloaded.lyricRomanizationEnabled.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }
}
