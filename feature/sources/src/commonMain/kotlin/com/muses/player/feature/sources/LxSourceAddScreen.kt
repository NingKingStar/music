package com.muses.player.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muses.player.core.ui.components.MusesSnackbar
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.components.SourceFormInput
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 添加或编辑 LX 在线音源：设置显示名称，通过 URL 或本地文件导入/替换脚本。 */
@Composable
fun LxSourceFormScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    sourceId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: LxScriptsViewModel = koinViewModel(),
) {
    val pendingSource by viewModel.pendingSource.collectAsState()
    val validation by viewModel.importValidation.collectAsState()
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(sourceId != null) }
    var url by remember(sourceId) { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var isInitializing by remember(sourceId) { mutableStateOf(sourceId != null) }

    LaunchedEffect(sourceId) {
        viewModel.clearStagedImport()
        if (sourceId != null) {
            viewModel.loadSourceForEdit(sourceId) { result ->
                isInitializing = false
                result.fold(
                    onSuccess = { editData ->
                        name = editData.source.name
                        url = editData.sourceUrl.orEmpty()
                    },
                    onFailure = {
                        MusesSnackbar.show(it.message ?: "读取 LX 音源失败。")
                        onBack()
                    },
                )
            }
        }
    }

    val urlImporter = rememberLxScriptUrlImporter { result ->
        result.fold(
            onSuccess = { content ->
                viewModel.stageImport(content)
            },
            onFailure = {
                isAdding = false
                urlError = it.message ?: "下载脚本失败。"
            },
        )
    }
    val filePicker = rememberLxScriptFilePicker { result ->
        result.fold(onSuccess = { content ->
            url = ""
            urlError = null
            viewModel.stageImport(content)
        }, onFailure = {
            MusesSnackbar.show(it.message ?: "读取脚本失败，请确认文件已下载到本地且可读取。")
        })
    }

    val suggestedName = (validation as? LxImportValidation.Valid)?.scriptName
    LaunchedEffect(suggestedName) {
        if (!nameEdited && !suggestedName.isNullOrBlank()) name = suggestedName
    }
    val onSaveComplete: (Result<Unit>) -> Unit = { result ->
        isAdding = false
        result.fold(
            onSuccess = {
                MusesSnackbar.show("保存成功")
                onSaved()
            },
            onFailure = { MusesSnackbar.show(it.message ?: "保存 LX 音源失败。") },
        )
    }
    LaunchedEffect(validation, pendingSource, isAdding) {
        if (!isAdding || pendingSource == null) return@LaunchedEffect
        when (val result = validation) {
            is LxImportValidation.Valid -> {
                if (sourceId == null) {
                    viewModel.confirmImport(name, sourceUrl = url.trim(), onComplete = onSaveComplete)
                } else {
                    viewModel.confirmEdit(
                        id = sourceId,
                        displayName = name,
                        replacementScript = pendingSource,
                        replacementUrl = url.trim(),
                        onComplete = onSaveComplete,
                    )
                }
            }
            is LxImportValidation.Invalid -> {
                isAdding = false
                urlError = "脚本校验失败：${result.message}"
            }
            else -> Unit
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = if (sourceId == null) "添加 LX 音源" else "编辑 LX 音源",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(top = 8.dp)
                .padding(bottom = com.muses.player.core.ui.theme.LocalBottomChromePadding.current),
        ) {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SourceFormInput(
                    label = "名称",
                    value = name,
                    readOnly = isAdding || isInitializing,
                    onValueChange = {
                        name = it
                        nameEdited = true
                    },
                )
                SourceFormInput(
                    label = "脚本 URL",
                    value = url,
                    error = urlError,
                    readOnly = isAdding || isInitializing,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    onValueChange = {
                        url = it
                        urlError = null
                        viewModel.clearStagedImport()
                    },
                )
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("填写脚本 URL，或选择本地 .js / .txt 文件。")
                    Button(
                        onClick = {
                            viewModel.clearStagedImport()
                            urlError = null
                            filePicker()
                        },
                        enabled = !isAdding && !isInitializing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("选择脚本文件（.js / .txt）") }
                    if (pendingSource != null) {
                        when (val result = validation) {
                            is LxImportValidation.Validating -> Text("正在校验脚本…")
                            is LxImportValidation.Valid -> Text("校验通过：${result.scriptName ?: "未命名脚本"}")
                            is LxImportValidation.Invalid -> Text("校验失败：${result.message}", color = MiuixTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Button(
                        onClick = {
                            if (!isAdding && !isInitializing) {
                                urlError = if (sourceId == null && url.isBlank() && pendingSource == null) "请填写脚本 URL 或选择脚本文件" else null
                                if (pendingSource != null) {
                                    isAdding = true
                                } else if (sourceId != null && url.isBlank()) {
                                    isAdding = true
                                    viewModel.confirmEdit(sourceId, name, replacementScript = null, onComplete = onSaveComplete)
                                } else if (url.isNotBlank()) {
                                    viewModel.clearStagedImport()
                                    isAdding = true
                                    urlImporter(url.trim())
                                }
                            }
                        },
                        enabled = !isAdding && !isInitializing && (pendingSource == null || validation is LxImportValidation.Valid),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        if (isAdding) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    size = 16.dp,
                                    strokeWidth = 2.dp,
                                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                        foregroundColor = MiuixTheme.colorScheme.onPrimary,
                                        backgroundColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
                                    ),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("保存")
                            }
                        } else {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}
