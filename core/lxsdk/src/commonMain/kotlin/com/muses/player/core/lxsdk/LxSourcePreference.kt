package com.muses.player.core.lxsdk

import com.muses.player.core.lxsdk.store.LxStoredScript

object LxSourcePreference {
    const val BUILTIN = "@builtin"

    /** 只优先当前启用的脚本，删除或禁用后自然回退到自动选择。 */
    fun preferredIds(choice: String, active: List<LxStoredScript>): Set<String> = active
        .filter { it.enabled && if (choice == BUILTIN) it.isBuiltin else choice.isNotBlank() && it.id == choice }
        .map { it.id }.toSet()
}
