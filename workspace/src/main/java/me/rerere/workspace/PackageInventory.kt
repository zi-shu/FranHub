package me.rerere.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
data class PackageEntry(
    val name: String,
    val version: String,
)

/**
 * 包管理器清单：记录 Rootfs 里由各包管理器装进来的软件名称与版本。
 *
 * 只读文件，不启动 proot，不执行命令。备份带上它，恢复后用户能照着清单把软件装回去。
 * 解析失败一律返回空列表，不阻断备份。
 */
@Serializable
data class PackageInventory(
    val apk: List<PackageEntry> = emptyList(),
    val dpkg: List<PackageEntry> = emptyList(),
    val pip: List<PackageEntry> = emptyList(),
    val npm: List<PackageEntry> = emptyList(),
) {
    val total: Int get() = apk.size + dpkg.size + pip.size + npm.size

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 不进入的目录：伪文件系统、缓存与临时区，扫了也没有包元数据。 */
        private val PRUNED = setOf(
            "proc", "sys", "dev", "tmp", "cache", ".cache", "_cacache", ".git", ".npm",
        )
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_DEPTH = 16

        fun collect(linuxDir: File): PackageInventory {
            if (!linuxDir.isDirectory) return PackageInventory()
            return PackageInventory(
                apk = readApk(File(linuxDir, "lib/apk/db/installed")),
                dpkg = readDpkg(File(linuxDir, "var/lib/dpkg/status")),
                pip = readPip(linuxDir),
                npm = readNpm(linuxDir),
            )
        }

        internal fun readApk(file: File): List<PackageEntry> {
            val text = readTextOrNull(file) ?: return emptyList()
            return text.split("\n\n")
                .mapNotNull { record ->
                    val name = record.lineValue("P:")
                    val version = record.lineValue("V:")
                    if (name != null && version != null) PackageEntry(name, version) else null
                }
                .distinctByName()
        }

        internal fun readDpkg(file: File): List<PackageEntry> {
            val text = readTextOrNull(file) ?: return emptyList()
            return text.split("\n\n")
                .mapNotNull { record ->
                    // 只收已安装的包；deinstall ok config-files 这类只剩配置的不算
                    val status = record.lineValue("Status:") ?: return@mapNotNull null
                    if (!status.contains(" installed")) return@mapNotNull null
                    val name = record.lineValue("Package:") ?: return@mapNotNull null
                    val version = record.lineValue("Version:") ?: return@mapNotNull null
                    PackageEntry(name, version)
                }
                .distinctByName()
        }

        internal fun parsePipMetadata(text: String): PackageEntry? {
            var name: String? = null
            var version: String? = null
            for (line in text.lineSequence()) {
                if (line.isBlank()) break
                when {
                    line.startsWith("Name:") && name == null -> name = line.removePrefix("Name:").trim()
                    line.startsWith("Version:") && version == null ->
                        version = line.removePrefix("Version:").trim()
                }
            }
            return if (name != null && version != null) PackageEntry(name, version) else null
        }

        internal fun parseNpmPackageJson(text: String): PackageEntry? = runCatching {
            val obj = JSON.parseToJsonElement(text).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val version = obj["version"]?.jsonPrimitive?.content ?: return null
            PackageEntry(name, version)
        }.getOrNull()

        private fun readPip(linuxDir: File): List<PackageEntry> =
            collectDirectories(linuxDir) { it.name.endsWith(".dist-info") }
                .mapNotNull { dir -> readTextOrNull(File(dir, "METADATA"))?.let(::parsePipMetadata) }
                .distinctByName()
                .sortedBy { it.name }

        private fun readNpm(linuxDir: File): List<PackageEntry> =
            collectDirectories(linuxDir, descendInto = { it.name != "node_modules" }) {
                it.name == "node_modules"
            }
                .flatMap { modules ->
                    modules.listFiles().orEmpty()
                        .filter { it.isDirectory }
                        .mapNotNull { dir ->
                            readTextOrNull(File(dir, "package.json"))?.let(::parseNpmPackageJson)
                        }
                }
                .distinctByName()
                .sortedBy { it.name }

        /**
         * 逐层遍历目录，命中 [predicate] 时收进结果，命中后不再深入该目录。
         * [descendInto] 返回 false 的目录不深入，用来避免钻进 node_modules 的依赖树。
         */
        private fun collectDirectories(
            root: File,
            descendInto: (File) -> Boolean = { true },
            predicate: (File) -> Boolean,
        ): List<File> {
            val result = mutableListOf<File>()
            fun visit(directory: File, depth: Int) {
                if (depth > MAX_DEPTH) return
                val children = directory.listFiles() ?: return
                for (child in children) {
                    if (!child.isDirectory) continue
                    if (child.name in PRUNED) continue
                    if (predicate(child)) {
                        result.add(child)
                        continue
                    }
                    if (descendInto(child)) visit(child, depth + 1)
                }
            }
            visit(root, 0)
            return result
        }

        private fun String.lineValue(prefix: String): String? =
            lineSequence().firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.trim()?.ifBlank { null }

        private fun List<PackageEntry>.distinctByName(): List<PackageEntry> =
            distinctBy { it.name.lowercase() }

        private fun readTextOrNull(file: File): String? {
            if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
            return runCatching { file.readText() }.getOrNull()
        }
    }
}
