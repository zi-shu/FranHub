package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AttachmentMergeTest {

    private fun dir(): java.io.File = Files.createTempDirectory("attach-merge-test-").toFile()

    private fun write(file: java.io.File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `本地没有的条目 全部导入`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "upload/a.txt"), "hello")
        write(java.io.File(payload, "fonts/f.ttf"), "font")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(2, stats.imported)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.renamed)
        assertTrue(java.io.File(payload, "upload/a.txt").isFile)
        assertTrue(java.io.File(payload, "fonts/f.ttf").isFile)
    }

    @Test
    fun `内容一致的文件 跳过并从暂存删除`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "upload/a.txt"), "same")
        write(java.io.File(local, "upload/a.txt"), "same")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(1, stats.skipped)
        assertFalse(java.io.File(payload, "upload/a.txt").exists())
        assertTrue(java.io.File(local, "upload/a.txt").isFile)
    }

    @Test
    fun `内容不同的文件 改名加序号`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "upload/a.txt"), "backup")
        write(java.io.File(local, "upload/a.txt"), "local")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(1, stats.renamed)
        assertTrue(java.io.File(local, "upload/a.txt").isFile)
        assertTrue(java.io.File(payload, "upload/a (2).txt").isFile)
        assertEquals("backup", java.io.File(payload, "upload/a (2).txt").readText())
    }

    @Test
    fun `目录级一致 整个目录跳过`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "skills/myskill/SKILL.md"), "s1")
        write(java.io.File(payload, "skills/myskill/tool.py"), "p1")
        write(java.io.File(local, "skills/myskill/SKILL.md"), "s1")
        write(java.io.File(local, "skills/myskill/tool.py"), "p1")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(1, stats.skipped)
        assertFalse(java.io.File(payload, "skills/myskill").exists())
        assertTrue(java.io.File(local, "skills/myskill/tool.py").isFile)
    }

    @Test
    fun `目录内容不同 目录改名`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "skills/myskill/SKILL.md"), "backup")
        write(java.io.File(local, "skills/myskill/SKILL.md"), "local")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(1, stats.renamed)
        assertTrue(java.io.File(payload, "skills/myskill (2)/SKILL.md").isFile)
        assertEquals("backup", java.io.File(payload, "skills/myskill (2)/SKILL.md").readText())
        assertTrue(java.io.File(local, "skills/myskill/SKILL.md").isFile)
    }

    @Test
    fun `改名序号避让本地已占用的名字`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "upload/a.txt"), "backup")
        write(java.io.File(local, "upload/a.txt"), "local")
        write(java.io.File(local, "upload/a (2).txt"), "local2")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(1, stats.renamed)
        assertTrue(java.io.File(payload, "upload/a (3).txt").isFile)
    }

    @Test
    fun `本地没有的目录 整目录导入不动`() {
        val payload = dir()
        val local = dir()
        write(java.io.File(payload, "media_creation/abc/1.png"), "img")

        val stats = AttachmentMerge.apply(payload, local)

        assertEquals(0, stats.renamed)
        assertEquals(0, stats.skipped)
        assertTrue(java.io.File(payload, "media_creation/abc/1.png").isFile)
    }
}
