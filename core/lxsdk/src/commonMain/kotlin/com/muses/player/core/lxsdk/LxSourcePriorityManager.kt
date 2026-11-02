package com.muses.player.core.lxsdk

import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.lxsdk.store.LxScriptStore
import kotlinx.coroutines.flow.first

/** 导入、管理页和删除入口共用同一持久化规则，不改变正在播放的直链。 */
class LxSourcePriorityManager(private val store: LxScriptStore, private val repository: LxScriptRepository, private val settings: SettingsRepository) {
    suspend fun choose(id: String) {
        if (id == LxSourcePreference.BUILTIN) settings.setBuiltinLxSourcesEnabled(true)
        else if (id.isNotBlank()) {
            val script = requireNotNull(store.get(id)) { "音源已不存在，请刷新后重试" }
            require(!script.isBuiltin) { "请通过内置音源组选取" }
            store.setEnabled(id, true)
        }
        val all = store.list().filterNot { it.isBuiltin }.map { it.id }
        val order = (settings.lxSourceOrder.first().filter { it in all } + all).distinct()
        settings.setLxSourcePriority(id, order)
        repository.refreshFromStore()
    }

    suspend fun repair(): Boolean {
        val scripts = store.list().filterNot { it.isBuiltin }
        val order = (settings.lxSourceOrder.first().filter { id -> scripts.any { it.id == id } } + scripts.map { it.id }).distinct()
        val preferred = settings.preferredLxSource.first()
        val builtin = settings.builtinLxSourcesEnabled.first()
        val valid = preferred.isBlank() || (preferred == LxSourcePreference.BUILTIN && builtin) || scripts.any { it.id == preferred && it.enabled }
        val next = if (valid) preferred else order.firstOrNull { id -> scripts.any { it.id == id && it.enabled } }
            ?: if (builtin) LxSourcePreference.BUILTIN else ""
        settings.setLxSourcePriority(next, order)
        return next != preferred
    }

    suspend fun move(id: String, delta: Int) {
        val scripts = store.list().filterNot { it.isBuiltin }.map { it.id }
        val order = (settings.lxSourceOrder.first().filter { it in scripts } + scripts).distinct().toMutableList()
        val index = order.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in order.indices) return
        order[index] = order[target].also { order[target] = id }
        settings.setLxSourceOrder(order)
    }
}
