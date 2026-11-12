package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "FranHubSubagent"

/** 子代理最终结果的注入预算, 超出截断。父代理上下文很贵, 不能整段塞进去。 */
const val SUBAGENT_RESULT_INLINE_CHARS = 4_000

/** 子代理系统提示。要求独立完成、不打断用户、结果自包含。 */
private val SUBAGENT_SYSTEM_PROMPT = """
    You are a subagent dispatched by a main agent. Your only job is to finish the assigned subtask
    on your own and return one self-contained result.

    Rules:
    1. Do not ask the user anything and do not request confirmation. Nobody will answer.
    2. Your output is read by the main agent, not shown as a chat reply. Include every fact,
       file path and conclusion the main agent needs to continue.
    3. Return the result only. No greeting, no narration of what you are about to do.
    4. If the task cannot be done, say what blocked you and what you checked.
""".trimIndent()

/** 子代理绝不能拿到的工具: 递归派发、以及有设备副作用的本地工具 */
private val DENIED_TOOL_NAMES = setOf(
    "spawn_agent",
    "poll_agent",
    "cancel_agent",
    "list_agents",
    "calendar_create",
    "calendar_query",
    "clipboard_tool",
    "get_screen_time",
    "text_to_speech",
    "conversation_read",
    "conversation_search",
    "recent_chats",
)

data class SubagentResult(
    val status: SubagentStatus,
    val text: String,
    val toolCallCount: Int,
    val error: String? = null,
)

/**
 * 子代理执行体。
 *
 * 走与主对话完全相同的生成链路: 构造 ChatTurnContext, 由 ChatRequestFactory 组装请求,
 * runAgent 跑循环, 工具由 ChatToolFactory 生成后按配置裁剪。
 *
 * 与主对话的差别只有三处: 空历史、独立系统提示、工具集受限。
 * 用量记在父会话名下, 因为 conversationId 传的是父会话。
 */
class SubagentRunner(
    private val context: Context,
    private val toolFactory: ChatToolFactory,
    private val requestFactory: ChatRequestFactory,
    private val manager: SubagentManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * 父代理能不能读到 /tool_outputs。
     * 只有本地 Rootfs 工作区挂了这个目录, SSH 工作区与未装 Rootfs 的工作区都读不到。
     */
    private suspend fun canParentReadSpill(assistant: Assistant): Boolean {
        val workspaceId = assistant.workspaceId?.toString() ?: return false
        val workspace = workspaceRepository.getById(workspaceId) ?: return false
        return workspace.capabilities().rootfs &&
            workspace.shellStatus == WorkspaceShellStatus.READY.name
    }
    suspend fun run(
        taskId: Uuid,
        parentConversationId: Uuid,
        settings: Settings,
        assistant: Assistant,
        parentModel: Model,
        workspaceCwd: String?,
        config: SubagentConfig,
        prompt: String,
        timeoutMs: Long,
        maxToolCalls: Int,
    ): SubagentResult {
        val childModel = config.modelId
            ?.let { settings.findModelById(it) }
            ?: parentModel
        val providerSetting = childModel.findProvider(settings.providers)
            ?: return fail("Provider not found for model ${childModel.id}")

        val childAssistant = assistant.copy(
            systemPrompt = SUBAGENT_SYSTEM_PROMPT,
            enableMemory = false,
            allowConversationSystemPrompt = false,
        )

        // conversationId 传 null: 子代理不注入派发工具, 递归到此为止
        val childTools = toolFactory.createTools(
            settings = settings,
            assistant = childAssistant,
            model = childModel,
            workspaceCwd = workspaceCwd,
            conversationId = null,
        ).filterByConfig(config)

        val turn = ChatTurnContext(
            settings = settings,
            assistant = childAssistant,
            model = childModel,
            providerSetting = providerSetting,
            conversationId = parentConversationId,
            conversationSystemPrompt = null,
            workspaceCwd = workspaceCwd,
            memories = emptyList(),
            tools = childTools,
        )

        val userMessage = UIMessage.user(prompt)
        val (request, hooks) = requestFactory.createAgent(
            turn = turn,
            messages = listOf(userMessage),
            processingStatus = MutableStateFlow(null),
        )

        var latest: UIMessage? = null
        var cancelledBySignal = false
        val cancelSignal = manager.cancelSignal(taskId)

        val result = try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val scope = this
                    val watcher = launch {
                        cancelSignal?.await()
                        cancelledBySignal = true
                        scope.cancel()
                    }
                    try {
                        runAgent(request.copy(maxSteps = maxToolCalls.coerceAtLeast(1)), hooks)
                            .collect { event ->
                                when (event) {
                                    is AgentEvent.MessageUpdated -> {
                                        latest = event.message
                                        manager.updateRun(
                                            taskId = taskId,
                                            messages = listOf(userMessage, event.message),
                                            toolCallCount = event.message.getTools().size,
                                        )
                                    }

                                    is AgentEvent.Finished -> latest = event.message
                                    is AgentEvent.Retrying -> Unit
                                }
                            }
                        val message = latest
                        SubagentResult(
                            status = SubagentStatus.COMPLETED,
                            text = message?.toText().orEmpty(),
                            toolCallCount = message?.getTools()?.size ?: 0,
                        )
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            SubagentResult(
                status = SubagentStatus.TIMEOUT,
                text = latest?.toText().orEmpty(),
                toolCallCount = latest?.getTools()?.size ?: 0,
                error = "timed out after ${timeoutMs}ms",
            )
        } catch (e: CancellationException) {
            if (!cancelledBySignal) {
                // 父协程被取消(停止生成、切换会话等)。这里必须落终态,
                // 否则任务永远停在 RUNNING, 界面的运行条会一直亮着且取消不掉。
                withContext(NonCancellable) {
                    manager.finish(
                        taskId = taskId,
                        status = SubagentStatus.CANCELLED,
                        resultText = latest?.toText().orEmpty(),
                        error = "cancelled with parent",
                    )
                }
                throw e
            }
            SubagentResult(
                status = SubagentStatus.CANCELLED,
                text = latest?.toText().orEmpty(),
                toolCallCount = latest?.getTools()?.size ?: 0,
                error = "cancelled",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "subagent failed", e)
            SubagentResult(
                status = SubagentStatus.FAILED,
                text = latest?.toText().orEmpty(),
                toolCallCount = latest?.getTools()?.size ?: 0,
                error = e.message ?: e.javaClass.simpleName,
            )
        }

        // 超长结果落盘, 父代理只拿到截断预览与文件路径。
        // 父代理读不到 /tool_outputs 时(没有工作区、SSH 工作区、Rootfs 未就绪)只能全文内联。
        val finalResult = result.copy(
            text = spillIfTooLong(taskId, result.text, canParentReadSpill(assistant)),
        )

        // 终态在这里落, 工具层只读结果
        manager.finish(
            taskId = taskId,
            status = finalResult.status,
            resultText = finalResult.text,
            error = finalResult.error,
        )
        return finalResult
    }

    /**
     * 结果超过内联预算时写进 /tool_outputs, 返回截断预览加读取提示。
     * 父代理上下文很贵, 不能把整段子代理输出塞进去。
     */
    private fun spillIfTooLong(taskId: Uuid, text: String, canRead: Boolean): String {
        if (text.length <= SUBAGENT_RESULT_INLINE_CHARS) return text
        // 落盘等于丢结果, 宁可多占上下文
        if (!canRead) return text
        val fileName = "subagent_$taskId.md"
        val output = runCatching {
            val dir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            File(dir, fileName).writeText(text)
        }.isSuccess
        return buildString {
            appendLine("[Subagent output truncated: ${text.length} characters]")
            if (output) {
                appendLine("Full output saved to: /tool_outputs/$fileName")
                appendLine("Read it with workspace_read_file, or: cat /tool_outputs/$fileName")
            } else {
                appendLine("Full output could not be saved.")
            }
            appendLine()
            append(text.take(SUBAGENT_RESULT_INLINE_CHARS))
        }
    }

    private fun fail(message: String): SubagentResult =
        SubagentResult(SubagentStatus.FAILED, "", 0, message)

    /**
     * 按配置裁剪工具: 先砍设备类与派发类, 再按四项权限开关过滤工作区工具。
     *
     * 子代理没有界面, 弹审批会把它卡在 AwaitingApproval 上原地结束。
     * 所以白名单内的工具一律免审批, 白名单外直接不给。取舍在配置里做, 不在运行时做。
     */
    private fun List<Tool>.filterByConfig(config: SubagentConfig): List<Tool> = filter { tool ->
        val name = tool.name
        when {
            name in DENIED_TOOL_NAMES -> false
            name.startsWith("mcp__") -> false
            name == "workspace_read_file" -> config.allowRead
            name == "workspace_write_file" -> config.allowWrite
            name == "workspace_edit_file" -> config.allowEdit
            name == "workspace_shell" -> config.allowShell
            else -> true
        }
    }.map { tool -> tool.copy(needsApproval = { false }) }
}
