package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentConfig
import me.rerere.rikkahub.data.ai.subagent.SubagentManager
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentStatus
import me.rerere.rikkahub.data.ai.subagent.SubagentTask
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

const val SPAWN_AGENT_TOOL_NAME = "spawn_agent"
const val POLL_AGENT_TOOL_NAME = "poll_agent"
const val CANCEL_AGENT_TOOL_NAME = "cancel_agent"
const val LIST_AGENTS_TOOL_NAME = "list_agents"

private const val TASK_NOT_FOUND = "AGENT_TASK_NOT_FOUND"

/**
 * 子代理工具集。只由 ChatToolFactory 在主会话里创建, 子代理自身拿不到, 递归在这里被切断。
 */
fun createSubagentTools(
    config: SubagentConfig,
    settings: Settings,
    assistant: Assistant,
    model: Model,
    workspaceCwd: String?,
    conversationId: Uuid,
    manager: SubagentManager,
    runner: SubagentRunner,
    scope: CoroutineScope,
): List<Tool> {
    val spawn = Tool(
        name = SPAWN_AGENT_TOOL_NAME,
        description = """
            Dispatch a self-contained subtask to a fresh child agent.
            The child starts with empty history, shares the workspace, and runs with a restricted tool set.
            It cannot dispatch further children.

            Use it only when the subtask is genuinely independent and large enough to need many tool calls,
            or when the user explicitly asks for subagents. Do not use it for a single lookup, a small edit,
            or work you can finish in one or two tool calls.

            async=false (default) blocks until the child finishes and returns the full result.
            async=true returns {taskId, status:"running"} immediately and keeps working in parallel;
            fetch the result later with poll_agent, stop it with cancel_agent.

            Result JSON: status (completed | failed | timeout | cancelled), result, toolCalls, taskId, durationMs, error.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Short label shown in the UI")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Self-contained instructions. Include every file path, constraint and expected output format.")
                    })
                    put("async", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true returns a taskId immediately and runs in parallel. Default false.")
                    })
                    put("timeoutMs", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional hard timeout for this child. Defaults to the workspace setting.")
                    })
                    put("maxToolCalls", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional cap on the child's tool call round trips.")
                    })
                },
                required = listOf("description", "prompt"),
            )
        },
        execute = { args ->
            val obj = args.asObject()
            val description = obj.string("description").ifBlank { "subtask" }
            val prompt = obj.string("prompt")
            if (prompt.isBlank()) {
                errorResult("spawn_agent requires a non-empty prompt")
            } else {
                val async = obj["async"]?.jsonPrimitive?.booleanOrNull ?: false
                val timeoutMs = (obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: config.timeoutMs)
                    .coerceIn(SubagentConfig.MIN_TIMEOUT_MS, SubagentConfig.MAX_TIMEOUT_MS)
                val maxToolCalls = (obj["maxToolCalls"]?.jsonPrimitive?.intOrNull ?: config.maxToolCalls)
                    .coerceIn(SubagentConfig.MIN_TOOL_CALLS, SubagentConfig.MAX_TOOL_CALLS)

                val taskId = Uuid.random()
                val task = manager.register(
                    taskId = taskId,
                    parentConversationId = conversationId,
                    description = description,
                    maxConcurrency = config.maxConcurrency,
                )
                if (task == null) {
                    errorResult(
                        "Too many subagents are already running for this conversation " +
                            "(limit ${config.maxConcurrency}). Wait for one to finish, or poll it with poll_agent."
                    )
                } else if (async) {
                    scope.launch(Dispatchers.IO) {
                        runner.run(
                            taskId = taskId,
                            parentConversationId = conversationId,
                            settings = settings,
                            assistant = assistant,
                            parentModel = model,
                            workspaceCwd = workspaceCwd,
                            config = config,
                            prompt = prompt,
                            timeoutMs = timeoutMs,
                            maxToolCalls = maxToolCalls,
                        )
                    }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("taskId", taskId.toString())
                        put("status", "running")
                        put("description", description)
                    }.toString()))
                } else {
                    val result = runner.run(
                        taskId = taskId,
                        parentConversationId = conversationId,
                        settings = settings,
                        assistant = assistant,
                        parentModel = model,
                        workspaceCwd = workspaceCwd,
                        config = config,
                        prompt = prompt,
                        timeoutMs = timeoutMs,
                        maxToolCalls = maxToolCalls,
                    )
                    listOf(UIMessagePart.Text(resultJson(taskId, task, result.status, result.text, result.toolCallCount, result.error)))
                }
            }
        },
    )

    val poll = Tool(
        name = POLL_AGENT_TOOL_NAME,
        description = """
            Check a subagent spawned with async=true.
            Returns status (running | completed | failed | cancelled | timeout), toolCalls, durationMs,
            and the result once it finished.
            Unknown taskId returns $TASK_NOT_FOUND.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("taskId", buildJsonObject {
                        put("type", "string")
                        put("description", "taskId returned by spawn_agent(async=true)")
                    })
                },
                required = listOf("taskId"),
            )
        },
        execute = { args ->
            val taskId = args.asObject().string("taskId").let { runCatching { Uuid.parse(it) }.getOrNull() }
            val task = taskId?.let { manager.task(it) }
            if (task == null) {
                errorResult(TASK_NOT_FOUND)
            } else {
                listOf(UIMessagePart.Text(task.toJson()))
            }
        },
    )

    val cancel = Tool(
        name = CANCEL_AGENT_TOOL_NAME,
        description = """
            Cancel a running subagent. It stops producing new work and ends with status=cancelled.
            Finished tasks cannot be cancelled.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("taskId", buildJsonObject {
                        put("type", "string")
                        put("description", "taskId returned by spawn_agent")
                    })
                },
                required = listOf("taskId"),
            )
        },
        execute = { args ->
            val taskId = args.asObject().string("taskId").let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (taskId == null) {
                errorResult(TASK_NOT_FOUND)
            } else {
                val ok = manager.cancel(taskId)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("taskId", taskId.toString())
                    put("cancelled", ok)
                    if (!ok) put("reason", "task not running or not found")
                }.toString()))
            }
        },
    )

    val list = Tool(
        name = LIST_AGENTS_TOOL_NAME,
        description = "List subagents dispatched in this conversation: taskId, description, status, toolCalls, durationMs.",
        parameters = { null },
        execute = {
            val tasks = manager.tasksOf(conversationId)
            val array = tasks.joinToString(prefix = "[", postfix = "]") { it.toJson() }
            listOf(UIMessagePart.Text("""{"count":${tasks.size},"tasks":$array}"""))
        },
    )

    return listOf(spawn, poll, cancel, list)
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private fun errorResult(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("status", "error")
        put("error", message)
    }.toString()))

private fun resultJson(
    taskId: Uuid,
    task: SubagentTask,
    status: SubagentStatus,
    text: String,
    toolCalls: Int,
    error: String?,
): String = buildJsonObject {
    put("taskId", taskId.toString())
    put("status", status.name.lowercase())
    put("result", text)
    put("toolCalls", toolCalls)
    put("durationMs", System.currentTimeMillis() - task.startedAt)
    error?.let { put("error", it) }
}.toString()

private fun SubagentTask.toJson(): String = buildJsonObject {
    put("taskId", taskId.toString())
    put("status", status.name.lowercase())
    put("description", description)
    put("toolCalls", toolCallCount)
    put("durationMs", durationMs)
    resultText?.let { put("result", it) }
    error?.let { put("error", it) }
}.toString()
