package me.rerere.rikkahub.data.backup

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复前包内 root 到本地 root 的映射测试。
 *
 * 工作区 root 在创建时随机生成，跨设备必然不同。恢复必须按名字映射到本地 root，
 * 否则数据落进孤儿目录，本地工作区保持原样。见 v0.3.4 修复。
 */
class WorkspaceRestoreMappingTest {

    private fun local(id: String, name: String, type: String = WorkspaceType.LOCAL.name) =
        WorkspaceEntity(
            id = id,
            name = name,
            root = id,
            createdAt = 0L,
            updatedAt = 0L,
            type = type,
        )

    private fun entry(root: String, name: String) =
        WorkspaceEntryInfo(root = root, name = name, hasFiles = true, hasLinux = true)

    @Test
    fun `同名 LOCAL 工作区映射到本地 root`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("backup-uuid", "all")),
            selectedRoots = setOf("backup-uuid"),
            localWorkspaces = listOf(local("local-uuid", "all")),
        )
        assertEquals(1, targets.size)
        val target = targets.first()
        assertEquals("backup-uuid", target.backupRoot)
        assertEquals("local-uuid", target.localRoot)
        assertFalse(target.isNew)
    }

    @Test
    fun `无同名工作区时保留包内 root 并标记新建`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("backup-uuid", "all")),
            selectedRoots = setOf("backup-uuid"),
            localWorkspaces = listOf(local("local-uuid", "other")),
        )
        assertEquals(1, targets.size)
        val target = targets.first()
        assertEquals("backup-uuid", target.backupRoot)
        assertEquals("backup-uuid", target.localRoot)
        assertEquals("all", target.name)
        assertTrue(target.isNew)
    }

    @Test
    fun `新建工作区名字与本地相撞时加后缀`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("backup-uuid", "all")),
            selectedRoots = setOf("backup-uuid"),
            localWorkspaces = listOf(local("local-uuid", "all", WorkspaceType.SSH.name)),
        )
        assertEquals(1, targets.size)
        assertEquals("all（恢复）", targets.first().name)
        assertTrue(targets.first().isNew)
    }

    @Test
    fun `SSH 工作区同名不算匹配`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("backup-uuid", "all")),
            selectedRoots = setOf("backup-uuid"),
            localWorkspaces = listOf(local("ssh-uuid", "all", WorkspaceType.SSH.name)),
        )
        assertEquals(1, targets.size)
        val target = targets.first()
        assertEquals("backup-uuid", target.localRoot)
        assertTrue(target.isNew)
    }

    @Test
    fun `多工作区混合匹配与新建`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("u1", "all"), entry("u2", "lab")),
            selectedRoots = setOf("u1", "u2"),
            localWorkspaces = listOf(local("l1", "all")),
        )
        val byRoot = targets.associateBy { it.backupRoot }
        assertEquals(2, byRoot.size)
        assertEquals("l1", byRoot.getValue("u1").localRoot)
        assertFalse(byRoot.getValue("u1").isNew)
        assertEquals("u2", byRoot.getValue("u2").localRoot)
        assertEquals("lab", byRoot.getValue("u2").name)
        assertTrue(byRoot.getValue("u2").isNew)
    }

    @Test
    fun `未勾选的 root 不进结果`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("u1", "all"), entry("u2", "lab")),
            selectedRoots = setOf("u1"),
            localWorkspaces = emptyList(),
        )
        assertEquals(1, targets.size)
        assertEquals("u1", targets.first().backupRoot)
    }

    @Test
    fun `两个包内 root 同名时后者改走新建分支`() {
        val targets = WorkspaceRestoreMapping.resolve(
            manifestWorkspaces = listOf(entry("u1", "all"), entry("u2", "all")),
            selectedRoots = setOf("u1", "u2"),
            localWorkspaces = listOf(local("l1", "all")),
        )
        val byRoot = targets.associateBy { it.backupRoot }
        assertEquals("l1", byRoot.getValue("u1").localRoot)
        assertFalse(byRoot.getValue("u1").isNew)
        assertEquals("u2", byRoot.getValue("u2").localRoot)
        assertTrue(byRoot.getValue("u2").isNew)
        assertTrue(byRoot.getValue("u2").name.startsWith("all（恢复）"))
    }
}
