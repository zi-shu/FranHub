package me.rerere.workspace

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.name

private const val TAG = "WorkspaceFileSystem"

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    /**
     * 删除文件或目录。
     *
     * 自己递归，不用 `deleteRecursively()`。后者进入子目录按 `isDirectory` 判断，会跟随软链接，
     * 失败时又只给一个 false，说不出是哪一项、为什么。这里逐项删，失败立刻带出路径与原因。
     */
    fun delete(root: File, path: String, recursive: Boolean = false): WorkspaceDeleteResult {
        if (path.isBlank() || path == ".") {
            return WorkspaceDeleteResult.failure(
                WorkspaceDeleteFailure.INVALID_PATH,
                path,
                "拒绝删除工作区根目录",
            )
        }
        val file = try {
            resolvePath(root, path)
        } catch (e: IllegalArgumentException) {
            return WorkspaceDeleteResult.failure(WorkspaceDeleteFailure.INVALID_PATH, path, e.message)
        }
        val link = Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !link) {
            return WorkspaceDeleteResult.failure(WorkspaceDeleteFailure.NOT_FOUND, path, "文件已不存在")
        }
        if (!link && file.isDirectory && !recursive) {
            return WorkspaceDeleteResult.failure(
                WorkspaceDeleteFailure.NEED_RECURSIVE,
                path,
                "目录删除需要 recursive = true",
            )
        }
        return deleteEntry(file, path)
    }

    /** 先删子项再删自己。软链接只删链接本身，不进入目标。 */
    private fun deleteEntry(file: File, relative: String): WorkspaceDeleteResult {
        if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory) {
            return runDelete(file, relative)
        }
        file.listFiles()?.forEach { child ->
            val result = deleteEntry(child, "$relative/${child.name}")
            if (!result.deleted) return result
        }
        return runDelete(file, relative)
    }

    private fun runDelete(file: File, relative: String): WorkspaceDeleteResult {
        return try {
            Files.delete(file.toPath())
            WorkspaceDeleteResult.success()
        } catch (e: NoSuchFileException) {
            WorkspaceDeleteResult.success()
        } catch (e: DirectoryNotEmptyException) {
            reportFailure(WorkspaceDeleteFailure.NOT_EMPTY, file, relative, e)
        } catch (e: AccessDeniedException) {
            reportFailure(WorkspaceDeleteFailure.PERMISSION_DENIED, file, relative, e)
        } catch (e: FileSystemException) {
            reportFailure(classify(e), file, relative, e)
        } catch (e: IOException) {
            reportFailure(classify(e), file, relative, e)
        }
    }

    /** 失败时把现场写进日志：目标状态、父目录权限、错误类型。 */
    private fun reportFailure(
        reason: WorkspaceDeleteFailure,
        file: File,
        relative: String,
        error: IOException,
    ): WorkspaceDeleteResult {
        val parent = file.parentFile
        Log.w(
            TAG,
            "delete failed path=$relative reason=$reason exists=${file.exists()} " +
                "isDir=${file.isDirectory} canWrite=${file.canWrite()} " +
                "perms=${permissionsOf(file)} parent=${parent?.absolutePath} " +
                "parentCanWrite=${parent?.canWrite()} parentPerms=${permissionsOf(parent)} " +
                "error=${error.javaClass.simpleName}: ${error.message}",
        )
        return WorkspaceDeleteResult.failure(reason, relative, error.message)
    }

    private fun classify(error: IOException): WorkspaceDeleteFailure {
        val text = buildString {
            append(error.message.orEmpty())
            if (error is FileSystemException) append(' ').append(error.reason.orEmpty())
        }
        return when {
            text.contains("Read-only", ignoreCase = true) -> WorkspaceDeleteFailure.READ_ONLY
            text.contains("No space", ignoreCase = true) -> WorkspaceDeleteFailure.IO
            text.contains("busy", ignoreCase = true) -> WorkspaceDeleteFailure.BUSY
            text.contains("File name too long", ignoreCase = true) -> WorkspaceDeleteFailure.NAME_TOO_LONG
            text.contains("Permission denied", ignoreCase = true) -> WorkspaceDeleteFailure.PERMISSION_DENIED
            text.contains("Directory not empty", ignoreCase = true) -> WorkspaceDeleteFailure.NOT_EMPTY
            else -> WorkspaceDeleteFailure.IO
        }
    }

    private fun permissionsOf(file: File?): String {
        if (file == null) return "-"
        return runCatching {
            val perms = Files.getPosixFilePermissions(file.toPath())
            buildString {
                append(if (perms.contains(PosixFilePermission.OWNER_READ)) 'r' else '-')
                append(if (perms.contains(PosixFilePermission.OWNER_WRITE)) 'w' else '-')
                append(if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) 'x' else '-')
                append(if (perms.contains(PosixFilePermission.GROUP_READ)) 'r' else '-')
                append(if (perms.contains(PosixFilePermission.GROUP_WRITE)) 'w' else '-')
                append(if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) 'x' else '-')
                append(if (perms.contains(PosixFilePermission.OTHERS_READ)) 'r' else '-')
                append(if (perms.contains(PosixFilePermission.OTHERS_WRITE)) 'w' else '-')
                append(if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) 'x' else '-')
            }
        }.getOrDefault("-")
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
