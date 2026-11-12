package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** 子代理运行状态。消息随生成实时更新, 界面据此显示过程。 */
data class SubagentRun(
    val taskId: Uuid,
    val parentConversationId: Uuid,
    val description: String,
    val startedAt: Long,
    val messages: List<UIMessage> = emptyList(),
    val toolCallCount: Int = 0,
)

enum class SubagentStatus { RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT }

/** 任务记录。运行中与终态共用一张表, 终态保留到进程结束。 */
data class SubagentTask(
    val taskId: Uuid,
    val parentConversationId: Uuid,
    val description: String,
    val status: SubagentStatus,
    val startedAt: Long,
    val endedAt: Long? = null,
    val toolCallCount: Int = 0,
    val resultText: String? = null,
    val error: String? = null,
) {
    val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
}

/**
 * 子代理注册表, 进程内单例。
 *
 * 只负责记账与取消信号, 不执行任何生成。执行体在 SubagentRunner。
 * 主会话与子代理共用同一个实例, 按 [SubagentTask.parentConversationId] 归属。
 *
 * 0.3.0 不落库: 进程重启后任务记录清空, 子代理本身也已随进程结束。
 */
class SubagentManager {
    private val mutex = Mutex()

    private val _runs = MutableStateFlow<Map<Uuid, SubagentRun>>(emptyMap())
    val runs: StateFlow<Map<Uuid, SubagentRun>> = _runs.asStateFlow()

    private val _tasks = MutableStateFlow<Map<Uuid, SubagentTask>>(emptyMap())
    val tasks: StateFlow<Map<Uuid, SubagentTask>> = _tasks.asStateFlow()

    private val cancelSignals = HashMap<Uuid, CompletableDeferred<Unit>>()

    /**
     * 登记一个新任务。并发数达到上限时返回 null, 由调用方排队或拒绝。
     */
    suspend fun register(
        taskId: Uuid,
        parentConversationId: Uuid,
        description: String,
        maxConcurrency: Int,
    ): SubagentTask? = mutex.withLock {
        val running = _tasks.value.values.count {
            it.parentConversationId == parentConversationId && it.status == SubagentStatus.RUNNING
        }
        if (running >= maxConcurrency) return@withLock null

        val now = System.currentTimeMillis()
        val task = SubagentTask(
            taskId = taskId,
            parentConversationId = parentConversationId,
            description = description,
            status = SubagentStatus.RUNNING,
            startedAt = now,
        )
        _tasks.update { it + (taskId to task) }
        _runs.update {
            it + (taskId to SubagentRun(
                taskId = taskId,
                parentConversationId = parentConversationId,
                description = description,
                startedAt = now,
            ))
        }
        cancelSignals[taskId] = CompletableDeferred()
        task
    }

    /** 更新运行中的消息快照与工具调用数, 供界面实时显示 */
    fun updateRun(taskId: Uuid, messages: List<UIMessage>, toolCallCount: Int) {
        val run = _runs.value[taskId] ?: return
        _runs.update {
            it + (taskId to run.copy(messages = messages, toolCallCount = toolCallCount))
        }
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            map + (taskId to task.copy(toolCallCount = toolCallCount))
        }
    }

    /** 落终态, 同时注销运行记录 */
    suspend fun finish(
        taskId: Uuid,
        status: SubagentStatus,
        resultText: String? = null,
        error: String? = null,
    ) = mutex.withLock {
        val now = System.currentTimeMillis()
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            if (task.status != SubagentStatus.RUNNING) return@update map
            map + (taskId to task.copy(
                status = status,
                endedAt = now,
                resultText = resultText,
                error = error,
            ))
        }
        _runs.update { it - taskId }
        cancelSignals.remove(taskId)
    }

    fun task(taskId: Uuid): SubagentTask? = _tasks.value[taskId]

    fun tasksOf(parentConversationId: Uuid): List<SubagentTask> =
        _tasks.value.values
            .filter { it.parentConversationId == parentConversationId }
            .sortedBy { it.startedAt }

    fun runningOf(parentConversationId: Uuid): List<SubagentRun> =
        _runs.value.values
            .filter { it.parentConversationId == parentConversationId }
            .sortedBy { it.startedAt }

    fun runningCount(parentConversationId: Uuid): Int =
        _tasks.value.values.count {
            it.parentConversationId == parentConversationId && it.status == SubagentStatus.RUNNING
        }

    fun cancelSignal(taskId: Uuid): CompletableDeferred<Unit>? = synchronized(cancelSignals) {
        cancelSignals[taskId]
    }

    /** 取消单个任务, 只置信号, 终态由执行体落 */
    suspend fun cancel(taskId: Uuid): Boolean {
        val signal = mutex.withLock { cancelSignals[taskId] } ?: return false
        signal.complete(Unit)
        return true
    }

    /**
     * 取消某会话下全部运行中的子代理。主对话点停止时调用, 防止子代理继续跑。
     * 返回被置信号的任务数。
     */
    suspend fun cancelAll(parentConversationId: Uuid): Int {
        val targets = mutex.withLock {
            _tasks.value.values
                .filter { it.parentConversationId == parentConversationId && it.status == SubagentStatus.RUNNING }
                .mapNotNull { task -> cancelSignals[task.taskId]?.let { task.taskId to it } }
        }
        targets.forEach { (_, signal) -> signal.complete(Unit) }
        return targets.size
    }
}
