package me.rerere.rikkahub.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * `WorkspaceBackup.apply()` 的回归测试。
 *
 * 重点是 rootfs 里的自引用软链接：`/usr/bin/X11 -> .`。旧实现用 `walkTopDown()` 拷贝，
 * 会跟着链接把同一棵树反复拷，增量合并在启动阶段直接失败。
 */
class WorkspaceBackupApplyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildStaged(pending: File, root: String, mode: WorkspaceRestoreMode): File {
        val staged = File(pending, WorkspaceBackup.WORKSPACES_DIR)
        val rootDir = File(staged, root)

        File(rootDir, "files/note.txt").apply { parentFile?.mkdirs() }.writeText("来自备份")
        File(rootDir, "files/run.sh").apply {
            writeText("#!/bin/sh\necho hi\n")
            setExecutable(true, false)
        }

        val linux = File(rootDir, "linux")
        File(linux, "usr/bin").mkdirs()
        File(linux, "usr/bin/dash").writeText("#!/bin/sh\n")
        Files.createSymbolicLink(File(linux, "bin").toPath(), File("usr/bin").toPath())
        Files.createSymbolicLink(File(linux, "usr/bin/sh").toPath(), File("dash").toPath())
        // 自引用：旧实现会在这里无限递归
        Files.createSymbolicLink(File(linux, "usr/bin/X11").toPath(), File(".").toPath())

        WorkspaceBackup.writeOptions(staged, mode)
        return pending
    }

    @Test
    fun `merge copies tree without following the self referential symlink`() {
        val filesDir = tmp.newFolder("files-dir")
        val pending = tmp.newFolder("pending")
        buildStaged(pending, "all", WorkspaceRestoreMode.MERGE)

        // 合并模式要保留包内没有的旧文件
        File(filesDir, "workspaces/all/files/local.txt").apply { parentFile?.mkdirs() }.writeText("本地")

        WorkspaceBackup.apply(pending, filesDir)

        val files = File(filesDir, "workspaces/all/files")
        assertEquals("来自备份", File(files, "note.txt").readText())
        assertEquals("本地", File(files, "local.txt").readText())
        // 合并拷贝必须保留可执行位，rootfs 里的 /usr/bin/env 就靠它
        assertTrue("合并后 run.sh 丢掉可执行位", File(files, "run.sh").canExecute())

        val linux = File(filesDir, "workspaces/all/linux")
        assertTrue(Files.isSymbolicLink(File(linux, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(linux, "bin").toPath()).toString())
        assertTrue(Files.isSymbolicLink(File(linux, "usr/bin/X11").toPath()))
        assertEquals(".", Files.readSymbolicLink(File(linux, "usr/bin/X11").toPath()).toString())
        // bin/sh 在宿主机上解析不到 dash，但链接本身必须在，Rootfs 判定靠它
        assertTrue(Files.isSymbolicLink(File(linux, "bin/sh").toPath()))
    }

    @Test
    fun `replace swaps directory and keeps a rollback copy`() {
        val filesDir = tmp.newFolder("files-dir-replace")
        val pending = tmp.newFolder("pending-replace")
        buildStaged(pending, "all", WorkspaceRestoreMode.REPLACE)

        File(filesDir, "workspaces/all/files/old.txt").apply { parentFile?.mkdirs() }.writeText("旧文件")

        WorkspaceBackup.apply(pending, filesDir)

        val files = File(filesDir, "workspaces/all/files")
        assertEquals("来自备份", File(files, "note.txt").readText())
        assertFalse("全盘替换后旧文件不应留在活动目录", File(files, "old.txt").exists())
        val original = File(pending, "workspaces/all/files-original")
        assertEquals("旧文件", File(original, "old.txt").readText())
    }
}
