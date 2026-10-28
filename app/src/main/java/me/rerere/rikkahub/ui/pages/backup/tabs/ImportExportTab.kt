package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.backup.WorkspaceBackupItem
import me.rerere.rikkahub.data.backup.WorkspaceBackupManifest
import me.rerere.rikkahub.data.backup.WorkspaceRestoreMode
import me.rerere.rikkahub.data.backup.WorkspaceRestoreSelection
import me.rerere.rikkahub.data.backup.WorkspaceSize
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.extensions.workspace.InstallRootfsDialog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.rikkahub.data.backup.BackupPhase
import me.rerere.rikkahub.data.backup.BackupTaskKind
import me.rerere.rikkahub.data.backup.BackupTaskState
import me.rerere.rikkahub.ui.pages.backup.BackupTarget
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.ui.toast.ToastType

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    val selectedWorkspaceItems by vm.workspaceBackupItems.collectAsStateWithLifecycle()
    val selectedRoots by vm.selectedWorkspaceRoots.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val job by vm.backupJob.collectAsStateWithLifecycle()
    val running = job as? BackupTaskState.Running
    val isChatExporting = running?.kind == BackupTaskKind.CHAT_EXPORT
    val isChatImporting = running?.kind == BackupTaskKind.CHAT_IMPORT ||
        running?.kind == BackupTaskKind.CHATBOX_IMPORT ||
        running?.kind == BackupTaskKind.CHERRY_IMPORT
    val isWorkspaceExporting = running?.kind == BackupTaskKind.WORKSPACE_EXPORT
    val isWorkspaceImporting = running?.kind == BackupTaskKind.WORKSPACE_IMPORT ||
        running?.kind == BackupTaskKind.WORKSPACE_RESTORE
    val pendingRestore by vm.pendingWorkspaceRestore.collectAsStateWithLifecycle()
    val sizes by vm.workspaceSizesState.collectAsStateWithLifecycle()
    val sizesReady by vm.workspaceSizesReady.collectAsStateWithLifecycle()
    val rootfsGaps by vm.rootfsGaps.collectAsStateWithLifecycle()
    val rootfsInstallProgress by vm.rootfsInstallProgress.collectAsStateWithLifecycle()
    val rootfsInstallError by vm.rootfsInstallError.collectAsStateWithLifecycle()
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf(BackupTarget.CHAT) }
    // 恢复后缺 Linux 环境时，在这里挂起待安装的工作区
    var installRootfsTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    // 工作区包是否 gzip 压缩：压缩省空间，但 CPU 长时间满载
    var compressWorkspace by remember { mutableStateOf(false) }

    // 导入类型：local 为聊天记录包，workspace 为工作区包，chatbox 与 cherry 为外部导入
    var importType by remember { mutableStateOf("local") }

    val isRunning = job is BackupTaskState.Running
    val localWorkspaces = workspaces.filter { !it.isSsh() }
    val effectiveRoots = if (selectedRoots.isEmpty()) {
        localWorkspaces.map { it.root }.toSet()
    } else {
        selectedRoots
    }
    val pickerLabel = stringResource(
        R.string.backup_page_workspace_selected,
        effectiveRoots.size,
        localWorkspaces.size,
    )
    val wantsFiles = WorkspaceBackupItem.FILES in selectedWorkspaceItems
    val wantsLinux = WorkspaceBackupItem.LINUX in selectedWorkspaceItems
    val filesBytes = effectiveRoots.sumOf { sizes[it]?.filesBytes ?: 0L }
    val linuxBytes = effectiveRoots.sumOf { sizes[it]?.linuxBytes ?: 0L }

    // 作业结果统一在这里提示，页面离开后回来仍能看到
    LaunchedEffect(job) {
        when (val state = job) {
            is BackupTaskState.Failed -> {
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, state.message),
                    type = ToastType.Error,
                )
                vm.acknowledgeJob()
            }

            is BackupTaskState.Completed -> {
                toaster.show(
                    context.getString(
                        if (state.restartRequired) R.string.backup_page_restore_success
                        else R.string.backup_page_backup_success
                    ),
                    type = ToastType.Success,
                )
                if (state.restartRequired) onShowRestartDialog()
                vm.acknowledgeJob()
            }

            else -> Unit
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { vm.startExport(exportTarget, it) }
    }

    val createTarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-tar")
    ) { uri ->
        uri?.let { vm.startExport(exportTarget, it, compressWorkspace) }
    }

    val createTarGzLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        uri?.let { vm.startExport(exportTarget, it, compressWorkspace) }
    }

    // 工作区包可能是 .tar、.tar.gz 或旧版 .zip，导入时不按扩展名过滤
    val openWorkspaceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.startWorkspaceImport(it) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            when (importType) {
                "local" -> vm.startChatImport(it)
                "workspace" -> vm.startWorkspaceImport(it)
                "chatbox" -> vm.startChatboxImport(it)
                "cherry" -> vm.startCherryImport(it)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 聊天记录包：与官方一致，只含设置、数据库与附件
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_local_backup_export)) },
        ) {
            backupContentItem(
                options = WebDavConfig.BackupItem.entries,
                selected = selectedBackupItems,
                onChange = { vm.updateLocalBackupItems(it) },
            ) { item ->
                when (item) {
                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                    WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                }
            }
            item(
                onClick = if (!isRunning) {
                    {
                        exportTarget = BackupTarget.CHAT
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                supportingContent = {
                    Text(
                        if (isChatExporting) {
                            exportProgressText(running)
                        } else {
                            stringResource(R.string.backup_page_export_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(
                        HugeIcons.FileExport,
                        loading = isChatExporting,
                    )
                },
            )

            item(
                onClick = if (!isRunning) {
                    { showImportConfirmDialog = true }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                supportingContent = {
                    Text(
                        if (isChatImporting) {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isChatImporting)
                },
            )
        }

        // 工作区包：独立文件，含工作区文件与 Linux 环境
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_workspace_section)) },
        ) {
            item(
                onClick = {
                    vm.updateWorkspaceBackupItems(
                        if (wantsFiles) selectedWorkspaceItems - WorkspaceBackupItem.FILES
                        else selectedWorkspaceItems + WorkspaceBackupItem.FILES
                    )
                },
                headlineContent = { Text(stringResource(R.string.backup_page_workspace)) },
                supportingContent = { Text(stringResource(R.string.backup_page_workspace_files_desc)) },
                leadingContent = { Checkbox(checked = wantsFiles, onCheckedChange = null) },
            )
            item(
                onClick = {
                    vm.updateWorkspaceBackupItems(
                        if (wantsLinux) selectedWorkspaceItems - WorkspaceBackupItem.LINUX
                        else selectedWorkspaceItems + WorkspaceBackupItem.LINUX
                    )
                },
                headlineContent = { Text(stringResource(R.string.backup_page_linux_environment)) },
                supportingContent = { Text(stringResource(R.string.backup_page_linux_desc)) },
                leadingContent = { Checkbox(checked = wantsLinux, onCheckedChange = null) },
            )
            item(
                onClick = { compressWorkspace = !compressWorkspace },
                headlineContent = { Text(stringResource(R.string.backup_page_compress)) },
                supportingContent = { Text(stringResource(R.string.backup_page_compress_desc)) },
                leadingContent = { Switch(checked = compressWorkspace, onCheckedChange = null) },
            )
            item(
                onClick = {
                    vm.refreshWorkspaceSizes()
                    showWorkspacePicker = true
                },
                headlineContent = { Text(stringResource(R.string.backup_page_select_workspaces)) },
                supportingContent = { Text(pickerLabel) },
                leadingContent = { BackupActionIcon(HugeIcons.Folder01) },
            )
            item(
                onClick = if (!isRunning) {
                    {
                        if (localWorkspaces.isEmpty()) {
                            toaster.show(
                                context.getString(R.string.backup_page_workspace_empty),
                                type = ToastType.Warning,
                            )
                        } else if (selectedWorkspaceItems.isEmpty()) {
                            toaster.show(
                                context.getString(R.string.backup_page_workspace_empty_items),
                                type = ToastType.Warning,
                            )
                        } else {
                            vm.refreshWorkspaceSizes()
                            showExportConfirm = true
                        }
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_workspace_export)) },
                supportingContent = {
                    Text(
                        if (isWorkspaceExporting) {
                            exportProgressText(running)
                        } else {
                            stringResource(R.string.backup_page_workspace_export_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(
                        HugeIcons.FileExport,
                        loading = isWorkspaceExporting,
                    )
                },
            )
            item(
                onClick = if (!isRunning) {
                    {
                        importType = "workspace"
                        openWorkspaceLauncher.launch(arrayOf("*/*"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_workspace_import)) },
                supportingContent = {
                    Text(
                        if (isWorkspaceImporting) {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_workspace_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(
                        HugeIcons.FileImport,
                        loading = isWorkspaceImporting,
                    )
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.backup_page_import_from_other_app)) },
        ) {
            item(
                onClick = if (!isRunning) {
                    {
                        importType = "chatbox"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isChatImporting && importType == "chatbox")
                },
            )

            item(
                onClick = if (!isRunning) {
                    {
                        importType = "cherry"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isChatImporting && importType == "cherry")
                },
            )
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showWorkspacePicker) {
        WorkspacePickerDialog(
            workspaces = localWorkspaces,
            selected = effectiveRoots,
            sizes = sizes,
            sizesReady = sizesReady,
            onDismiss = { showWorkspacePicker = false },
            onConfirm = { roots ->
                vm.updateSelectedWorkspaceRoots(roots)
                showWorkspacePicker = false
            },
        )
    }

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(R.string.backup_page_workspace_export)) },
            text = {
                Text(
                    if (sizesReady) {
                        stringResource(
                            R.string.backup_page_export_estimate,
                            filesBytes.fileSizeToString(),
                            linuxBytes.fileSizeToString(),
                        )
                    } else {
                        stringResource(R.string.backup_page_calculating)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = sizesReady,
                    onClick = {
                        showExportConfirm = false
                        exportTarget = BackupTarget.WORKSPACE
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        if (compressWorkspace) {
                            createTarGzLauncher.launch("franhub_workspaces_$timestamp.tar.gz")
                        } else {
                            createTarLauncher.launch("franhub_workspaces_$timestamp.tar")
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingRestore?.let { pending ->
        WorkspaceRestoreDialog(
            manifest = pending.manifest,
            onDismiss = { vm.dismissPendingWorkspaceRestore() },
            onConfirm = { selection -> vm.startWorkspaceRestore(selection) },
        )
    }

    // 恢复后仍没有 Linux 环境的工作区：在这里给出安装入口，不必再跳到工作区详情页
    if (rootfsGaps.isNotEmpty() && installRootfsTarget == null && rootfsInstallProgress == null) {
        AlertDialog(
            onDismissRequest = { vm.dismissRootfsGaps() },
            title = { Text(stringResource(R.string.backup_page_rootfs_missing_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_page_rootfs_missing_desc,
                        rootfsGaps.joinToString("、") { it.name },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { installRootfsTarget = rootfsGaps.first() }) {
                    Text(stringResource(R.string.backup_page_rootfs_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissRootfsGaps() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    rootfsInstallProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_page_rootfs_install)) },
            text = { Text(rootfsInstallText(progress)) },
            confirmButton = {},
        )
    }

    rootfsInstallError?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissRootfsInstallError() },
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissRootfsInstallError() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    installRootfsTarget?.let { workspace ->
        InstallRootfsDialog(
            workspace = workspace,
            onDismiss = { installRootfsTarget = null },
            onConfirm = { url ->
                vm.installRootfsForGap(workspace.id, url)
                installRootfsTarget = null
            },
        )
    }
}

@Composable
private fun WorkspacePickerDialog(
    workspaces: List<WorkspaceEntity>,
    selected: Set<String>,
    sizes: Map<String, WorkspaceSize>,
    sizesReady: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_select_workspaces)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                workspaces.forEach { workspace ->
                    val checked = workspace.root in current
                    val size = sizes[workspace.root]?.totalBytes ?: 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                onValueChange = { value ->
                                    current = if (value) current + workspace.root else current - workspace.root
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(workspace.name)
                            Text(
                                if (sizesReady) size.fileSizeToString()
                                else stringResource(R.string.backup_page_calculating)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceRestoreDialog(
    manifest: WorkspaceBackupManifest,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceRestoreSelection) -> Unit,
) {
    val fileRoots = remember { manifest.workspaces.filter { it.hasFiles }.map { it.root }.toSet() }
    val linuxRoots = remember { manifest.workspaces.filter { it.hasLinux }.map { it.root }.toSet() }
    var pickedFiles by remember { mutableStateOf(fileRoots) }
    var pickedLinux by remember { mutableStateOf(linuxRoots) }
    var mode by remember { mutableStateOf(WorkspaceRestoreMode.REPLACE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_restore_workspaces_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                manifest.workspaces.forEach { info ->
                    Text(info.name, modifier = Modifier.fillMaxWidth())
                    if (info.hasFiles) {
                        ToggleRow(
                            label = stringResource(R.string.backup_page_workspace) +
                                " · " + info.filesBytes.fileSizeToString(),
                            checked = info.root in pickedFiles,
                            onCheckedChange = { value ->
                                pickedFiles = if (value) pickedFiles + info.root else pickedFiles - info.root
                            },
                        )
                    }
                    if (info.hasLinux) {
                        ToggleRow(
                            label = stringResource(R.string.backup_page_linux_environment) +
                                " · " + info.linuxBytes.fileSizeToString(),
                            checked = info.root in pickedLinux,
                            onCheckedChange = { value ->
                                pickedLinux = if (value) pickedLinux + info.root else pickedLinux - info.root
                            },
                        )
                    }
                }
                Text(stringResource(R.string.backup_page_restore_mode))
                RestoreModeRow(
                    label = stringResource(R.string.backup_page_restore_replace),
                    selected = mode == WorkspaceRestoreMode.REPLACE,
                    onSelect = { mode = WorkspaceRestoreMode.REPLACE },
                )
                RestoreModeRow(
                    label = stringResource(R.string.backup_page_restore_merge),
                    selected = mode == WorkspaceRestoreMode.MERGE,
                    onSelect = { mode = WorkspaceRestoreMode.MERGE },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        WorkspaceRestoreSelection(
                            mode = mode,
                            filesRoots = pickedFiles,
                            linuxRoots = pickedLinux,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RestoreModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, onValueChange = { onSelect() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 导出进度文案：统计阶段显示不定进度，写入阶段显示百分比与字节数。 */
@Composable
private fun exportProgressText(state: BackupTaskState.Running?): String {
    if (state == null) return stringResource(R.string.backup_page_exporting)
    val progress = state.progress
    return when (progress.phase) {
        BackupPhase.SCANNING -> stringResource(R.string.backup_notification_scanning)
        BackupPhase.WRITING, BackupPhase.FINISHING -> {
            val done = progress.doneBytes.fileSizeToString()
            if (progress.totalBytes > 0) {
                val percent = (progress.fraction * 100).toInt()
                "$done / ${progress.totalBytes.fileSizeToString()} · $percent%"
            } else {
                done
            }
        }

        BackupPhase.PREPARING -> stringResource(R.string.backup_page_exporting)
    }
}

/** Rootfs 安装进度文案：下载显示字节数，解压显示条目数。 */
@Composable
private fun rootfsInstallText(progress: RootfsInstallProgress): String = when (progress.stage) {
    RootfsInstallStage.DOWNLOADING -> {
        val total = progress.totalBytes
        if (total != null && total > 0) {
            stringResource(
                R.string.backup_page_rootfs_downloading,
                progress.bytesRead.fileSizeToString(),
                total.fileSizeToString(),
            )
        } else {
            stringResource(R.string.backup_page_rootfs_downloading_unknown, progress.bytesRead.fileSizeToString())
        }
    }

    RootfsInstallStage.EXTRACTING ->
        stringResource(R.string.backup_page_rootfs_extracting, progress.entriesExtracted)

    RootfsInstallStage.INSTALLED -> stringResource(R.string.backup_page_rootfs_installed)
}
