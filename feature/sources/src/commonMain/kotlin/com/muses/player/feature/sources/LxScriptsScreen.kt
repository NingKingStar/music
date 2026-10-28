package com.muses.player.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.MusesBottomSheet
import com.muses.player.core.ui.components.MusesButton
import com.muses.player.core.ui.components.MusesDialog
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesPullToRefresh
import com.muses.player.core.ui.components.MusesRefreshablePlaceholder
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesTextField
import com.muses.player.core.ui.components.MusesSnackbar
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.ui.icons.TablerIcons
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import com.muses.player.core.ui.components.MarqueeText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 洛雪自定义音源脚本管理页。
 *
 * 交互：
 * - 列表：脚本名/版本/作者 + 声明的源标签 + 加载错误提示 + 启用开关；
 * - 导入：粘贴脚本内容 → **立即预检**（能否初始化、能提供哪些源）→ 确认导入；
 * - 删除：二次确认。
 *
 * 支持管理应用内置免费音源，以及用户自行导入的音源脚本。
 */
@Composable
fun LxScriptsScreen(
    onBack: () -> Unit,
    viewModel: LxScriptsViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val pendingSource by viewModel.pendingSource.collectAsState()
    val validation by viewModel.importValidation.collectAsState()
    val urlImportStatus by viewModel.urlImportStatus.collectAsState()

    var showImportSheet by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var scriptUrl by remember { mutableStateOf("") }
    var fetchingUrl by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var actionSheetFor by remember { mutableStateOf<LxScriptItem?>(null) }
    val filePicker = rememberLxScriptFilePicker { result ->
        result.fold(onSuccess = { viewModel.stageImport(it); showImportSheet = true }, onFailure = {
            MusesSnackbar.show(it.message ?: "读取脚本失败，请确认文件已下载到本地且可读取。")
        })
    }
    val importUrl = rememberLxScriptUrlImporter { result ->
        fetchingUrl = false
        result.fold(onSuccess = { viewModel.importFromUrlContent(it, sourceUrl = scriptUrl) }, onFailure = {
            showUrlDialog = false
            MusesSnackbar.show(it.message ?: "下载脚本失败")
        })
    }
    LaunchedEffect(urlImportStatus) {
        when (val status = urlImportStatus) {
            LxUrlImportStatus.Success -> {
                showUrlDialog = false
                MusesSnackbar.show("添加成功")
            }
            is LxUrlImportStatus.Failure -> { showUrlDialog = false; MusesSnackbar.show(status.message) }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = "在线音源脚本",
                onBack = onBack,
                actions = {
                    MusesIconButton(
                        onClick = { showImportOptions = true },
                        imageVector = TablerIcons.Add,
                        contentDescription = "导入脚本",
                        size = MusesIconButtonSize.MD,
                    )
                },
            )
        },
    ) { padding ->
        MusesPullToRefresh(
            isRefreshing = loading,
            onRefresh = { if (!loading) viewModel.refresh() },
            modifier = Modifier.padding(padding),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (loading && items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty()) {
                MusesRefreshablePlaceholder {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MusesEmpty(
                        title = "空空如也~",
                        bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
                    )
                }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = "共 ${items.size} 个脚本 · ${items.count { it.enabled }} 个已启用",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(items, key = { it.id }) { item ->
                        LxScriptCard(
                            item = item,
                            onToggle = { enabled -> viewModel.setEnabled(item.id, enabled) },
                            onMore = { actionSheetFor = item },
                        )
                    }
                }
            }
        }
        }
    }

    if (showImportOptions) {
        MusesActionsSheet(
            opened = true,
            onDismiss = { showImportOptions = false },
            label = "添加音源脚本",
            items = listOf(
                MusesActionItem(label = "通过文件添加", onClick = { showImportOptions = false; filePicker() }),
                MusesActionItem(label = "通过 URL 添加", onClick = { showImportOptions = false; scriptUrl = ""; fetchingUrl = false; urlError = null; viewModel.resetUrlImportStatus(); showUrlDialog = true }),
            ),
        )
    }

    if (showUrlDialog) {
        MusesDialog(
            onDismiss = { showUrlDialog = false },
            title = "通过 URL 添加",
            confirmText = "添加",
            confirmEnabled = !fetchingUrl && urlImportStatus !is LxUrlImportStatus.Importing,
            confirmLoading = fetchingUrl || urlImportStatus is LxUrlImportStatus.Importing,
            onConfirm = { urlError = null; fetchingUrl = true; importUrl(scriptUrl.trim()) },
            content = {
                Column {
                    MusesTextField(value = scriptUrl, onValueChange = { scriptUrl = it }, modifier = Modifier.fillMaxWidth(), label = "URL")
                    urlError?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
        )
    }

    // 导入底部弹窗（含预检反馈）
    if (showImportSheet) {
        LxScriptImportSheet(
            pendingSource = pendingSource,
            validation = validation,
            onSourceChange = { viewModel.stageImport(it) },
            onDismiss = {
                showImportSheet = false
                viewModel.clearStagedImport()
            },
            onConfirm = {
                viewModel.confirmImport()
                showImportSheet = false
            },
        )
    }

    // 单项操作
    actionSheetFor?.let { item ->
        MusesActionsSheet(
            opened = true,
            onDismiss = { actionSheetFor = null },
            label = item.name,
            items = listOf(
                MusesActionItem(
                    label = if (item.enabled) "禁用" else "启用",
                    onClick = {
                        viewModel.setEnabled(item.id, !item.enabled)
                        actionSheetFor = null
                    },
                ),
                MusesActionItem(
                    label = "删除脚本",
                    destructive = true,
                    onClick = {
                        pendingDeleteId = item.id
                        actionSheetFor = null
                    },
                ),
            ),
        )
    }

    pendingDeleteId?.let { id ->
        val name = items.firstOrNull { it.id == id }?.name ?: "该脚本"
        MusesDialog(
            onDismiss = { pendingDeleteId = null },
            title = "删除脚本",
            message = "确定删除「$name」吗？删除后使用该脚本的在线音源将无法播放。",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                viewModel.delete(id)
                pendingDeleteId = null
            },
        )
    }
}

/** 单个脚本卡片 */
@Composable
private fun LxScriptCard(
    item: LxScriptItem,
    onToggle: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        item.version?.let { "v$it" },
                        item.author,
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(checked = item.enabled, onCheckedChange = onToggle)
                MusesIconButton(
                    onClick = onMore,
                    imageVector = TablerIcons.MoreHorizontal,
                    contentDescription = "更多操作",
                    size = MusesIconButtonSize.SM,
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 源标签
            if (item.platforms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "提供音源：" + item.platforms.joinToString("、") { platformLabel(it) },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            // 加载错误
            item.loadError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = TablerIcons.Warning,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!item.enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已禁用",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 导入脚本弹窗：粘贴 → 预检 → 确认 */
@Composable
internal fun LxScriptImportSheet(
    pendingSource: String?,
    validation: LxImportValidation,
    onSourceChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var text by remember { mutableStateOf(pendingSource.orEmpty()) }

    MusesBottomSheet(onDismiss = onDismiss, title = "导入音源脚本") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "支持导入 .js、.txt 音源脚本，也可粘贴脚本正文。文件后缀不影响校验结果。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            MusesTextField(
                value = text,
                onValueChange = {
                    text = it
                    onSourceChange(it)
                },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                label = "脚本内容",
                minLines = 8,
            )

            Spacer(Modifier.height(12.dp))
            when (validation) {
                is LxImportValidation.Idle -> Unit
                is LxImportValidation.Validating -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("正在校验脚本…", fontSize = 13.sp)
                }
                is LxImportValidation.Valid -> {
                    Text(
                        text = "✓ 校验通过：${validation.scriptName ?: "未命名脚本"}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        text = "可提供音源：" + validation.platforms.joinToString("、") { platformLabel(it) },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                is LxImportValidation.Invalid -> {
                    Text(
                        text = "✗ 校验失败：${validation.message}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MusesButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                MusesButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = validation is LxImportValidation.Valid,
                ) {
                    Text("导入")
                }
            }
        }
    }
}

/** 源 key → 中文展示名（对齐洛雪平台命名） */
internal fun platformLabel(key: String, displayName: String? = null): String = when (key) {
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "tx" -> "QQ 音乐"
    "wy" -> "网易云"
    "mg" -> "咪咕"
    "local" -> "本地"
    else -> displayName?.takeIf { it.isNotBlank() } ?: key
}
