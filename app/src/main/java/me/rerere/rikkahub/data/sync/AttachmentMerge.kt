package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** 附件合并统计，供预览与恢复报告使用。 */
@Serializable
data class AttachmentMergeStats(
    val imported: Int = 0,
    val skipped: Int = 0,
    val renamed: Int = 0,
)

/**
 * 增量恢复的附件端合并，在暂存的 payload/files 上就地操作：
 * 与本地同路径内容一致的条目删掉（不安装），冲突条目改名「 (n)」递增，
 * 本地没有的条目原样保留。
 *
 * upload、skills、fonts、media_creation 是固定容器目录，只递归处理它们的内部条目。
 * 内部子目录（技能目录、媒体会话目录）按整目录比较：目录内全部文件一致才算一致。
 */
object AttachmentMerge {

    fun apply(payloadFiles: File, localFilesDir: File): AttachmentMergeStats {
        var imported = 0
        var skipped = 0
        var renamed = 0
        val containers = payloadFiles.listFiles().orEmpty().filter { it.isDirectory }
        for (container in containers) {
            val targetContainer = File(localFilesDir, container.name)
            if (!targetContainer.isDirectory) {
                // 本地没有整个容器目录：全部照常安装，按文件数统计
                imported += fileCount(container)
                continue
            }
            for (entry in container.listFiles().orEmpty()) {
                val target = File(targetContainer, entry.name)
                processEntry(entry, target) { action ->
                    when (action) {
                        MergeAction.IMPORT -> imported++
                        MergeAction.SKIP -> skipped++
                        else -> renamed++
                    }
                }
            }
        }
        return AttachmentMergeStats(imported = imported, skipped = skipped, renamed = renamed)
    }

    private fun processEntry(entry: File, target: File, onAction: (MergeAction) -> Unit) {
        when {
            entry.isDirectory -> {
                if (!target.isDirectory) {
                    onAction(MergeAction.IMPORT) // 本地没有整个目录，整目录照常安装
                } else if (dirEquals(entry, target)) {
                    onAction(MergeAction.SKIP)
                    entry.deleteRecursively() // 一致：不安装，本地目录保留
                } else {
                    val newName = uniqueName(entry, target.parentFile)
                    check(entry.renameTo(File(entry.parentFile!!, newName))) {
                        "Cannot rename attachment directory ${entry.name}"
                    }
                    onAction(MergeAction.RENAME_IMPORT)
                }
            }
            entry.isFile -> {
                if (!target.isFile) {
                    onAction(MergeAction.IMPORT)
                } else if (fileEquals(entry, target)) {
                    onAction(MergeAction.SKIP)
                    check(entry.delete()) { "Cannot remove identical attachment ${entry.name}" }
                } else {
                    val newName = uniqueName(entry, target.parentFile)
                    check(entry.renameTo(File(entry.parentFile!!, newName))) {
                        "Cannot rename attachment file ${entry.name}"
                    }
                    onAction(MergeAction.RENAME_IMPORT)
                }
            }
            // 符号链接等其余类型：照常安装
        }
    }

    private fun fileCount(root: File): Int {
        var count = 0
        root.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }

    /** 目录级一致：两边文件集合（相对路径 -> sha256）完全相同。 */
    private fun dirEquals(payloadDir: File, localDir: File): Boolean {
        return hashTree(payloadDir) == hashTree(localDir)
    }

    private fun hashTree(root: File): Map<String, String> {
        val hashes = HashMap<String, String>()
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            hashes[file.relativeTo(root).invariantSeparatorsPath] = sha256(file)
        }
        return hashes
    }

    /** 文件级一致：先比大小，大小相同再比内容。 */
    private fun fileEquals(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return sha256(a) == sha256(b)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 名字加「 (n)」递增，避开本地目录与暂存目录里已占用的名字。 */
    private fun uniqueName(entry: File, targetParent: File?): String {
        val base = entry.name
        val taken = HashSet<String>()
        targetParent?.listFiles().orEmpty().forEach { taken += it.name }
        entry.parentFile?.listFiles().orEmpty().forEach { taken += it.name }
        if (base !in taken) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 2
        var candidate = "$stem ($n)$ext"
        while (candidate in taken) {
            n++
            candidate = "$stem ($n)$ext"
        }
        return candidate
    }
}
