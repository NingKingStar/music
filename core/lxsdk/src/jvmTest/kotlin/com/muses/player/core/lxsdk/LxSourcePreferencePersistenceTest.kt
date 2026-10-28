package com.muses.player.core.lxsdk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muses.player.core.data.repository.DataStoreSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class LxSourcePreferencePersistenceTest {
    @Test fun `首选音源持久保存且切回自动选择`() = runBlocking {
        val root = Files.createTempDirectory("muses-source-preference").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
            val settings = DataStoreSettingsRepository(dataStore)
            assertEquals("", settings.preferredLxSource.first())
            settings.setPreferredLxSource("user-source")
            assertEquals("user-source", DataStoreSettingsRepository(dataStore).preferredLxSource.first())
            settings.setPreferredLxSource(LxSourcePreference.BUILTIN)
            assertEquals(LxSourcePreference.BUILTIN, settings.preferredLxSource.first())
            settings.setPreferredLxSource("")
            assertEquals("", settings.preferredLxSource.first())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }
}
