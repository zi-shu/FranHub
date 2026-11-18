package me.rerere.rikkahub.data.backup

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

/**
 * 恢复时包内工作区 root 到本地 root 的映射结果。
 *
 * [backupRoot] 是备份包清单里记录的 root。工作区 root 在创建时随机生成（UUID），
 * 跨设备必然不同。恢复时直接按包内 root 落位会把数据放进没人认领的目录。
 */
data class RootTarget(
    val backupRoot: String,
    val name: String,
    val localRoot: String,
    val isNew: Boolean,
)

/**
 * 恢复前把包内工作区 root 映射到本地 root 的纯逻辑。
 *
 * 规则：
 * 1. 包内工作区与本地 LOCAL 工作区同名 → 映射到本地 root。本地名字唯一，最多一个匹配。
 * 2. 无同名 LOCAL 工作区 → 保留包内 root，isNew = true，由调用方补数据库记录。
 * 3. SSH 工作区同名不算匹配。SSH 工作区不读本地目录，映射过去数据照样看不见。
 * 4. 新建工作区名字与本地已有名字相撞时，依次加「（恢复）」与「（恢复2）…」后缀。
 * 5. 两个包内 root 映射到同一个本地 root 时，后面的改走新建分支。
 */
object WorkspaceRestoreMapping {

    fun resolve(
        manifestWorkspaces: List<WorkspaceEntryInfo>,
        selectedRoots: Set<String>,
        localWorkspaces: List<WorkspaceEntity>,
    ): List<RootTarget> {
        val localByName = localWorkspaces
            .filterNot { it.isSsh() }
            .groupBy { it.name }
        val usedLocalRoots = mutableSetOf<String>()
        val usedNames = localWorkspaces.map { it.name }.toMutableSet()
        val result = mutableListOf<RootTarget>()
        for (entry in manifestWorkspaces) {
            if (entry.root !in selectedRoots) continue
            val matched = localByName[entry.name]?.firstOrNull()
            if (matched != null && matched.root !in usedLocalRoots) {
                usedLocalRoots += matched.root
                result += RootTarget(entry.root, matched.name, matched.root, false)
                continue
            }
            val baseName = entry.name.ifBlank { entry.root }
            var name = baseName
            var counter = 1
            while (name in usedNames) {
                name = if (counter == 1) "$baseName（恢复）" else "$baseName（恢复$counter）"
                counter++
            }
            usedNames += name
            result += RootTarget(entry.root, name, entry.root, true)
        }
        return result
    }
}
