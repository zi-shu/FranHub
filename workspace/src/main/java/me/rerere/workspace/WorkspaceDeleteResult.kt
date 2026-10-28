package me.rerere.workspace

/** 删除失败的原因。界面按它显示一句人话，日志里记同一套枚举便于统计。 */
enum class WorkspaceDeleteFailure {
    /** 路径为空，或解析后越出工作区。 */
    INVALID_PATH,

    /** 目标已经不存在。 */
    NOT_FOUND,

    /** 目标是目录，但调用方没有要求递归删除。 */
    NEED_RECURSIVE,

    /** 没有权限（EACCES / EPERM），常见是父目录没有写位。 */
    PERMISSION_DENIED,

    /** 文件系统只读（EROFS）。 */
    READ_ONLY,

    /** 目录非空（ENOTEMPTY），通常是子项删不掉。 */
    NOT_EMPTY,

    /** 被占用（EBUSY）。 */
    BUSY,

    /** 路径过长（ENAMETOOLONG）。 */
    NAME_TOO_LONG,

    /** 其他 IO 错误。 */
    IO,

    /** 未知。 */
    UNKNOWN,
}

/**
 * 一次删除的结果。
 *
 * [failedPath] 是相对工作区根目录的路径，指向真正失败的那一项，不是调用方传进来的父目录。
 */
data class WorkspaceDeleteResult(
    val deleted: Boolean,
    val failure: WorkspaceDeleteFailure? = null,
    val failedPath: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun success(): WorkspaceDeleteResult = WorkspaceDeleteResult(true)

        fun failure(
            reason: WorkspaceDeleteFailure,
            path: String?,
            detail: String? = null,
        ): WorkspaceDeleteResult = WorkspaceDeleteResult(
            deleted = false,
            failure = reason,
            failedPath = path,
            detail = detail,
        )
    }
}
