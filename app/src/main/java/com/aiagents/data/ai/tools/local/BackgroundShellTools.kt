package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 后台进程工具集:
 * - shell_bg: 后台执行 shell 命令, 返回进程 ID
 * - shell_output: 读取后台 shell 的输出
 * - shell_kill: 终止后台 shell
 * - shell_list: 列出所有后台进程
 */
internal fun buildBackgroundShellTools(
    manager: BackgroundProcessManager,
    workspaceDir: () -> File?,
): List<Tool> = listOf(

    // ── shell_bg: 后台执行 shell 命令 ──
    Tool(
        name = "shell_bg",
        description = "Run a shell command in the background. Returns a process ID. Use shell_output to read output, shell_kill to terminate.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to execute in background")
                    })
                },
                required = listOf("command")
            )
        },
        execute = { args ->
            val cmd = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
            if (cmd.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"command is required"}"""))
            } else {
                val id = manager.start("shell", cmd, workspaceDir())
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("id", id)
                    put("message", "Background shell started. Use shell_output('$id') to read output.")
                }.toString()))
            }
        }
    ),

    // ── shell_output: 读取后台 shell 输出 ──
    Tool(
        name = "shell_output",
        description = "Read stdout/stderr from a background shell process.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Process ID from shell_bg")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (id.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"id is required"}"""))
            } else {
                val output = manager.readOutput(id)
                if (output == null) {
                    listOf(UIMessagePart.Text("""{"error":"Process $id not found"}"""))
                } else {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("running", JsonPrimitive(output["running"] as Boolean))
                        output["exitCode"]?.let { put("exitCode", JsonPrimitive(it as Int)) }
                        put("stdout", JsonPrimitive(output["stdout"] as String))
                        put("stderr", JsonPrimitive(output["stderr"] as String))
                    }.toString()))
                }
            }
        }
    ),

    // ── shell_kill: 终止后台 shell ──
    Tool(
        name = "shell_kill",
        description = "Kill a background shell process.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Process ID from shell_bg")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (id.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"id is required"}"""))
            } else {
                val ok = manager.kill(id)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", JsonPrimitive(ok))
                    put("message", if (ok) "Process $id killed" else "Process $id not found")
                }.toString()))
            }
        }
    ),

    // ── shell_list: 列出所有后台进程 ──
    Tool(
        name = "shell_list",
        description = "List all background processes.",
        parameters = { null },
        execute = {
            val list = manager.list()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("processes", JsonPrimitive(list.toString()))
            }.toString()))
        }
    ),
)
