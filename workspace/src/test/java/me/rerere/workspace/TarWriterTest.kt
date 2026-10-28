package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TarWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleTree(): File {
        val source = tmp.newFolder("files")
        File(source, "note.txt").writeText("工作区说明")
        File(source, "sub").mkdirs()
        File(source, "sub/data.bin").writeBytes(ByteArray(3000) { (it % 97).toByte() })
        File(source, "empty").mkdirs()
        // 自引用软链接：旧统计函数会跟着它无限递归，写入端必须只记一条链接
        Files.createSymbolicLink(File(source, "self").toPath(), File(".").toPath())
        return source
    }

    @Test
    fun `writer prefixes entries and manifest stays at archive head`() {
        val source = sampleTree()
        val archive = ByteArrayOutputStream()
        val writer = TarArchive.TarWriter(archive)
        writer.bytes("workspaces/manifest.json", """{"version":1}""".toByteArray())
        writer.directoryTree(source, "workspaces/demo/files", filter = { it != "tmp" })
        writer.finish()

        val bytes = archive.toByteArray()
        // 清单在包头，readEntry 只读开头几块即可拿到
        val manifest = TarArchive.readEntry(bytes.inputStream(), "workspaces/manifest.json")
        assertEquals("""{"version":1}""", manifest?.toString(Charsets.UTF_8))

        val target = tmp.newFolder("out")
        var extracted = 0
        bytes.inputStream().use { input ->
            extracted = TarArchive.extract(
                input = input,
                targetDir = target,
                filter = { path -> path.startsWith("workspaces/demo/files") },
                onEntry = {},
            )
        }
        assertEquals("工作区说明", File(target, "workspaces/demo/files/note.txt").readText())
        assertEquals(3000L, File(target, "workspaces/demo/files/sub/data.bin").length())
        assertTrue(File(target, "workspaces/demo/files/empty").isDirectory)
        assertTrue(Files.isSymbolicLink(File(target, "workspaces/demo/files/self").toPath()))
        assertTrue("提取条目数应包含 self 链接，实际 $extracted", extracted >= 5)
        assertTrue("清单不应被解出来", !File(target, "workspaces/manifest.json").exists())
    }

    @Test
    fun `self referential symlink does not multiply entries`() {
        val source = tmp.newFolder("tree")
        repeat(20) { File(source, "f$it.txt").writeText("x") }
        Files.createSymbolicLink(File(source, "loop").toPath(), File(".").toPath())

        val archive = ByteArrayOutputStream()
        val writer = TarArchive.TarWriter(archive)
        writer.directoryTree(source, "ws/root/files")
        writer.finish()

        val target = tmp.newFolder("out2")
        val count = archive.toByteArray().inputStream().use { TarArchive.extract(it, target) }
        // 20 个文件 + 1 条软链接，绝不能因为 loop 变成上千条
        assertEquals(21, count)
    }
}
