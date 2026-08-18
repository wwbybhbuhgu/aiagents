package com.aiagents.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart

/**
 * 构建 `agent` 委派工具。与宿主 Agent 的 agent 工具 1:1 对齐：
 *
 * 主 Agent 调用后立即返回任务已启动（不阻塞主生成链），子 Agent 在后台独立运行
 * （独立上下文、可执行自己的工具集），完成后由 [onSubAgentFinished] 把结果/日志
 * 写回父对话；失败则通过 [onSubAgentFailed] 回传错误。
 *
 * 若传入可选的 [intervalMillis]（周期性监控/心跳模式），子 Agent 会每隔该间隔在
 * 后台重复执行，并在每次执行后调用 [onSubAgentHeartbeat] 把本次侦测结果写回父对话，
 * 从而周期性激活主 Agent。
 */
fun buildAgentTools(
    runManager: AgentRunManager,
    parentConversationId: String,
    runSubAgent: suspend (prompt: String) -> String,
    onSubAgentFinished: suspend (AgentRun) -> Unit = {},
    onSubAgentFailed: suspend (AgentRun, Throwable) -> Unit = { _, _ -> },
    onSubAgentHeartbeat: suspend (AgentRun) -> Unit = {},
): List<Tool> = listOf(
    Tool(
        name = "agent",
        description = """
            Delegates a task to a sub-agent running in the background.
            The sub-agent has its own context and tool set; it executes independently
            and reports the result back to this conversation when finished.
            Use this to parallelize work: research, coding sub-tasks, file inspection,
            monitoring (periodic surveillance of how the user uses this device), or any
            task that can run separately while you continue the main conversation.
            The task id and completion will be reported back automatically.
            Pass a single, self-contained `prompt`; describe the goal, constraints,
            and the expected output format precisely. Do not rely on the main
            conversation context — the sub-agent cannot see it.
            To set up periodic monitoring (heartbeat), pass the `interval` parameter:
            the sub-agent will re-run itself on that cadence and post each cycle's
            findings back to this conversation so you can react to them.
            IMPORTANT for monitoring tasks: the user's stated top priority matters most
            (see your instructions). Fill all parameters yourself; only the goal comes
            from the user.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Self-contained task description for the sub-agent (goal, constraints, expected output)")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional short description of the sub-task for the report")
                    })
                    put("interval", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional periodic monitoring cadence, e.g. '30m', '2h', '1d'. Omit for a one-shot task.")
                    })
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: error("prompt is required")
            val description = params["description"]?.jsonPrimitive?.contentOrNull
                ?: prompt.take(60)
            val interval = params["interval"]?.jsonPrimitive?.contentOrNull
                ?.let { parseIntervalMillis(it) }

            if (interval == null) {
                val run = runManager.launch(
                    parentConversationId = parentConversationId,
                    prompt = prompt,
                    runBlock = { runSubAgent(prompt) },
                    onFinished = onSubAgentFinished,
                    onFailed = onSubAgentFailed,
                )
                return@Tool executeResult(run, "已启动: $description", interval = null)
            }
            val run = runManager.launchRecurring(
                parentConversationId = parentConversationId,
                prompt = prompt,
                intervalMillis = interval,
                runBlock = { runSubAgent(prompt) },
                onCycle = onSubAgentHeartbeat,
                onFailed = onSubAgentFailed,
            )
            executeResult(run, "已启动(每 ${formatInterval(interval)} 侦测一次): $description", interval)
        },
    ),
)

private fun executeResult(
    run: AgentRun,
    message: String,
    interval: Long?,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("taskId", run.id)
            put("status", "running")
            put("recurring", run.recurring)
            put("intervalMillis", interval)
            put("message", "${run.id} $message")
        }.toString()
    )
)

/** 解析 '30m'/'2h'/'1d'/'90s' 等间隔为毫秒；非法输入返回 null */
fun parseIntervalMillis(text: String): Long? {
    val trimmed = text.trim()
    val m = Regex("(\\d+)\\s*([smhdSMHD])").matchEntire(trimmed) ?: return null
    val value = m.groupValues[1].toLongOrNull() ?: return null
    val unit = m.groupValues[2].lowercase()
    val multiplier = when (unit) {
        "s" -> 1000L
        "m" -> 60_000L
        "h" -> 3_600_000L
        "d" -> 86_400_000L
        else -> return null
    }
    return value * multiplier
}

/** 格式化毫秒间隔为可读文本 */
fun formatInterval(millis: Long): String = when {
    millis % 86_400_000L == 0L -> "${millis / 86_400_000L}d"
    millis % 3_600_000L == 0L -> "${millis / 3_600_000L}h"
    millis % 60_000L == 0L -> "${millis / 60_000L}m"
    else -> "${millis / 1000L}s"
}
