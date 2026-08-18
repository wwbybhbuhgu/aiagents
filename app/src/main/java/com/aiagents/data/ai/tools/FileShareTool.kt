package com.aiagents.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.repository.WorkspaceRepository

private const val MAX_SHARE_BYTES = 256L * 1024 * 1024

/**
 * 构建 file_share 工具：主 Agent 传入工作区内的文件路径，
 * 聊天中渲染为"文件分享卡片"，用户可一键保存到设备或分享给其他应用。
 */
fun buildFileShareTool(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String?,
): Tool = Tool(
    name = "file_share",
    description = """
        Shares a file from the workspace with the user.
        Pass the absolute path inside the workspace rootfs (e.g. /workspace/files/report.pdf).
        The file is rendered as a share card in the conversation, letting the user
        save it to the device or share it to another app.
        Use this to deliver files to the user (reports, exports, generated content, etc.).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the file inside the workspace rootfs")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { false },
    execute = {
        if (workspaceId.isNullOrBlank()) {
            error("文件分享需要绑定工作区，请先为助手配置工作区")
        }
        val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { p -> p.isNotBlank() && p.startsWith("/") }
            ?: error("path is required and must be absolute")
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        require(size <= MAX_SHARE_BYTES) { "文件过大，无法分享: $path" }
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { "file" }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("shareFile", true)
                    put("workspaceId", workspaceId)
                    put("path", path)
                    put("name", name)
                    put("sizeBytes", size)
                }.toString()
            )
        )
    },
)
