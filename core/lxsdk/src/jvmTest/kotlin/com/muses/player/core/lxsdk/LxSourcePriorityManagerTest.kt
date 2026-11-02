package com.muses.player.core.lxsdk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muses.player.core.data.repository.DataStoreSettingsRepository
import com.muses.player.core.lxsdk.crypto.LxCryptoJvm
import com.muses.player.core.lxsdk.store.FileLxScriptStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LxSourcePriorityManagerTest {
    @Test fun `备用顺序真实控制解析且同档失败才使用下一个音源`() = runBlocking {
        var order = listOf("two", "one")
        val repository = LxScriptRepository(LxCryptoJvm(), sourceOrderProvider = { order })
        fun script(id: String) = """
            lx.on('request', () => Promise.resolve('https://cdn.test/$id'));
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac','320k']}}});
        """.trimIndent()
        try {
            repository.register("one", script("one")); repository.register("two", script("two"))
            assertEquals("https://cdn.test/two", repository.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC).url)
            val seen = mutableListOf<String>()
            val result = repository.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC, acceptUrl = { seen += it; it.endsWith("one") })
            assertEquals(listOf("https://cdn.test/two", "https://cdn.test/one"), seen)
            assertEquals(LxQuality.FLAC, result.quality)
            val before = repository.preferenceFingerprint()
            order = listOf("one", "two")
            assertNotEquals(before, repository.preferenceFingerprint())
            assertEquals("https://cdn.test/one", repository.resolveMusicUrlInfo("kw", "{}", LxQuality.FLAC).url)
        } finally { repository.close() }
    }
    @Test fun `选择导入音源优先并持久化备用顺序停用后接替且删除后清理`() = runBlocking {
        val root = Files.createTempDirectory("source-priority").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = LxScriptRepository(LxCryptoJvm())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
            val settings = DataStoreSettingsRepository(dataStore)
            val store = FileLxScriptStore(rootDir = File(root, "scripts"))
            for ((index, id) in listOf("one", "two", "three").withIndex()) { store.save(id, "// @name $id"); File(root, "scripts/$id.js").setLastModified((index + 1) * 1_000L) }
            val manager = LxSourcePriorityManager(store, repository, settings)
            manager.choose("one")
            assertEquals("one", settings.preferredLxSource.first())
            assertEquals(listOf("one", "two", "three"), settings.lxSourceOrder.first())
            manager.move("three", -1)
            assertEquals(listOf("one", "three", "two"), DataStoreSettingsRepository(dataStore).lxSourceOrder.first())
            store.setEnabled("one", false)
            assertTrue(manager.repair())
            assertEquals("three", settings.preferredLxSource.first())
            store.delete("three")
            assertTrue(manager.repair())
            assertEquals("two", settings.preferredLxSource.first())
            assertEquals(listOf("one", "two"), settings.lxSourceOrder.first())
            manager.choose("one")
            assertTrue(store.get("one")!!.enabled)
            settings.setBuiltinLxSourcesEnabled(false)
            store.setEnabled("one", false); store.setEnabled("two", false)
            assertTrue(manager.repair())
            assertEquals("", settings.preferredLxSource.first())
        } finally { repository.close(); scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }
}
