package com.muses.player.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muses.player.core.data.repository.SourceRepository
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.model.Source
import com.muses.player.core.model.SourceType
import com.muses.player.core.lxsdk.LxScriptMetaParser
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.store.LxScriptStore
import com.muses.player.core.lxsdk.store.LxStoredScript
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import com.muses.player.core.lxsdk.LxSourcePriorityManager

/** 脚本列表项（UI 展示用；合并持久化状态与运行态） */
data class LxScriptItem(
    val id: String,
    val name: String,
    val version: String?,
    val author: String?,
    val description: String?,
    val enabled: Boolean,
    /** 脚本运行态声明的源 key（kw/kg/tx/...）；未加载时为空 */
    val platforms: List<String>,
    /** 加载/初始化错误；null = 正常 */
    val loadError: String?,
    /** 声明支持的 action 数（粗略反映能力） */
    val supportsLyric: Boolean,
    val supportsPic: Boolean,
)

/** 编辑表单所需的音源与其导入地址。 */
data class LxSourceEditData(val source: Source, val sourceUrl: String?)

/** 导入校验结果 */
sealed interface LxUrlImportStatus {
    data object Idle : LxUrlImportStatus
    data object Importing : LxUrlImportStatus
    data object Success : LxUrlImportStatus
    data class Failure(val message: String) : LxUrlImportStatus
}

sealed interface LxImportValidation {
    data object Idle : LxImportValidation
    data object Validating : LxImportValidation

    /** 校验通过：列出可提供的源 */
    data class Valid(val scriptName: String?, val platforms: List<String>) : LxImportValidation

    /** 校验失败：脚本无法初始化 */
    data class Invalid(val message: String) : LxImportValidation
}

/**
 * 洛雪自定义音源脚本管理 ViewModel。
 *
 * 职责：脚本的导入（含**导入前预检**）、启用/禁用、删除，以及运行态回显
 * （能提供哪些源、是否加载失败）。
 *
 * 导入预检的意义：用户在粘贴/选择脚本时立即知道「这脚本能不能跑、能提供哪些源」，
 * 避免导入后才发现不可用。
 */
@OptIn(ExperimentalUuidApi::class)
class LxScriptsViewModel(
    private val store: LxScriptStore,
    private val repository: LxScriptRepository,
    private val sourceRepository: SourceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val priority = LxSourcePriorityManager(store, repository, settingsRepository)
    val preferredSource = settingsRepository.preferredLxSource
    val sourceOrder = settingsRepository.lxSourceOrder
    val builtinEnabled = settingsRepository.builtinLxSourcesEnabled
    val notice = MutableStateFlow<String?>(null)
    fun clearNotice() { notice.value = null }
    fun choosePreferred(id: String) = changePriority { priority.choose(id); notice.value = "已更新优先音源" }
    fun moveSource(id: String, delta: Int) = changePriority { priority.move(id, delta) }
    fun setBuiltinEnabled(enabled: Boolean) = changePriority {
        settingsRepository.setBuiltinLxSourcesEnabled(enabled)
        repository.refreshFromStore()
        if (priority.repair()) notice.value = "优先音源已切换为可用备用音源"
    }
    private fun changePriority(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice.value = e.message ?: "音源设置保存失败，请重试" }
        }
    }

    private val _items = MutableStateFlow<List<LxScriptItem>>(emptyList())
    val items: StateFlow<List<LxScriptItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _importValidation = MutableStateFlow<LxImportValidation>(LxImportValidation.Idle)
    val importValidation: StateFlow<LxImportValidation> = _importValidation.asStateFlow()

    /** 待导入的脚本文本（从文件选择器或剪贴板来） */
    private val _pendingSource = MutableStateFlow<String?>(null)
    val pendingSource: StateFlow<String?> = _pendingSource.asStateFlow()

    private val _urlImportStatus = MutableStateFlow<LxUrlImportStatus>(LxUrlImportStatus.Idle)
    val urlImportStatus: StateFlow<LxUrlImportStatus> = _urlImportStatus.asStateFlow()

    fun resetUrlImportStatus() {
        _urlImportStatus.value = LxUrlImportStatus.Idle
    }

    fun importFromUrlContent(source: String, sourceUrl: String) {
        viewModelScope.launch {
            _urlImportStatus.value = LxUrlImportStatus.Importing
            try {
                require(source.isNotBlank()) { "脚本内容为空。" }
                val descriptor = repository.validate(source)
                require(descriptor.sources.isNotEmpty()) { "脚本未声明任何音源，无法导入。" }
                val id = generateScriptId(source)
                store.save(id = id, source = source, enabled = true, sourceUrl = sourceUrl.trim())
                repository.register(id, source)
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                sourceRepository.upsert(
                    Source(
                        id = id,
                        name = descriptor.meta.name?.takeIf { it.isNotBlank() } ?: id,
                        type = SourceType.ONLINE,
                        path = id,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                refresh()
                priority.choose(id)
                notice.value = "已添加并设为优先音源"
                _urlImportStatus.value = LxUrlImportStatus.Success
            } catch (e: Exception) {
                _urlImportStatus.value = LxUrlImportStatus.Failure(e.message ?: "脚本校验失败。")
            }
        }
    }

    init {
        refresh()
    }

    /**
     * 刷新列表：重读磁盘 + 触发脚本加载（以回显各脚本能提供的源）。
     *
     * 加载是必要的：未加载的脚本无法得知其声明的源，UI 只能显示空。
     * 脚本数量级小（个位数），加载开销可接受。
     */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val stored = store.list()
                // LX 脚本同时作为 ONLINE 音源显示在音源列表中。
                stored.filterNot { it.isBuiltin }.forEach { script ->
                    if (sourceRepository.getSource(script.id) == null) {
                        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        sourceRepository.upsert(
                            Source(
                                id = script.id,
                                name = script.meta.name?.takeIf { it.isNotBlank() } ?: script.name,
                                type = SourceType.ONLINE,
                                path = script.id,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                // 同步运行态：把磁盘状态与仓库对齐（新增/删除/启禁用）
                syncRepository(stored)
                // 触发加载以回显源声明与错误
                repository.loadAll()
                _items.value = buildItems(stored.filterNot { it.isBuiltin })
            } finally {
                _loading.value = false
            }
        }
    }

    /** 设置待导入脚本（来自文件选择器/粘贴），并立即预检 */
    fun stageImport(source: String) {
        _pendingSource.value = source
        validate(source)
    }

    fun clearStagedImport() {
        _pendingSource.value = null
        _importValidation.value = LxImportValidation.Idle
    }

    /** 读取编辑表单所需的现有在线音源。 */
    fun loadSourceForEdit(id: String, onComplete: (Result<LxSourceEditData>) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val stored = requireNotNull(store.get(id)) { "找不到要编辑的 LX 音源。" }
                val source = requireNotNull(sourceRepository.getSource(id)) { "找不到要编辑的音源。" }
                LxSourceEditData(source, stored.sourceUrl)
            }.also(onComplete)
        }
    }

    /** 预检脚本：能否初始化、能提供哪些源 */
    fun validate(source: String) {
        if (source.isBlank()) {
            _importValidation.value = LxImportValidation.Invalid("脚本内容为空。")
            return
        }
        viewModelScope.launch {
            _importValidation.value = LxImportValidation.Validating
            _importValidation.value = try {
                val descriptor = repository.validate(source)
                if (descriptor.sources.isEmpty()) {
                    LxImportValidation.Invalid("脚本可初始化，但未声明任何音源（无法使用）。")
                } else {
                    LxImportValidation.Valid(
                        scriptName = descriptor.meta.name,
                        platforms = descriptor.sources.keys.toList(),
                    )
                }
            } catch (e: Exception) {
                LxImportValidation.Invalid(e.message ?: "脚本初始化失败。")
            }
        }
    }

    /**
     * 确认导入：落盘 + 注册到仓库 + 刷新列表。
     *
     * id 生成：优先用元信息名称派生（可读、便于用户识别），同名时追加序号。
     */
    fun confirmImport(
        displayName: String? = null,
        sourceUrl: String? = null,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        val source = _pendingSource.value ?: return
        viewModelScope.launch {
            try {
                val id = generateScriptId(source)
                store.save(id = id, source = source, enabled = true, sourceUrl = sourceUrl)
                repository.register(id, source)
                val meta = LxScriptMetaParser.parse(source)
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                sourceRepository.upsert(
                    Source(
                        id = id,
                        name = displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: meta.name?.takeIf { it.isNotBlank() }
                            ?: id,
                        type = SourceType.ONLINE,
                        path = id,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                clearStagedImport()
                refresh()
                priority.choose(id)
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            }
        }
    }

    /** 更新已有 LX 音源；替换脚本是可选的，未提供时只更新显示名称。 */
    fun confirmEdit(
        id: String,
        displayName: String?,
        replacementScript: String? = _pendingSource.value,
        replacementUrl: String? = null,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val stored = requireNotNull(store.get(id)) { "找不到要编辑的 LX 音源。" }
                val currentSource = requireNotNull(sourceRepository.getSource(id)) { "找不到要编辑的音源。" }
                val script = replacementScript?.takeIf { it.isNotBlank() } ?: stored.source
                if (replacementScript != null) {
                    require(repository.validate(script).sources.isNotEmpty()) { "脚本未声明任何音源，无法保存。" }
                    store.save(id = id, source = script, enabled = stored.enabled, sourceUrl = replacementUrl)
                    if (stored.enabled && (!stored.isBuiltin || settingsRepository.builtinLxSourcesEnabled.first())) {
                        repository.register(id, script)
                    } else repository.unregister(id)
                }
                val metaName = LxScriptMetaParser.parse(script).name
                sourceRepository.upsert(
                    currentSource.copy(
                        name = displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: metaName?.takeIf { it.isNotBlank() }
                            ?: stored.name,
                        updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                    ),
                )
                clearStagedImport()
                refresh()
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            if (store.setEnabled(id, enabled)) {
                if (enabled) {
                    store.get(id)?.let {
                        if (!it.isBuiltin || settingsRepository.builtinLxSourcesEnabled.first()) repository.register(it.id, it.source)
                    }
                } else {
                    repository.unregister(id)
                }
                if (priority.repair()) notice.value = "优先音源已切换为可用备用音源"
                refresh()
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            repository.unregister(id)
            sourceRepository.deleteById(id)
            if (priority.repair()) notice.value = "优先音源已切换为可用备用音源"
            refresh()
        }
    }

    // ── 内部 ──

    /** 把磁盘状态同步到运行态仓库：新增未注册的、移除已删除的、停用已禁用的 */
    private suspend fun syncRepository(stored: List<LxStoredScript>) {
        val builtinEnabled = settingsRepository.builtinLxSourcesEnabled.first()
        val active = stored.filter { it.enabled && (builtinEnabled || !it.isBuiltin) }
        val registered = repository.scripts().associateBy { it.scriptId }
        val enabledIds = active.map { it.id }.toSet()

        // 新增/更新：磁盘有但仓库无（或来源已变）
        active.forEach { script ->
            val existing = registered[script.id]
            if (existing == null || existing.source != script.source) {
                repository.register(script.id, script.source)
            }
        }
        // 移除：仓库有但磁盘不再启用
        registered.keys.filter { it !in enabledIds }.forEach { repository.unregister(it) }
    }

    private suspend fun buildItems(stored: List<LxStoredScript>): List<LxScriptItem> {
        val loaded = repository.loadAll().associateBy { it.scriptId }
        return stored.map { script ->
            val runtime = loaded[script.id]
            val declarations = runtime?.sources.orEmpty()
            LxScriptItem(
                id = script.id,
                name = script.meta.name ?: script.name,
                version = script.meta.version,
                author = script.meta.author,
                description = script.meta.description,
                enabled = script.enabled,
                platforms = declarations.keys.toList(),
                loadError = if (script.enabled) runtime?.loadError else null,
                supportsLyric = declarations.values.any { "lyric" in it.actions },
                supportsPic = declarations.values.any { "pic" in it.actions },
            )
        }
    }

    /** 由元信息名称生成稳定 id（同 id 覆盖 = 更新同名脚本） */
    private fun generateScriptId(source: String): String {
        val name = com.muses.player.core.lxsdk.LxScriptMetaParser.parse(source).name
        val base = (name ?: "script")
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .take(48)
            .ifBlank { "script" }
        return base
    }
}
