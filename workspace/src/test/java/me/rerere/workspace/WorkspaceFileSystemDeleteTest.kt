package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileSystemDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileSystem = WorkspaceFileSystem()

    @Test
    fun `delete directory that contains a self referential symlink`() {
        val root = tmp.newFolder("root")
        File(root, "train/notes.md").apply { parentFile?.mkdirs() }.writeText("内容")
        Files.createSymbolicLink(File(root, "train/loop").toPath(), File(".").toPath())

        val result = fileSystem.delete(root, "train", recursive = true)

        assertTrue("含自引用链接的目录应删除成功，实际 ${result.failure}", result.deleted)
        assertFalse(File(root, "train").exists())
    }

    @Test
    fun `delete missing path reports not found`() {
        val root = tmp.newFolder("root2")
        val result = fileSystem.delete(root, "nope")
        assertFalse(result.deleted)
        assertEquals(WorkspaceDeleteFailure.NOT_FOUND, result.failure)
    }

    @Test
    fun `delete directory without recursive keeps the tree`() {
        val root = tmp.newFolder("root3")
        File(root, "dir/a.txt").apply { parentFile?.mkdirs() }.writeText("x")

        val result = fileSystem.delete(root, "dir", recursive = false)

        assertFalse(result.deleted)
        assertEquals(WorkspaceDeleteFailure.NEED_RECURSIVE, result.failure)
        assertTrue(File(root, "dir/a.txt").exists())
    }

    @Test
    fun `delete single file reports the failing entry path`() {
        val root = tmp.newFolder("root4")
        File(root, "k.txt").writeText("x")

        val result = fileSystem.delete(root, "k.txt")

        assertTrue(result.deleted)
        assertFalse(File(root, "k.txt").exists())
    }
}
