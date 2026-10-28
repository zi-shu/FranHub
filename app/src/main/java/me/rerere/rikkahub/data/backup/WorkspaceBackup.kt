package me.rerere.rikkahub.data.backup

import me.rerere.workspace.PackageInventory
import me.rerere.workspace.TarArchive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 备份时用户勾选的工作区。 */
@Serializable
data class WorkspaceSelection(
    val root: String,
    val name: String,
    val includeFiles: Boolean,
    val includeLinux: Boolean,
)

/** 包内清单里记录的一个工作区。 */
@Serializable
data class WorkspaceEntryInfo(
    val root: String,
    val name: String,
    val hasFiles: Boolean = false,
    val hasLinux: Boolean = false,
    val filesCount: Int = 0,
    val filesBytes: Long = 0,
    val linuxCount: Int = 0,
    val linuxBytes: Long = 0,
)

@Serializable
data class WorkspaceBackupManifest(
    val version: Int = 1,
    val createdAt: String = "",
    val exclusions: List<String> = emptyList(),
    val workspaces: List<WorkspaceEntryInfo> = emptyList(),
)

/** 一个工作区的体积统计，用于备份前的体积提示。 */
data class WorkspaceSize(
    val filesCount: Int = 0,
    val filesBytes: Long = 0,
    val linuxCount: Int = 0,
    val linuxBytes: Long = 0,
) {
    val totalBytes: Long get() = filesBytes + linuxBytes
}

@Serializable
data class WorkspaceRestoreOptions(
    val mode: WorkspaceRestoreMode = WorkspaceRestoreMode.REPLACE,
)

@Serializable
enum class WorkspaceRestoreMode {
    /** 整份替换，旧目录留一份用于回滚。 */
    REPLACE,

    /** 逐文件覆盖，包内没有的文件保留。 */
    MERGE,
}

/** 工作区备份包里的可选内容。 */
enum class WorkspaceBackupItem {
    FILES,
    LINUX,
}

/** 恢复时用户勾选的项目。 */
data class WorkspaceRestoreSelection(
    val mode: WorkspaceRestoreMode = WorkspaceRestoreMode.REPLACE,
    val filesRoots: Set<String> = emptySet(),
    val linuxRoots: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = filesRoots.isEmpty() && linuxRoots.isEmpty()
}

/**
 * 工作区与 Linux 环境的备份与恢复。
 *
 * 目录约定沿用 [me.rerere.workspace.WorkspaceManager]：`filesDir/workspaces/<root>/{files,linux,tmp}`。
 * 备份包里每个工作区各存两个 tar：`workspaces/<root>/files.tar` 与 `workspaces/<root>/linux.tar`。
 * 用 tar 而不用逐个 zip 条目，是因为 rootfs 里有软链接与权限位，zip 存不住。
 */
object WorkspaceBackup {
    const val MANIFEST_ENTRY = "workspaces/manifest.json"
    const val PACKAGES_ENTRY = "workspaces/packages.json"
    const val MODE_FILE = "mode.json"
    const val WORKSPACES_DIR = "workspaces"

    /** 备份包扩展名。压缩包走 gzip 包裹的同一种 tar 流。 */
    const val EXTENSION_TAR = ".tar"
    const val EXTENSION_TAR_GZ = ".tar.gz"

    val EXCLUDED_LABELS = listOf(
        "tmp", "proc", "sys", "dev", "var/cache", "var/log", "__pycache__", "_cacache", ".cache/pip",
    )

    private val EXCLUDED_SEGMENTS = setOf("tmp", "proc", "sys", "dev", "__pycache__", "_cacache")
    private val EXCLUDED_PREFIXES = listOf("var/cache/", "var/log/", ".cache/pip/", ".npm/_cacache/")

    private val JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun workspaceDir(filesDir: File, root: String): File = File(File(filesDir, WORKSPACES_DIR), root)

    fun sizeOf(workspaceDir: File): WorkspaceSize {
        val files = scan(File(workspaceDir, "files"))
        val linux = scan(File(workspaceDir, "linux"))
        return WorkspaceSize(files.first, files.second, linux.first, linux.second)
    }

    fun filesTarEntry(root: String): String = "$WORKSPACES_DIR/$root/files.tar"

    fun linuxTarEntry(root: String): String = "$WORKSPACES_DIR/$root/linux.tar"

    /** 排除缓存与伪文件系统；正则只按路径段判断，避免误伤 `mytmp` 这类名字。 */
    fun isExcluded(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val segments = relativePath.split('/')
        if (segments.any { it in EXCLUDED_SEGMENTS }) return true
        return EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }
    }

    /**
     * 统计目录内参与备份的文件数与字节数。
     *
     * 与 [writeTar] 用同一套排除规则，数字才对得上。软链接只计一个条目、不跟进去：
     * 跟进去会把 `/bin -> usr/bin` 这类软链接后面的整棵子树算两遍，遇到指向祖先的链接
     * 还会一直转下去，统计永远结束不了。
     */
    fun scan(directory: File): Pair<Int, Long> {
        if (!directory.isDirectory) return 0 to 0L
        return scanInto(directory, "")
    }

    private fun scanInto(directory: File, prefix: String): Pair<Int, Long> {
        val children = directory.listFiles() ?: return 0 to 0L
        var count = 0
        var bytes = 0L
        for (child in children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (isExcluded(path)) continue
            when {
                Files.isSymbolicLink(child.toPath()) -> count++
                child.isDirectory -> {
                    val (childCount, childBytes) = scanInto(child, path)
                    count += childCount
                    bytes += childBytes
                }

                child.isFile -> {
                    count++
                    bytes += child.length()
                }
            }
        }
        return count to bytes
    }

    /**
     * 只统计写入字节数、用于进度上报的包装流。
     *
     * [close] 只冲刷不关闭底层流，底层是 zip 的条目流，关掉会把整个压缩包带下来。
     */
    class CountingOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Long) -> Unit = {},
    ) : OutputStream() {
        var total: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            total += 1
            onBytes(total)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            total += len.toLong()
            onBytes(total)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            flush()
        }
    }

    /**
     * 判断备份包是不是 tar 容器。按文件头判断，不看扩展名：
     * gzip 头 `1f 8b`，或偏移 257 处的 `ustar`。
     */
    fun isTarArchive(file: File): Boolean {
        val head = ByteArray(512)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return true
        if (read < 512) return false
        return String(head, 257, 5, Charsets.US_ASCII) == "ustar"
    }

    /** 打开归档输入流，gzip 自动解开。调用方负责关闭。 */
    fun openArchive(file: File): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 1 shl 20)
        raw.mark(2)
        val first = raw.read()
        val second = raw.read()
        raw.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(raw, 1 shl 20) else raw
    }

    /** 归档输出流。压缩时用最快压缩档，避免长时间占满 CPU。 */
    fun openArchiveOutput(output: OutputStream, compress: Boolean): OutputStream {
        val buffered = BufferedOutputStream(output, 1 shl 20)
        return if (compress) FastGzipOutputStream(buffered) else buffered
    }

    /** 写 gzip 尾部并冲刷，不关闭调用方的流。 */
    fun finishArchive(stream: OutputStream) {
        (stream as? GZIPOutputStream)?.finish()
        stream.flush()
    }

    /** 从归档里读清单。清单排在包首，读到即可返回。 */
    fun readManifestFromArchive(file: File): WorkspaceBackupManifest? = runCatching {
        openArchive(file).use { input ->
            val data = TarArchive.readEntry(input, MANIFEST_ENTRY) ?: return@use null
            readManifest(data.toString(Charsets.UTF_8))
        }
    }.getOrNull()

    private class FastGzipOutputStream(output: OutputStream) : GZIPOutputStream(output, 1 shl 16) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }

    fun writeTar(directory: File, output: OutputStream, onEntry: (String) -> Unit = {}) {        TarArchive.write(
            sourceDir = directory,
            output = output,
            filter = { path -> !isExcluded(path) },
            onEntry = { path, _ -> onEntry(path) },
        )
    }

    fun extractTar(input: InputStream, target: File, onEntry: (String) -> Unit = {}) {
        TarArchive.extract(
            input = input,
            targetDir = target,
            filter = { path -> !isExcluded(path) },
            onEntry = onEntry,
        )
    }

    fun writeManifest(path: File, manifest: WorkspaceBackupManifest) {
        path.parentFile?.mkdirs()
        path.writeText(JSON.encodeToString(WorkspaceBackupManifest.serializer(), manifest))
    }

    fun readManifest(text: String): WorkspaceBackupManifest? =
        runCatching { JSON.decodeFromString(WorkspaceBackupManifest.serializer(), text) }.getOrNull()

    fun writePackages(path: File, inventory: PackageInventory) {
        path.parentFile?.mkdirs()
        path.writeText(JSON.encodeToString(PackageInventory.serializer(), inventory))
    }

    /**
     * 在 Application 启动、Room 与 Koin 初始化之前把暂存的工作区换入。
     *
     * 替换模式每个目录只做两次 rename，带 state 文件标记进度，进程被杀后下次启动能续上。
     * 合并模式逐文件覆盖，不保留回滚快照。
     */
    fun apply(pending: File, filesDir: File) {
        val staged = File(pending, WORKSPACES_DIR)
        if (!staged.isDirectory) return
        val options = readOptions(staged)
        for (rootDir in staged.listFiles().orEmpty().filter { it.isDirectory }) {
            val root = rootDir.name
            for (kind in KINDS) {
                val stagedKind = File(rootDir, kind)
                if (!stagedKind.exists()) continue
                val liveDir = File(workspaceDir(filesDir, root), kind)
                when (options.mode) {
                    WorkspaceRestoreMode.REPLACE -> replaceKind(rootDir, kind, stagedKind, liveDir)
                    WorkspaceRestoreMode.MERGE -> {
                        copyTree(stagedKind, liveDir)
                        writeState(rootDir, kind, STATE_DONE)
                    }
                }
            }
        }
    }

    fun rollback(pending: File, filesDir: File) {
        val staged = File(pending, WORKSPACES_DIR)
        if (!staged.isDirectory) return
        val options = readOptions(staged)
        if (options.mode == WorkspaceRestoreMode.MERGE) return
        for (rootDir in staged.listFiles().orEmpty().filter { it.isDirectory }) {
            for (kind in KINDS) {
                val state = readState(rootDir, kind)
                if (state == null) {
                    // 没有状态但原目录还在：上次刚挪走 live 就被杀，直接放回
                    val liveDir = File(workspaceDir(filesDir, rootDir.name), kind)
                    val original = File(rootDir, "$kind-original")
                    if (original.exists() && !liveDir.exists()) move(original, liveDir)
                    continue
                }
                val liveDir = File(workspaceDir(filesDir, rootDir.name), kind)
                val original = File(rootDir, "$kind-original")
                when (state) {
                    STATE_BACKED_UP -> if (original.exists()) {
                        deleteTree(liveDir)
                        move(original, liveDir)
                    }

                    STATE_DONE -> {
                        deleteTree(liveDir)
                        if (original.exists()) move(original, liveDir)
                    }
                }
            }
        }
    }

    private fun replaceKind(rootDir: File, kind: String, stagedKind: File, liveDir: File) {
        val original = File(rootDir, "$kind-original")
        val state = readState(rootDir, kind)
        if (state == null) {
            // original 已存在而 live 不见，说明上次在 rename 之后、写状态之前被杀，直接认作已备份
            if (!(original.exists() && !liveDir.exists())) {
                if (liveDir.exists()) move(liveDir, original)
            }
            writeState(rootDir, kind, STATE_BACKED_UP)
        }
        if (stagedKind.exists()) {
            deleteTree(liveDir)
            move(stagedKind, liveDir)
        } else if (!liveDir.exists() && original.exists()) {
            // 上次进程在两次 rename 之间被杀，先放回原目录，让调用方按失败处理
            move(original, liveDir)
            throw IOException("工作区 $kind 的暂存目录缺失，已放回原目录")
        }
        writeState(rootDir, kind, STATE_DONE)
    }

    /**
     * 增量合并：把 [source] 的内容叠到 [target] 上，包内没有的文件保留。
     *
     * 递归自己写，不用 `walkTopDown`。后者进入子目录按 `File.isDirectory` 判断，会跟随软链接。
     * rootfs 里有 `/usr/bin/X11 -> .` 这类自引用，跟随会把同一棵树反复拷，
     * 并且出现「源与目标是同一个文件」，恢复在启动阶段直接失败。
     */
    private fun copyTree(source: File, target: File) {
        require(target.isDirectory || target.mkdirs()) { "无法创建工作区目录: ${target.absolutePath}" }
        copyTreeInto(source, target, "", target.canonicalFile)
    }

    private fun copyTreeInto(sourceDir: File, targetDir: File, prefix: String, rootCanonical: File) {
        val children = sourceDir.listFiles() ?: return
        for (child in children) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (isExcluded(relative)) continue
            val destination = File(targetDir, child.name)
            when {
                Files.isSymbolicLink(child.toPath()) -> {
                    val link = runCatching { Files.readSymbolicLink(child.toPath()) }.getOrElse { continue }
                    destination.parentFile?.mkdirs()
                    deleteEntry(destination)
                    runCatching { Files.createSymbolicLink(destination.toPath(), link) }
                        .onFailure { destination.writeText(link.toString()) }
                }

                child.isDirectory -> {
                    // 目标位置若是指向别处的软链接，先删链接，避免把内容写进链接目标
                    if (Files.isSymbolicLink(destination.toPath()) ||
                        (destination.exists() && !destination.isDirectory)
                    ) {
                        deleteEntry(destination)
                    }
                    destination.mkdirs()
                    copyTreeInto(child, destination, relative, rootCanonical)
                }

                child.isFile -> {
                    destination.parentFile?.mkdirs()
                    require(destination.canonicalFile.startsWith(rootCanonical)) {
                        "工作区条目越界: $relative"
                    }
                    child.copyTo(destination, overwrite = true)
                }
            }
        }
    }

    /** 删掉一个条目本身，软链接只删链接，不进目标。 */
    private fun deleteEntry(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory) {
            Files.deleteIfExists(file.toPath())
        } else {
            deleteTree(file)
        }
    }

    /**
     * 删除整棵目录树，不跟随软链接。
     *
     * `File.deleteRecursively()` 与 `walkTopDown()` 一样按 `File.isDirectory` 判断，
     * 会把 `X11 -> .` 这样的链接当成目录跟进去，删一次 rootfs 要几分钟。
     */
    fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteTree(child) }
        }
        Files.deleteIfExists(path)
    }

    private fun readOptions(staged: File): WorkspaceRestoreOptions {
        val file = File(staged, MODE_FILE)
        if (!file.isFile) return WorkspaceRestoreOptions()
        return runCatching {
            JSON.decodeFromString(WorkspaceRestoreOptions.serializer(), file.readText())
        }.getOrDefault(WorkspaceRestoreOptions())
    }

    fun writeOptions(staged: File, mode: WorkspaceRestoreMode) {
        staged.mkdirs()
        File(staged, MODE_FILE).writeText(
            JSON.encodeToString(WorkspaceRestoreOptions.serializer(), WorkspaceRestoreOptions(mode))
        )
    }

    private fun writeState(rootDir: File, kind: String, state: String) {
        File(rootDir, "$kind-state").writeText(state)
    }

    private fun readState(rootDir: File, kind: String): String? =
        File(rootDir, "$kind-state").takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private val KINDS = listOf("files", "linux")
    private const val STATE_BACKED_UP = "backed_up"
    private const val STATE_DONE = "done"
}
