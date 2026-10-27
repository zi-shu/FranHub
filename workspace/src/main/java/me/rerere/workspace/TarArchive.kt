package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 精简 tar 读写器，用途是把一个目录树（含软链接与权限位）搬进备份包再搬回来。
 *
 * 写出：POSIX ustar，超长路径与超长软链接目标用 GNU 'L'/'K' 扩展条目。
 * 读入：兼容 ustar、GNU 长名与 PAX 'x' 头，与 [RootfsInstaller] 支持的条目类型保持一致。
 *
 * 只处理文件、目录、软链接与硬链接。设备节点、FIFO、socket 在 Android 上既不可创建也不进备份，
 * 遇到时跳过数据区继续。
 */
object TarArchive {
    private const val BLOCK = 512
    private const val LONG_NAME_ENTRY = "././@LongLink"
    private const val BUFFER_SIZE = 64 * 1024

    private const val TYPE_FILE = '0'
    private const val TYPE_HARDLINK = '1'
    private const val TYPE_SYMLINK = '2'
    private const val TYPE_DIRECTORY = '5'
    private const val TYPE_LONG_NAME = 'L'
    private const val TYPE_LONG_LINK = 'K'
    private const val TYPE_PAX = 'x'

    /** 目录为空时写入不产生条目，解包后需要显式建回，故单独记录空目录。 */
    fun write(
        sourceDir: File,
        output: OutputStream,
        filter: (relativePath: String) -> Boolean = { true },
        onEntry: (relativePath: String, file: File) -> Unit = { _, _ -> },
    ) {
        require(sourceDir.isDirectory) { "Not a directory: ${sourceDir.absolutePath}" }
        writeDirectory(sourceDir, "", output, filter, onEntry)
        output.write(ByteArray(BLOCK * 2))
        output.flush()
    }

    private fun writeDirectory(
        directory: File,
        relativePath: String,
        output: OutputStream,
        filter: (String) -> Boolean,
        onEntry: (String, File) -> Unit,
    ) {
        val children = directory.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val path = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            if (!filter(path)) continue
            when {
                Files.isSymbolicLink(child.toPath()) -> {
                    val target = runCatching { Files.readSymbolicLink(child.toPath()).toString() }
                        .getOrElse { continue }
                    writeSymlink(path, target, child.lastModified(), output)
                    onEntry(path, child)
                }

                child.isDirectory -> {
                    writeHeader(path.ensureTrailingSlash(), TYPE_DIRECTORY, 0, 493, child.lastModified(), "", output)
                    onEntry(path, child)
                    writeDirectory(child, path, output, filter, onEntry)
                }

                child.isFile -> {
                    val size = child.length()
                    val mode = if (child.canExecute()) 493 else 420
                    writeHeader(path, TYPE_FILE, size, mode, child.lastModified(), "", output)
                    copyExactly(child, size, output)
                    onEntry(path, child)
                }
                // 设备节点、FIFO、socket 与 Android 上无法表示的条目一律跳过
            }
        }
    }

    private fun writeSymlink(path: String, target: String, lastModified: Long, output: OutputStream) {
        writeHeader(path, TYPE_SYMLINK, 0, 511, lastModified, target, output)
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private fun writeHeader(
        name: String,
        type: Char,
        size: Long,
        mode: Int,
        lastModified: Long,
        linkName: String,
        output: OutputStream,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val linkBytes = linkName.toByteArray(Charsets.UTF_8)
        // GNU 扩展：超长名字与超长链接目标先各写一个数据条目，主头部里放截断副本
        if (nameBytes.size > 100) writeLongEntry(TYPE_LONG_NAME, nameBytes, output)
        if (linkBytes.size > 100) writeLongEntry(TYPE_LONG_LINK, linkBytes, output)

        val header = ByteArray(BLOCK)
        header.putString(0, 100, nameBytes)
        header.putOctal(100, 8, mode.toLong())
        header.putOctal(108, 8, 0)
        header.putOctal(116, 8, 0)
        header.putOctal(124, 12, size)
        header.putOctal(136, 12, (lastModified / 1000L).coerceAtLeast(0))
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = type.code.toByte()
        header.putString(157, 100, linkBytes)
        header.putString(257, 6, "ustar\u0000".toByteArray(Charsets.US_ASCII))
        header.putString(263, 2, "00".toByteArray(Charsets.US_ASCII))
        header.putString(265, 32, "root".toByteArray(Charsets.US_ASCII))
        header.putString(297, 32, "root".toByteArray(Charsets.US_ASCII))

        var checksum = 0L
        for (byte in header) checksum += (byte.toInt() and 0xFF)
        val checksumText = checksum.toString(8).padStart(6, '0')
        header.putString(148, 6, checksumText.toByteArray(Charsets.US_ASCII))
        header[154] = 0
        header[155] = ' '.code.toByte()

        output.write(header)
    }

    private fun writeLongEntry(type: Char, payload: ByteArray, output: OutputStream) {
        val data = payload + 0.toByte()
        val header = ByteArray(BLOCK)
        header.putString(0, 100, LONG_NAME_ENTRY.toByteArray(Charsets.US_ASCII))
        header.putOctal(100, 8, 420)
        header.putOctal(108, 8, 0)
        header.putOctal(116, 8, 0)
        header.putOctal(124, 12, data.size.toLong())
        header.putOctal(136, 12, System.currentTimeMillis() / 1000L)
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = type.code.toByte()
        header.putString(257, 6, "ustar\u0000".toByteArray(Charsets.US_ASCII))
        header.putString(263, 2, "00".toByteArray(Charsets.US_ASCII))
        var checksum = 0L
        for (byte in header) checksum += (byte.toInt() and 0xFF)
        header.putString(148, 6, checksum.toString(8).padStart(6, '0').toByteArray(Charsets.US_ASCII))
        header[154] = 0
        header[155] = ' '.code.toByte()
        output.write(header)
        output.write(data)
        writePadding(data.size.toLong(), output)
    }

    private fun ByteArray.putString(offset: Int, length: Int, value: ByteArray) {
        val count = minOf(length, value.size)
        System.arraycopy(value, 0, this, offset, count)
    }

    private fun ByteArray.putOctal(offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0').takeLast(length - 1)
        putString(offset, length - 1, text.toByteArray(Charsets.US_ASCII))
        this[offset + length - 1] = 0
    }

    private fun copyExactly(file: File, size: Long, output: OutputStream) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        // 文件在写入过程中被改小：补零保持块边界，解包端按头部 size 读不会错位
        val zeros = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val chunk = minOf(zeros.size.toLong(), remaining).toInt()
            output.write(zeros, 0, chunk)
            remaining -= chunk
        }
        writePadding(size, output)
    }

    private fun writePadding(size: Long, output: OutputStream) {
        val remainder = (size % BLOCK).toInt()
        if (remainder == 0) return
        output.write(ByteArray(BLOCK - remainder))
    }

    /**
     * 解包到 [targetDir]。条目路径越界、含 `..`、含反斜杠或指向目录外软链接时抛出 [IOException]。
     * 软链接按原样创建，不跟随解析，与 rootfs 内 `/bin/sh -> /bin/busybox` 这类绝对链接一致。
     */
    fun extract(
        input: InputStream,
        targetDir: File,
        filter: (relativePath: String) -> Boolean = { true },
        onEntry: (relativePath: String) -> Unit = {},
    ): Int {
        require(targetDir.isDirectory || targetDir.mkdirs()) {
            "Cannot create extraction directory: ${targetDir.absolutePath}"
        }
        val rootPath = targetDir.canonicalFile.toPath()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String>? = null
        var count = 0

        while (true) {
            if (!input.readFullyOrEnd(header)) break
            if (header.all { it == 0.toByte() }) {
                // 归档结尾是连续两个全零块，读到第一个即可停止
                break
            }
            val rawName = header.readString(0, 100)
            val prefix = header.readString(345, 155)
            val type = header[156].toInt().toChar()
            val size = header.readOctal(124, 12)
            val mode = header.readOctal(100, 8).toInt()
            val mtime = header.readOctal(136, 12) * 1000L
            val rawLink = header.readString(157, 100)

            when (type) {
                TYPE_LONG_NAME -> {
                    longName = readEntryData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }

                TYPE_LONG_LINK -> {
                    longLink = readEntryData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }

                TYPE_PAX -> {
                    pax = parsePax(readEntryData(input, size).toString(Charsets.UTF_8))
                    continue
                }
            }

            val name = pax?.get("path") ?: longName
                ?: listOf(prefix, rawName).filter { it.isNotBlank() }.joinToString("/")
            val linkName = pax?.get("linkpath") ?: longLink ?: rawLink
            longName = null
            longLink = null
            pax = null

            val relative = normalizeEntryPath(name) ?: run {
                skipEntry(input, size)
                continue
            }
            if (!filter(relative)) {
                skipEntry(input, size)
                continue
            }

            val target = resolveInside(rootPath, relative)
            when (type) {
                TYPE_DIRECTORY -> {
                    check(target.toFile().isDirectory || target.toFile().mkdirs()) {
                        "Cannot create directory: $relative"
                    }
                    applyMode(target, mode)
                    count++
                    onEntry(relative)
                }

                TYPE_SYMLINK -> {
                    val parent = target.parent ?: rootPath
                    check(parent.toFile().isDirectory || parent.toFile().mkdirs()) {
                        "Cannot create directory for $relative"
                    }
                    // 父目录若是指向外部的软链接，canonical 路径会落在 root 之外，直接拒绝
                    require(parent.toFile().canonicalFile.toPath().startsWith(rootPath)) {
                        "Symlink parent escapes target: $relative"
                    }
                    Files.deleteIfExists(target)
                    runCatching { Files.createSymbolicLink(target, Path.of(linkName)) }
                        .getOrElse {
                            // Android 存储或文件系统不支持软链时退化为普通文件，保证条目数量与内容不丢
                            target.toFile().writeText(linkName)
                        }
                    count++
                    onEntry(relative)
                }

                TYPE_HARDLINK -> {
                    val source = normalizeEntryPath(linkName)?.let { runCatching { resolveInside(rootPath, it) }.getOrNull() }
                    val parent = target.parent ?: rootPath
                    check(parent.toFile().isDirectory || parent.toFile().mkdirs()) {
                        "Cannot create directory for $relative"
                    }
                    if (source != null && Files.isRegularFile(source)) {
                        runCatching {
                            Files.deleteIfExists(target)
                            Files.createLink(target, source)
                        }.onFailure {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    count++
                    onEntry(relative)
                }

                TYPE_FILE -> {
                    val parent = target.parent ?: rootPath
                    check(parent.toFile().isDirectory || parent.toFile().mkdirs()) {
                        "Cannot create directory for $relative"
                    }
                    require(parent.toFile().canonicalFile.toPath().startsWith(rootPath)) {
                        "File parent escapes target: $relative"
                    }
                    target.toFile().outputStream().use { output ->
                        copyExactly(input, size, output)
                    }
                    applyMode(target, mode)
                    if (mtime > 0) target.toFile().setLastModified(mtime)
                    count++
                    onEntry(relative)
                }

                else -> skipEntry(input, size)
            }
        }
        return count
    }

    private fun applyMode(path: Path, mode: Int) {
        val file = path.toFile()
        if (mode and 0b001_000_000 != 0) file.setReadable(true, false)
        if (mode and 0b000_100_000 != 0) file.setWritable(true, true)
        if (mode and 0b000_001_000 != 0) file.setExecutable(true, false)
    }

    /** 归档内路径归一化：去掉开头的 `./`，拒绝绝对路径、`..` 与反斜杠，返回 null 表示跳过。 */
    private fun normalizeEntryPath(raw: String): String? {
        var name = raw
        while (name.startsWith("./")) name = name.removePrefix("./")
        name = name.trimStart('/')
        if (name.isBlank()) return null
        if ('\\' in name) return null
        if (name.contains('\u0000')) return null
        val segments = name.split('/')
        if (segments.any { it == ".." }) return null
        return segments.filter { it.isNotEmpty() && it != "." }.joinToString("/").ifBlank { null }
    }

    private fun resolveInside(root: Path, relative: String): Path {
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Entry escapes target: $relative" }
        return target
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            index += length
        }
        return result
    }

    private fun ByteArray.readString(offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun ByteArray.readOctal(offset: Int, length: Int): Long {
        val text = readString(offset, length).trim().trimEnd('\u0000').ifBlank { "0" }
        return text.toLongOrNull(8) ?: 0L
    }

    private fun readEntryData(input: InputStream, size: Long): ByteArray {
        require(size <= Int.MAX_VALUE) { "Tar entry too large: $size" }
        val buffer = ByteArray(size.toInt())
        var read = 0
        while (read < buffer.size) {
            val step = input.read(buffer, read, buffer.size - read)
            if (step < 0) throw IOException("Unexpected EOF in tar entry")
            read += step
        }
        skipFully(input, (BLOCK - size % BLOCK) % BLOCK)
        return buffer
    }

    private fun copyExactly(input: InputStream, size: Long, output: OutputStream) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected EOF in tar entry data")
            output.write(buffer, 0, read)
            remaining -= read
        }
        skipFully(input, (BLOCK - size % BLOCK) % BLOCK)
    }

    /** 跳过整条条目：数据区加块填充，保证下一条头部对齐。 */
    private fun skipEntry(input: InputStream, size: Long) {
        skipFully(input, size + (BLOCK - size % BLOCK) % BLOCK)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return
            remaining -= read
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val step = read(buffer, read, buffer.size - read)
            if (step < 0) return if (read == 0) false else throw IOException("Unexpected EOF in tar header")
            read += step
        }
        return true
    }
}
