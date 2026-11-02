package com.muses.player.feature.sources

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.muses.player.core.ui.components.MusesDialog
import com.muses.player.core.ui.components.MusesSnackbar
import com.muses.player.core.ui.icons.TablerIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import com.muses.player.core.ui.components.MusesTextField
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.muses.player.core.model.Source
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.muses.player.core.ui.components.MusesActionsSheet
import com.muses.player.core.ui.components.MusesActionItem
import com.muses.player.core.ui.components.SharedSourceItem
import com.muses.player.core.ui.components.SourceListItem
import com.muses.player.core.ui.components.MusesEmpty
import com.muses.player.core.ui.components.MusesRefreshableContent
import com.muses.player.core.ui.components.MusesRefreshablePlaceholder
import com.muses.player.core.ui.components.MusesIconButton
import com.muses.player.core.ui.components.MusesIconButtonSize
import com.muses.player.core.ui.components.MusesTopBar
import com.muses.player.core.model.SourceType

// ── 主入口 ──────────────────────────────────────────

@Composable
fun SourcesScreen(
    modifier: Modifier = Modifier,
    /** 从设置页进入时返回上级；顶层入口不传时仅显示标题 */
    onBack: (() -> Unit)? = null,
    /** 跳转 WebDAV 添加表单页（P5：对照 Web 层 /tabs/sources/webdav） */
    onOpenWebdavAdd: () -> Unit = {},
    /** 跳转 WebDAV 编辑表单页（对照 /tabs/sources/webdav/:id） */
    onOpenWebdavEdit: (sourceId: String) -> Unit = {},
    /** 跳转在线音源脚本管理页（洛雪自定义源） */
    onOpenLxScripts: () -> Unit = {},
    /** 跳转 LX 音源新增页（名称 + 脚本来源） */
    onOpenLxAdd: () -> Unit = {},
    /** 跳转 LX 音源编辑页（复用新增表单） */
    onOpenLxEdit: (sourceId: String) -> Unit = {},
    viewModel: SourcesViewModel = koinViewModel(),
) {
    val scheme = MiuixTheme.colorScheme
    val sources by viewModel.sources.collectAsState()
    // 扫描进度弹窗观察 scanner 内部进度流
    val scanProgress by viewModel.scanProgress.collectAsState()
    var sourceActionsTarget by remember { mutableStateOf<Source?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MusesTopBar(
                title = "音源",
                onBack = onBack,
                actions = {
                    MusesIconButton(
                        onClick = { viewModel.openAddActionSheet() },
                        size = MusesIconButtonSize.SM,
                        contentDescription = "添加音源",
                    ) {
                        Icon(TablerIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
            )
        },
    ) { padding ->
        // overlay 层：与原外层 Box 严格对应（对话框/浮层挂载域，层级 1:1）
        Box(Modifier.fillMaxSize()) {
            MusesRefreshableContent(
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(padding),
            ) {
                Column(Modifier.fillMaxSize()) {
                Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clickable(onClick = onOpenLxScripts)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("在线音源管理", fontWeight = FontWeight.Medium)
                        Text("导入 · 优先音源 · 备用顺序 · 内置音源", fontSize = 12.sp, color = scheme.onSurfaceVariantSummary)
                    }
                }
                Box(Modifier.weight(1f)) {
                if (sources.isEmpty()) {
                    MusesRefreshablePlaceholder {
                        MusesEmpty(
                            title = "空空如也~",
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
                        )
                    }
                } else {
                    SourceCardList(
                        sources = sources,
                        modifier = Modifier.fillMaxSize(),
                        onMoreActions = { sourceActionsTarget = it },
                    )
                }
                }
                }
            }
        }
    }

    // ---- 系统目录选择器（添加本地文件夹）：expect/actual 端口 → 物理路径 → 建源 ----
    // （安卓 SAF tree uri 解析 + 授权持久化在 androidMain actual；桌面 Swing 目录选择在 jvmMain actual）
    val pickLocalFolder = rememberLocalFolderPicker { physicalPath ->
        viewModel.saveLocalSource(physicalPath)
    }

    // ---- m-actions：添加音源 ----
    if (viewModel.isAddActionSheetOpen) {
        MusesActionsSheet(
            opened = true,
            onDismiss = { viewModel.closeAddActionSheet() },
            label = "添加音源",
            items = listOf(
                MusesActionItem(label = "本地音源", onClick = {
                    viewModel.closeAddActionSheet()
                    // 系统目录选择器：选完回调内建源，对齐 Web FilePicker.pickDirectory 语义
                    pickLocalFolder()
                }),
                MusesActionItem(label = "WebDav 音源", onClick = {
                    viewModel.closeAddActionSheet()
                    onOpenWebdavAdd()
                }),
                MusesActionItem(label = "LX 音源", onClick = {
                    viewModel.closeAddActionSheet()
                    onOpenLxAdd()
                }),
            ),
        )
    }

    sourceActionsTarget?.let { source ->
        MusesActionsSheet(
            opened = true,
            onDismiss = { sourceActionsTarget = null },
            label = source.name,
            items = buildList {
                add(MusesActionItem(label = "编辑") {
                    sourceActionsTarget = null
                    if (source.type == SourceType.WEBDAV) {
                        onOpenWebdavEdit(source.id)
                    } else if (source.type == SourceType.ONLINE) {
                        onOpenLxEdit(source.id)
                    } else {
                        viewModel.openEditForm(source)
                    }
                })
                add(MusesActionItem(label = "删除", destructive = true) {
                    sourceActionsTarget = null
                    viewModel.confirmDelete(source)
                })
                if (source.type != SourceType.ONLINE) {
                    add(MusesActionItem(label = "扫描") {
                        sourceActionsTarget = null
                        viewModel.startScan(source)
                    })
                }
            },
        )
    }

    // ---- 删除确认（miuix MusesDialog 主次操作左右排列）----
    viewModel.pendingDelete?.let { source ->
        MusesDialog(
            onDismiss = { viewModel.dismissDelete() },
            title = "删除",
            message = "确定删除「${source.name}」吗？",
            confirmText = "确定",
            onConfirm = {
                viewModel.deleteSource(source) {
                    MusesSnackbar.show("删除成功")
                }
                viewModel.dismissDelete()
            },
            dismissText = "取消",
        )
    }

    // ---- 编辑音源表单（m-dialog：显示名称 / 目录；miuix MusesDialog + MusesTextField）----
    viewModel.pendingEdit?.let { source ->
        var editName by remember(source.id) { mutableStateOf(source.name) }
        var editPath by remember(source.id) { mutableStateOf(source.path.orEmpty()) }
        MusesDialog(
            onDismiss = { viewModel.dismissEdit() },
            title = "编辑音源",
            confirmText = "保存修改",
            onConfirm = {
                viewModel.updateEditedSource(source, editName.trim(), editPath.trim())
                viewModel.dismissEdit()
            },
            confirmEnabled = editName.isNotBlank() && editPath.isNotBlank(),
            dismissText = "取消",
            content = {
                MusesTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                    label = "显示名称",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                MusesTextField(
                    value = editPath,
                    onValueChange = { editPath = it },
                    singleLine = true,
                    label = "目录",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    // ---- m-dialog：扫描进度 ----
    if (viewModel.isScanProgressOpen) {
        val scanError = viewModel.scanError
        MusesDialog(
            // 扫描进行中禁止关闭，完成后可点弹窗外或返回键收起。
            onDismiss = { viewModel.dismissScanProgress() },
            title = "扫描",
            content = {
                when {
                    scanError != null -> {
                        Text("扫描失败", style = MiuixTheme.textStyles.main, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                        Spacer(Modifier.height(8.dp))
                        Text(scanError, style = MiuixTheme.textStyles.footnote1, color = scheme.error)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ScanResultStat("成功", "0", scheme.primary, Modifier.weight(1f))
                            ScanResultStat("失败", "1", scheme.error, Modifier.weight(1f))
                            ScanResultStat("跳过", "0", scheme.onBackgroundVariant, Modifier.weight(1f))
                        }
                    }
                    !scanProgress.finished -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = if (scanProgress.total > 0) {
                                    scanProgress.current.toFloat() / scanProgress.total
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (scanProgress.total > 0) {
                                Text(
                                    "已处理 ${scanProgress.current} / ${scanProgress.total}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onBackgroundVariant,
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                    }
                    else -> {
                        val result = viewModel.scanMergeResult
                        if (result != null) {
                            val successCount = if (result.skipped) 0 else result.scanned
                            val skippedCount = if (result.skipped) result.scanned else 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ScanResultStat("成功", successCount.toString(), scheme.primary, Modifier.weight(1f))
                                ScanResultStat("失败", "0", scheme.error, Modifier.weight(1f))
                                ScanResultStat("跳过", skippedCount.toString(), scheme.onBackgroundVariant, Modifier.weight(1f))
                            }
                            if (result.skipped || result.missing > 0) {
                                Spacer(Modifier.height(8.dp))
                                viewModel.scanResultMessage?.let {
                                    Text(it, style = MiuixTheme.textStyles.footnote1, color = scheme.onBackgroundVariant)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ScanResultStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = color)
    }
}

// ── 音源卡片列表（.sources-page__list / __card）──────────────

/**
 * 紧凑展示名称、音源类型和位置；编辑、删除、扫描收进 Miuix 操作单。
 */
@Composable
private fun SourceCardList(
    sources: List<Source>,
    modifier: Modifier = Modifier,
    onMoreActions: (Source) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            // 末项避让底部悬浮件（悬浮件高度见 BottomChrome）
            bottom = 16.dp + com.muses.player.core.ui.theme.LocalBottomChromePadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sources, key = { it.id }) { source ->
            SourceListItem(
                item = source.toSharedSourceItem(),
                onMoreActions = { onMoreActions(source) },
            )
        }
    }
}

/** 安卓 Source → 共用 SharedSourceItem 映射（subtitle/detail 文案与原卡片一致） */
private fun Source.toSharedSourceItem() = SharedSourceItem(
    id = id,
    name = name,
    sourceType = when (type) {
        SourceType.LOCAL -> "本地"
        SourceType.WEBDAV -> "WebDav"
        SourceType.ONLINE -> "LX"
    },
)

