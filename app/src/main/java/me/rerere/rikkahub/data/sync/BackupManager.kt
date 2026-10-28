package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.backup.BackupPhase
import me.rerere.rikkahub.data.backup.BackupProgress
import me.rerere.rikkahub.data.backup.WorkspaceBackup
import me.rerere.rikkahub.data.backup.WorkspaceBackupManifest
import me.rerere.rikkahub.data.backup.WorkspaceEntryInfo
import me.rerere.rikkahub.data.backup.WorkspaceRestoreSelection
import me.rerere.rikkahub.data.backup.WorkspaceSelection
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.workspace.PackageInventory
import me.rerere.workspace.TarArchive
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.awaitLoaded()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        workspaces: WorkspaceRestoreSelection? = null,
    ) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            if (entry.name.startsWith("${WorkspaceBackup.WORKSPACES_DIR}/")) {
                                if (restoreWorkspaceEntry(zip, entry, staging, workspaces)) restoredEntries++
                                continue
                            }
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    if (workspaces != null && !workspaces.isEmpty) {
                        WorkspaceBackup.writeOptions(
                            File(staging, WorkspaceBackup.WORKSPACES_DIR),
                            workspaces.mode,
                        )
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                        val settings = json.decodeFromString<Settings>(migrated)
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeSettings(settings, json.launchCountOf(migrated)))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    /**
     * 把工作区与 Linux 环境写进 [output]。
     *
     * 容器是单个 tar，可选 gzip 压缩。直接流式写进用户选定的目标文件，不经过 cacheDir：
     * 不占两倍空间，也不会因为中间文件被系统清理而中断。
     * 写入失败时由调用方删除半成品文档。
     */
    suspend fun writeWorkspaceArchive(
        workspaces: List<WorkspaceSelection>,
        output: OutputStream,
        compress: Boolean,
        onProgress: (BackupProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val archive = WorkspaceBackup.openArchiveOutput(output, compress)
        writeWorkspaces(archive, workspaces, onProgress)
        WorkspaceBackup.finishArchive(archive)
    }

    /** 工作区包恢复：只解工作区条目，不碰数据库、设置与附件。 */
    suspend fun stageWorkspaceRestore(archive: File, workspaces: WorkspaceRestoreSelection) =
        withContext(Dispatchers.IO) {
            if (WorkspaceBackup.isTarArchive(archive)) {
                stageWorkspaceRestoreFromTar(archive, workspaces)
            } else {
                // 0.2.0 及更早版本用 zip 容器，保留读取路径
                stageRestore(
                    archive = archive,
                    includeDatabase = false,
                    includeFiles = false,
                    workspaces = workspaces,
                )
            }
        }

    /**
     * tar 包恢复：把选中的 `workspaces/<root>/{files,linux}/...` 解到暂存目录。
     *
     * 归档顺序读，未选中的条目只跳过数据区，不解包。
     */
    private suspend fun stageWorkspaceRestoreFromTar(
        archive: File,
        workspaces: WorkspaceRestoreSelection,
    ) = restoreMutex.withLock {
        val restore = pendingRestore(context)
        val staging = restore.createStagingDirectory()
        try {
            var restoredEntries = 0
            val input = WorkspaceBackup.openArchive(archive)
            try {
                TarArchive.extract(
                    input = input,
                    targetDir = staging,
                    filter = { path -> matchesWorkspaceEntry(path, workspaces) },
                    onEntry = { restoredEntries++ },
                )
            } finally {
                runCatching { input.close() }
            }
            require(restoredEntries > 0) { "No selected data found in the backup" }
            WorkspaceBackup.writeOptions(
                File(staging, WorkspaceBackup.WORKSPACES_DIR),
                workspaces.mode,
            )
            currentCoroutineContext().ensureActive()
            restore.publish(staging)
        } finally {
            WorkspaceBackup.deleteTree(staging)
        }
    }

    /** 归档条目是否属于用户勾选的工作区与项目。 */
    private fun matchesWorkspaceEntry(path: String, selection: WorkspaceRestoreSelection): Boolean {
        val prefix = "${WorkspaceBackup.WORKSPACES_DIR}/"
        if (!path.startsWith(prefix)) return false
        val rest = path.removePrefix(prefix)
        val root = rest.substringBefore('/')
        if (!WORKSPACE_ROOT_REGEX.matches(root)) return false
        val tail = rest.substringAfter('/', "")
        val kind = when {
            tail == "files" || tail.startsWith("files/") -> "files"
            tail == "linux" || tail.startsWith("linux/") -> "linux"
            else -> return false
        }
        return root in (if (kind == "files") selection.filesRoots else selection.linuxRoots)
    }

    /** 处理 zip 容器里的 `workspaces/<root>/files.tar` 一类条目，返回 true 表示确实解包了内容。 */
    private fun restoreWorkspaceEntry(
        zip: ZipFile,
        entry: ZipEntry,
        staging: File,
        selection: WorkspaceRestoreSelection?,
    ): Boolean {
        if (selection == null || selection.isEmpty) return false
        val path = entry.name.removePrefix("${WorkspaceBackup.WORKSPACES_DIR}/")
        val root = path.substringBefore('/')
        if (!WORKSPACE_ROOT_REGEX.matches(root)) return false
        val kind = when {
            path.endsWith("/files.tar") -> "files"
            path.endsWith("/linux.tar") -> "linux"
            else -> return false
        }
        val wanted = if (kind == "files") selection.filesRoots else selection.linuxRoots
        if (root !in wanted) return false
        val target = File(File(staging, WorkspaceBackup.WORKSPACES_DIR), "$root/$kind")
        check(target.isDirectory || target.mkdirs()) { "Cannot create workspace staging directory" }
        zip.getInputStream(entry).use { input -> WorkspaceBackup.extractTar(input, target) }
        return true
    }

    /**
     * 写清单、包管理器清单与各工作区的目录树。
     *
     * 条目路径统一为 `workspaces/<root>/files/...` 与 `workspaces/<root>/linux/...`，
     * 归档里不再套第二层 tar，写入端可以一路流式追加，不必事先知道整包大小。
     */
    private suspend fun writeWorkspaces(
        sink: OutputStream,
        workspaces: List<WorkspaceSelection>,
        onProgress: (BackupProgress) -> Unit,
    ) {
        data class Planned(
            val selection: WorkspaceSelection,
            val filesDir: File,
            val linuxDir: File,
            val files: Pair<Int, Long>,
            val linux: Pair<Int, Long>,
            val packages: PackageInventory,
        )

        onProgress(BackupProgress(phase = BackupPhase.SCANNING))
        val planned = workspaces
            .filter { WORKSPACE_ROOT_REGEX.matches(it.root) }
            .map { selection ->
                val workspaceDir = WorkspaceBackup.workspaceDir(context.filesDir, selection.root)
                val filesDir = File(workspaceDir, "files")
                val linuxDir = File(workspaceDir, "linux")
                Planned(
                    selection = selection,
                    filesDir = filesDir,
                    linuxDir = linuxDir,
                    files = if (selection.includeFiles) WorkspaceBackup.scan(filesDir) else 0 to 0L,
                    linux = if (selection.includeLinux) WorkspaceBackup.scan(linuxDir) else 0 to 0L,
                    packages = if (selection.includeLinux) {
                        PackageInventory.collect(linuxDir)
                    } else {
                        PackageInventory()
                    },
                )
            }
        currentCoroutineContext().ensureActive()
        val totalBytes = planned.sumOf { it.files.second + it.linux.second }
        val totalEntries = planned.sumOf { it.files.first + it.linux.first }

        var doneEntries = 0
        var writtenBytes = 0L
        val counter = WorkspaceBackup.CountingOutputStream(sink) { written ->
            writtenBytes = written
            onProgress(
                BackupProgress(
                    phase = BackupPhase.WRITING,
                    doneBytes = written,
                    totalBytes = totalBytes,
                    entries = doneEntries,
                )
            )
        }
        val writer = TarArchive.TarWriter(counter)

        // 清单排在包首：恢复端读开头就能拿到内容与体积
        val manifest = WorkspaceBackupManifest(
            createdAt = LocalDateTime.now().toString(),
            exclusions = WorkspaceBackup.EXCLUDED_LABELS,
            workspaces = planned.map { plan ->
                WorkspaceEntryInfo(
                    root = plan.selection.root,
                    name = plan.selection.name,
                    hasFiles = plan.files.first > 0,
                    hasLinux = plan.linux.first > 0,
                    filesCount = plan.files.first,
                    filesBytes = plan.files.second,
                    linuxCount = plan.linux.first,
                    linuxBytes = plan.linux.second,
                )
            },
        )
        writer.bytes(
            WorkspaceBackup.MANIFEST_ENTRY,
            json.encodeToString(WorkspaceBackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
        )

        val packages = PackageInventory(
            apk = planned.flatMap { it.packages.apk }.distinctBy { it.name.lowercase() }.sortedBy { it.name },
            dpkg = planned.flatMap { it.packages.dpkg }.distinctBy { it.name.lowercase() }.sortedBy { it.name },
            pip = planned.flatMap { it.packages.pip }.distinctBy { it.name.lowercase() }.sortedBy { it.name },
            npm = planned.flatMap { it.packages.npm }.distinctBy { it.name.lowercase() }.sortedBy { it.name },
        )
        writer.bytes(
            WorkspaceBackup.PACKAGES_ENTRY,
            json.encodeToString(PackageInventory.serializer(), packages).toByteArray(Charsets.UTF_8),
        )

        val reportEntry: (String) -> Unit = { path ->
            onProgress(
                BackupProgress(
                    phase = BackupPhase.WRITING,
                    doneBytes = writtenBytes,
                    totalBytes = totalBytes,
                    entries = doneEntries,
                    current = path,
                )
            )
        }

        for (plan in planned) {
            currentCoroutineContext().ensureActive()
            val root = plan.selection.root
            if (plan.selection.includeFiles && plan.filesDir.isDirectory) {
                writer.directoryTree(
                    sourceDir = plan.filesDir,
                    prefix = "${WorkspaceBackup.WORKSPACES_DIR}/$root/files",
                    filter = { path -> !WorkspaceBackup.isExcluded(path) },
                    onEntry = { path, _ -> reportEntry(path) },
                )
                doneEntries += plan.files.first
            }
            if (plan.selection.includeLinux && plan.linuxDir.isDirectory) {
                writer.directoryTree(
                    sourceDir = plan.linuxDir,
                    prefix = "${WorkspaceBackup.WORKSPACES_DIR}/$root/linux",
                    filter = { path -> !WorkspaceBackup.isExcluded(path) },
                    onEntry = { path, _ -> reportEntry(path) },
                )
                doneEntries += plan.linux.first
            }
        }

        writer.finish()
        onProgress(
            BackupProgress(
                phase = BackupPhase.FINISHING,
                doneBytes = totalBytes,
                totalBytes = totalBytes,
                entries = totalEntries,
            )
        )
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private val WORKSPACE_ROOT_REGEX = Regex("[A-Za-z0-9._-]+")

        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** 是否有待应用的恢复。Application 启动时用它决定要不要先亮提示。 */
        fun hasPendingRestore(context: Context): Boolean =
            File(context.noBackupFilesDir, "backup-restore/pending").isDirectory

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeFromString<Settings>(settingsJson),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}
