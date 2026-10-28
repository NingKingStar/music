package com.muses.player.settings

import androidx.compose.runtime.*
import com.muses.player.core.data.repository.SettingsRepository
import com.muses.player.core.data.repository.SourceRepository
import com.muses.player.core.lxsdk.LxSourcePreference
import com.muses.player.core.lxsdk.LxScriptRepository
import com.muses.player.core.lxsdk.store.LxScriptStore
import com.muses.player.core.lxsdk.store.LxStoredScript
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 偏好仅影响后续请求，切换设置不会打断当前播放。 */
@Composable
fun PreferredLxSourcePreference() {
    val settings = koinInject<SettingsRepository>()
    val store = koinInject<LxScriptStore>()
    val repository = koinInject<LxScriptRepository>()
    val sources = koinInject<SourceRepository>()
    val sourceNames by sources.observeSources().collectAsState(emptyList())
    val preferred by settings.preferredLxSource.collectAsState("")
    val builtinEnabled by settings.builtinLxSourcesEnabled.collectAsState(true)
    var scripts by remember { mutableStateOf<List<LxStoredScript>>(emptyList()) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(show, sourceNames) {
        scripts = withContext(Dispatchers.IO) { store.list() }
    }
    fun name(script: LxStoredScript) = sourceNames.firstOrNull { it.id == script.id }?.name ?: script.name
    val selected = scripts.firstOrNull { it.id == preferred && it.enabled }
    val label = when (preferred) {
        "" -> "自动选择"
        LxSourcePreference.BUILTIN -> if (builtinEnabled) "内置音源优先" else "内置音源已关闭，自动回退"
        else -> selected?.let { name(it) } ?: "所选音源已停用或删除，自动回退"
    }
    ArrowPreference(
        title = "优先使用的音源",
        summary = "$label · 不可用时自动切换",
        onClick = { if (!busy) show = true },
    )
    fun choose(id: String) {
        if (busy) return
        busy = true
        show = false
        scope.launch {
            try {
                if (id == LxSourcePreference.BUILTIN && !builtinEnabled) settings.setBuiltinLxSourcesEnabled(true)
                settings.setPreferredLxSource(id)
                repository.refreshFromStore()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { MusesSnackbar.show("优先音源保存失败，请重试")
            } finally { busy = false }
        }
    }
    if (show) {
        val options = listOf(
            "" to "自动选择",
            LxSourcePreference.BUILTIN to "内置音源优先",
        ) + scripts.filter { it.enabled && !it.isBuiltin }.map { it.id to name(it) }
        MusesActionsSheet(
            opened = true,
            onDismiss = { show = false },
            label = "优先使用的音源",
            items = options.map { (id, title) ->
                MusesActionItem(title + if (id == preferred) "（当前）" else "") { choose(id) }
            },
        )
    }
}
