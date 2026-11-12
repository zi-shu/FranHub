package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 子智能体配置, 按工作区保存(存 workspaces 表的 JSON 列)。
 *
 * [enabled] 关时不给主代理注入派发工具。
 * [modelId] 为空表示跟随主对话模型。
 * 四项工具权限只作用于子代理, 与工作区原有的主代理审批开关互不影响。
 */
@Serializable
data class SubagentConfig(
    val enabled: Boolean = false,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val modelId: Uuid? = null,
    val allowRead: Boolean = true,
    val allowWrite: Boolean = false,
    val allowEdit: Boolean = false,
    val allowShell: Boolean = false,
) {
    /** 收敛到合法范围, 防止手改配置或旧版本数据越界 */
    fun normalized(): SubagentConfig = copy(
        maxConcurrency = maxConcurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        timeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
        maxToolCalls = maxToolCalls.coerceIn(MIN_TOOL_CALLS, MAX_TOOL_CALLS),
    )

    companion object {
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 4
        const val DEFAULT_MAX_CONCURRENCY = 2

        const val MIN_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 3_600_000L
        const val DEFAULT_TIMEOUT_MS = 600_000L

        const val MIN_TOOL_CALLS = 1
        const val MAX_TOOL_CALLS = 200
        const val DEFAULT_MAX_TOOL_CALLS = 40

        val DEFAULT = SubagentConfig()
    }
}
