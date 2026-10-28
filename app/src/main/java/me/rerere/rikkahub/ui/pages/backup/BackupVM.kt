package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.backup.BackupPhase
import me.rerere.rikkahub.data.backup.BackupProgress
import me.rerere.rikkahub.data.backup.BackupTaskKind
import me.rerere.rikkahub.data.backup.BackupTaskRunner
import me.rerere.rikkahub.data.backup.BackupTaskState
import me.rerere.rikkahub.data.backup.PendingWorkspaceRestore
import me.rerere.rikkahub.data.backup.WorkspaceBackup
import me.rerere.rikkahub.data.backup.WorkspaceBackupManifest
import me.rerere.rikkahub.data.backup.WorkspaceRestoreMode
import me.rerere.rikkahub.data.backup.WorkspaceRestoreSelection
import me.rerere.rikkahub.data.backup.WorkspaceSelection
import me.rerere.rikkahub.data.backup.WorkspaceSize
import me.rerere.rikkahub.data.backup.WorkspaceBackupItem
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceManager
import me.rerere.rikkahub.utils.UiState
import java.io.File
import java.util.zip.ZipFile

private const val TAG = "BackupVM"

enum class BackupTarget { CHAT, WORKSPACE }

class BackupVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val backupManager: BackupManager,
    private val workspaceManager: WorkspaceManager,
    private val workspaceDao: WorkspaceDAO,
    private val workspaceRepository: WorkspaceRepository,
    private val runner: BackupTaskRunner,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    /** 聊天记录备份项，沿用官方原样的两项。 */
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    /** 工作区备份项，与聊天记录包互不影响。 */
    val workspaceBackupItems = MutableStateFlow(
        listOf(WorkspaceBackupItem.FILES, WorkspaceBackupItem.LINUX)
    )

    /** 本地备份里勾选的工作区根目录；空集合表示按「全选」处理。 */
    val selectedWorkspaceRoots = MutableStateFlow<Set<String>>(emptySet())

    val workspaces = workspaceDao.listFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList<WorkspaceEntity>(),
    )

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun updateWorkspaceBackupItems(items: List<WorkspaceBackupItem>) {
        workspaceBackupItems.value = items
    }

    fun updateSelectedWorkspaceRoots(roots: Set<String>) {
        selectedWorkspaceRoots.value = roots
    }

    /** 统计勾选工作区的体积，备份前的确认弹窗用。 */
    fun workspaceSizes(): Map<String, WorkspaceSize> = workspaces.value
        .filter { !it.isSsh() }
        .associate { workspace ->
            workspace.root to WorkspaceBackup.sizeOf(workspaceManager.workspaceDir(workspace.root))
        }

    /** 读取备份包里的工作区清单；不是新版备份时返回 null。 */
    suspend fun readWorkspaceManifest(file: File): WorkspaceBackupManifest? = withContext(Dispatchers.IO) {
        if (WorkspaceBackup.isTarArchive(file)) {
            WorkspaceBackup.readManifestFromArchive(file)
        } else {
            runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(WorkspaceBackup.MANIFEST_ENTRY) ?: return@use null
                    zip.getInputStream(entry).bufferedReader().use { reader ->
                        WorkspaceBackup.readManifest(reader.readText())
                    }
                }
            }.getOrNull()
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    /** 聊天记录包：与官方行为一致，只含设置、数据库与附件。 */
    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    /** 当前勾选的工作区，导出与体积预估共用。 */
    private fun workspaceSelections(): List<WorkspaceSelection> {
        val items = workspaceBackupItems.value
        val selected = selectedWorkspaceRoots.value
        return workspaces.value
            .filter { !it.isSsh() }
            .filter { selected.isEmpty() || it.root in selected }
            .map {
                WorkspaceSelection(
                    root = it.root,
                    name = it.name,
                    includeFiles = WorkspaceBackupItem.FILES in items,
                    includeLinux = WorkspaceBackupItem.LINUX in items,
                )
            }
    }

    /** 统一入口：作业交给运行器，在 IO 线程里执行 [block]。 */
    private fun launchTask(
        kind: BackupTaskKind,
        restartRequired: Boolean,
        block: suspend () -> Unit,
    ) {
        runner.start(kind) {
            withContext(Dispatchers.IO) { block() }
            restartRequired
        }
    }

    suspend fun restoreWorkspaceBackup(file: File, selection: WorkspaceRestoreSelection) {
        backupManager.stageWorkspaceRestore(file, selection)
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings {
            it.copy(
                providers = importProviders + it.providers,
            )
        }
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    // ---- 备份与恢复作业 ----

    /** 作业状态在应用作用域的 [BackupTaskRunner] 里，离开页面不中断。 */
    val backupJob: StateFlow<BackupTaskState> = runner.state

    val pendingWorkspaceRestore: StateFlow<PendingWorkspaceRestore?> = runner.pendingWorkspaceRestore

    /** 工作区体积，进入选择弹窗或导出确认前刷新。 */
    val workspaceSizesState = MutableStateFlow<Map<String, WorkspaceSize>>(emptyMap())

    /** 体积统计是否已完成，界面用它显示「计算中」。 */
    val workspaceSizesReady = MutableStateFlow(false)

    fun refreshWorkspaceSizes() {
        viewModelScope.launch {
            workspaceSizesReady.value = false
            // 刚进页面时 DAO 的 Flow 可能还没发第一帧，等一次，避免统计出空表
            val list = workspaces.value.ifEmpty { workspaces.first() }
            val sizes = withContext(Dispatchers.IO) {
                list.filter { !it.isSsh() }.associate { workspace ->
                    // 单个工作区统计失败时不拖垮整张表，按 0 显示
                    val size = runCatching {
                        WorkspaceBackup.sizeOf(workspaceManager.workspaceDir(workspace.root))
                    }.getOrDefault(WorkspaceSize())
                    workspace.root to size
                }
            }
            workspaceSizesState.value = sizes
            workspaceSizesReady.value = true
        }
    }

    fun acknowledgeJob() = runner.acknowledge()

    fun dismissPendingWorkspaceRestore() = runner.clearPendingWorkspaceRestore()

    /** 导出到用户选定的 Uri。工作区包与聊天包共用一条链路。 */
    fun startExport(target: BackupTarget, uri: Uri, compress: Boolean = false) {
        val kind = if (target == BackupTarget.CHAT) BackupTaskKind.CHAT_EXPORT else BackupTaskKind.WORKSPACE_EXPORT
        // 设置与勾选先取出来：作业可能在页面销毁后继续跑
        val config = settings.value.webDavConfig.copy(items = localBackupItems.value)
        val selections = workspaceSelections()
        runner.start(kind) { report ->
            withContext(Dispatchers.IO) {
                when (target) {
                    BackupTarget.CHAT -> {
                        val file = webDavSync.prepareBackupFile(config)
                        try {
                            report(BackupProgress(phase = BackupPhase.WRITING))
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                file.inputStream().use { input -> input.copyTo(output) }
                            } ?: error(exportWriteFailed())
                        } finally {
                            file.delete()
                        }
                        recordBackupTime()
                    }

                    BackupTarget.WORKSPACE -> {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error(exportWriteFailed())
                        try {
                            output.use { backupManager.writeWorkspaceArchive(selections, it, compress, report) }
                        } catch (e: Throwable) {
                            // 失败时删掉半成品，别在用户目录里留一个打不开的包
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            throw e
                        }
                    }
                }
            }
            false
        }
    }

    private fun exportWriteFailed(): String = context.getString(R.string.backup_page_export_failed_write)

    /** 聊天记录包导入，结束需要重启。 */
    fun startChatImport(uri: Uri) = launchTask(BackupTaskKind.CHAT_IMPORT, restartRequired = true) {
        val temp = copyToCache(uri, "temp_restore")
        try {
            restoreFromLocalFile(temp)
        } finally {
            temp.delete()
        }
    }

    /** 工作区包导入：先读清单，有内容就挂起等用户挑。 */
    fun startWorkspaceImport(uri: Uri) =
        launchTask(BackupTaskKind.WORKSPACE_IMPORT, restartRequired = false) {
            val temp = copyToCache(uri, "temp_ws")
            val manifest = readWorkspaceManifest(temp)
            if (manifest == null) {
                temp.delete()
                error(context.getString(R.string.backup_page_workspace_invalid))
            }
            runner.setPendingWorkspaceRestore(PendingWorkspaceRestore(temp, manifest))
        }

    /** 恢复后仍然不会有 Linux 环境的工作区，界面据此提示安装 Rootfs。 */
    private val _rootfsGaps = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
    val rootfsGaps: StateFlow<List<WorkspaceEntity>> = _rootfsGaps.asStateFlow()

    /** Rootfs 安装进度，null 表示没有在安装。 */
    val rootfsInstallProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val rootfsInstallError = MutableStateFlow<String?>(null)

    fun dismissRootfsGaps() {
        _rootfsGaps.value = emptyList()
    }

    fun dismissRootfsInstallError() {
        rootfsInstallError.value = null
    }

    /** 在备份页直接给缺失 Linux 环境的工作区装 Rootfs，走与工作区页同一条安装流程。 */
    fun installRootfsForGap(workspaceId: String, url: String) {
        viewModelScope.launch {
            rootfsInstallError.value = null
            rootfsInstallProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                workspaceRepository.installRootfs(workspaceId, url) { progress ->
                    rootfsInstallProgress.value = progress
                }
                _rootfsGaps.value = _rootfsGaps.value.filterNot { it.id == workspaceId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                rootfsInstallError.value = e.message ?: "Rootfs 安装失败"
            } finally {
                rootfsInstallProgress.value = null
            }
        }
    }

    /**
     * 恢复后仍缺 Linux 环境的工作区。
     *
     * 只要该工作区的 linux 被勾选、且包内确实带了 linux，恢复就会补上，不算缺口。
     * 其余情况看本机当前是否已有 Rootfs。
     */
    private fun rootfsGapsAfterRestore(
        pending: PendingWorkspaceRestore,
        selection: WorkspaceRestoreSelection,
    ): List<WorkspaceEntity> {
        val restoredRoots = selection.filesRoots + selection.linuxRoots
        return workspaces.value
            .filter { it.root in restoredRoots }
            .filterNot { workspace ->
                val willRestoreLinux = workspace.root in selection.linuxRoots &&
                    pending.manifest.workspaces.any { it.root == workspace.root && it.hasLinux }
                willRestoreLinux || workspaceManager.hasRootfs(workspace.root)
            }
    }

    /** 用户在工作区恢复弹窗里确认后执行。 */
    fun startWorkspaceRestore(selection: WorkspaceRestoreSelection) {
        if (runner.isRunning()) return
        val pending = runner.takePendingWorkspaceRestore() ?: return
        launchTask(BackupTaskKind.WORKSPACE_RESTORE, restartRequired = true) {
            try {
                restoreWorkspaceBackup(pending.file, selection)
                _rootfsGaps.value = rootfsGapsAfterRestore(pending, selection)
            } finally {
                pending.file.delete()
            }
        }
    }

    fun startChatboxImport(uri: Uri) = launchTask(BackupTaskKind.CHATBOX_IMPORT, restartRequired = true) {
        val temp = copyToCache(uri, "temp_chatbox")
        try {
            restoreFromChatBox(temp)
        } finally {
            temp.delete()
        }
    }

    fun startCherryImport(uri: Uri) = launchTask(BackupTaskKind.CHERRY_IMPORT, restartRequired = true) {
        val temp = copyToCache(uri, "temp_cherry")
        try {
            restoreFromCherryStudio(temp)
        } finally {
            temp.delete()
        }
    }

    private fun copyToCache(uri: Uri, prefix: String): File {
        val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        return file
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
