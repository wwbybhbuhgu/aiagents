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
    appendLine("- Inline file display — USE IMAGES VIA MARKDOWN, `show_file` ONLY FOR NON-IMAGES: markdown can ONLY render IMAGES inline (it cannot play audio/video or show text/document files). (a) For an IMAGE, DO NOT use `show_file`; embed it directly in your markdown reply with a content uri built as `content://<app>.workspacefile/<workspaceId><path>` (e.g. `content://<app>.workspacefile/${workspace.id}/workspace/images/xxx.png`), pasted WITHOUT angle brackets: `![alt](content://...)`. Get the image's `/workspace/...` path from the generating/search tool result. (b) For VIDEO, AUDIO, PDF, text/markdown documents, zip and any other non-image file, use the `show_file` tool (renders a built-in player/viewer). This is exactly why `show_file` exists: it extends display beyond what markdown can render.")
    appendLine("- Rootfs path guide: the container root directory is `/`. The workspace file area is mounted at `/workspace` (its root). Mounted directories include `/workspace` (persistent work files), `/upload` (shared uploads), `/screenshots`, `/sd`, `/memories`, `/skills`, and `/tool_outputs` (ephemeral tool output, cleared on app start). When you reference a file with the internal protocol, use the FULL absolute path inside the container — for workspace files that is `/workspace/<file>`; for the other mounts it is `/upload/<file>`, `/screenshots/<file>`, `/sd/<file>`, `/memories/<file>`, `/skills/<file>`, `/tool_outputs/<file>` respectively.")
    appendLine("- Interactive HTML card: use the `render_html_card` tool when the user wants a rich interactive component (video player, file browser, dashboard, charts, forms, mini-app, mini chat). It renders your HTML in a live WebView with full JS, plus a global `AIAgents` JS API: `getInfo()`, `listFiles(path)`, `readText(path)`, `readBase64(path)`, `writeText(path,text)`, `shell(cmd,cwd)`, `search(query)` (structured web search), `generate(prompt,system,maxTokens)` (non-streaming, returns {text, reasoning}) and `generateStream({prompt,system,maxTokens,onDelta,onReasoning,onDone})` (streaming with named JS callbacks; streams the model's chain-of-thought via `onReasoning`). All synchronous `AIAgents.*` methods return NATIVE JS OBJECTS (auto-JSON-parsed) — call them directly without `JSON.parse` or `await` (`generateStream` is the only async one). IMPORTANT: (1) always pass the COMPLETE html in ONE tool call — the card renders while your reply streams, never assemble it across multiple calls; (2) new tabs/windows are blocked — `target=\"_blank\"` and `window.open` navigate inside the card instead; (3) keep shell commands under the 30s timeout; (4) workspace paths are container paths — absolute (`/workspace/...`) and relative both work, relative resolves against the card cwd (default `/workspace`); (5) for chat-like card UI prefer `generateStream` so answers stream in and thinking is delivered separately via `onReasoning`; (6) your JavaScript must be valid — a single syntax error blanks the card, and object keys starting with a digit must be quoted (`'7z':'x'`).")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}
