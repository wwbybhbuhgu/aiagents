package com.aiagents.data.ai.transformers

import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessage
import com.aiagents.data.db.entity.WorkspaceEntity
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendSystemText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\" (id: ${workspace.id}), running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- For any multi-step job, plan before executing: break the goal into concrete steps and track them with the `todo_write` tool, updating status as you go so later steps and the final summary can reference them.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- The official user guide skill is at `/skills/ai-agents-user-guide/SKILL.md`. When the user asks how to use this app, how to configure something, or where a feature is, read that skill and guide the user step by step. Settings that involve secrets (API keys, tokens), or default ordering (e.g. default search engine, by long-press drag), must be done manually by the user in the app UI — never change them for the user.")
    appendLine("- The uploads directory is mounted at `/upload` (shared across all workspaces). Uploaded files are placed there by the user; you may read them, and you may also write new files into `/upload` if you need to share them back to the user or keep them in one shared place.")
    appendLine("- The screenshots directory is mounted at `/screenshots`, memories at `/memories`, and the shared SD area at `/sd`. These correspond to real folders on the Android device under `/sdcard/AI-Agent/` (e.g. `/screenshots` ↔ `/sdcard/AI-Agent/screenshots`, `/sd` ↔ `/sdcard/AI-Agent/sd`).")
    appendLine("- DEVICE PATH CORRESPONDENCE: workspace tools (workspace_read_file, workspace_write_file, workspace_shell, image_analysis, file_share, workspace_glob) operate on container Rootfs paths like `/workspace/...`, `/screenshots/...`, `/sd/...`. Phone tools that need a SYSTEM path (install_apk, set_wallpaper) work on the real Android filesystem, but accept workspace paths too and resolve them automatically. To hand a container file to a device tool, just pass its workspace path (e.g. `install_apk` with `/sd/downloads/app.apk`); the tool copies it to the shared `/sdcard/AI-Agent/` area when needed. Prefer keeping files you will pass to device tools under `/sd` or `/screenshots`, which are already world-visible on the device.")
    appendLine("- Internal file protocol: `aiagents-file://${workspace.id}/path/to/file`. Use this in markdown to reference workspace files directly (e.g. `![screenshot](aiagents-file://${workspace.id}/screenshots/xxx.png)`). The Coil image loader in the app can resolve this protocol to display images inline.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}
