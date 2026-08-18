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
 * 构建 render_html_card 工具: AI 编写一段 HTML(或提供 URL)渲染成一张交互式卡片。
 *
 * 卡片在 WebView 中运行, 拥有完整浏览器能力, 并通过全局 `AIAgents` JS 对象暴露额外 API:
 * - `AIAgents.getInfo()`: {workspaceId, cwd, appVersion, ...}
 * - `AIAgents.listFiles(path)`: 列出工作区目录
 * - `AIAgents.readText(path)` / `AIAgents.readBase64(path)`: 读取文件
 * - `AIAgents.writeText(path, content)`: 写入文件
 * - `AIAgents.shell(command, cwd)`: 执行 shell 命令
 * - `AIAgents.generate(prompt, system, maxTokens)`: 调用当前会话模型
 */
fun buildHtmlCardTool(
    workspaceId: String?,
): Tool = Tool(
    name = "render_html_card",
    description = """
        Renders an interactive HTML card inside the chat. The card runs in a real WebView
        with full browser capabilities, plus a global `AIAgents` JavaScript API for this
        workspace and the current AI model.

        When to use: the user wants a rich, interactive component (video player, file browser,
        dashboard, charts, forms, a mini-app) — not just static text or an image.

        Parameters:
        - `html`: a complete HTML document or a body fragment (recommended). Prefer inline HTML
          so the card works offline. You may use `<script>` to drive interactivity.
        - `url`: alternative to `html` — an external URL to load. Use only when inline HTML is
          not possible (e.g. you cannot inline the page). If both are given, `html` wins.
        - `title`: an optional short title shown as the card header.
        - `height`: approximate card height in px (default 480, clamped to 160..960).

        AIAgents JavaScript API available inside the card (window.AIAgents). All synchronous methods return
        a NATIVE OBJECT (auto-parsed JSON) — use them directly, do not JSON.parse and do not await:
        - `AIAgents.getInfo()`            -> {workspaceId, cwd, appVersion, platform}
        - `AIAgents.listFiles(path)`      -> {ok, path, entries:[{path,name,isDirectory,size,updatedAt}]}
        - `AIAgents.readText(path)`       -> {ok, path, content}
        - `AIAgents.readBase64(path)`     -> {ok, path, base64}
        - `AIAgents.writeText(path,text)` -> {ok, path}
        - `AIAgents.shell(cmd,cwd)`       -> {ok, exitCode, stdout, stderr, timedOut, truncated}
        - `AIAgents.search(query,resultSize,engine)` -> {ok, query, engine, answer, items:[{title,url,text}]} (structured web search). `engine` selects among the user's configured search engines by name (get the list from `AIAgents.getInfo().searchEngines`); omit it to use the currently selected default engine.
        - `AIAgents.generate(prompt,system,maxTokens)` -> {ok, text, reasoning} (non-streaming, returns the full text plus any reasoning/chain-of-thought)
        - `AIAgents.generateStream(JSON.stringify({prompt, system, maxTokens, onDelta, onReasoning, onDone}))` -> streaming. Define global JS functions, e.g. `window.onDelta=function(t){...}`, `window.onReasoning=function(t){...}`, `window.onDone=function(text,reasoning){...}`. Use streaming for chat-like features so text appears incrementally; it also streams the model's chain-of-thought via onReasoning when the model supports it.

        IMPORTANT usage notes:
        - The app auto-wraps `AIAgents`: synchronous methods (`getInfo/listFiles/readText/readBase64/writeText/shell/search/generate`) return a NATIVE JS OBJECT (already JSON-parsed), NOT a string. Use them directly: `const r = AIAgents.shell('ls','/workspace'); r.ok; r.stdout;`. Do NOT call JSON.parse on them and do NOT `await` them. `generateStream` is the only async one (via callbacks).
        - Prefer `generateStream` over `generate` for user-facing chat UIs so output streams and thinking is delivered live.
        - Chain-of-thought: when the model produces reasoning, it is delivered through `onReasoning` (streaming) or the `reasoning` field (non-streaming). Render thinking in a collapsible section; never inject it into the answer.
        - Long-running shell commands block until finished (default timeout 30s). Keep commands short.
        - VALIDATE your JS: any syntax error blanks the whole card. In particular, object keys starting with a digit are illegal (`7z:'x'` fails) — always quote them (`'7z':'x'`).

        Paths are container paths. Both absolute (`/workspace/...`, `/upload/...`, `/screenshots/...`) and
        relative paths are accepted — a relative path is resolved against the card's working directory
        (default `/workspace`), so `listFiles('images')` == `listFiles('/workspace/images')`.
        Examples of great cards:
        - A video player for /workspace/videos/xxx.mp4: read the file, pick a codec, embed <video>.
        - A file browser: listFiles('/workspace') and render a clickable list.
        - A markdown viewer: readText('/workspace/notes.md') then render with a tiny parser.
        - A search dashboard: AIAgents.search(query) and render results.
        - A mini chat: generateStream with onDelta to stream the assistant's answer into a bubble.
        - A mini dashboard or interactive form wired to AIAgents.shell / AIAgents.generate.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("html", buildJsonObject {
                    put("type", "string")
                    put("description", "HTML document or body fragment to render in the card")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "External URL to load in the card (used when html is not provided)")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional short title for the card header")
                })
                put("height", buildJsonObject {
                    put("type", "integer")
                    put("description", "Card height in px (default 480, range 160..960)")
                })
            },
        )
    },
    needsApproval = { false },
    systemPrompt = { _, _ ->
        """
        ## HTML card (render_html_card)
        When you build an HTML card, follow these rules carefully:
        - The `html` you pass must be COMPLETE and self-contained in a single value. The app renders it
          as a live WebView while your reply streams, so do NOT rely on streaming multiple chunks that
          assemble the card — always output the full document (or full body fragment) in one tool call.
        - The card runs with JavaScript enabled and internet access, but treat it as a sandboxed
          preview: prefer inline CSS/JS, avoid external CDNs unless necessary.
        - The app auto-wraps `AIAgents`: synchronous methods return a NATIVE JS OBJECT (already JSON-parsed), not a string. Use `r = AIAgents.shell('ls','/workspace'); r.ok; r.stdout;` directly — no JSON.parse, no await. `generateStream` is the only async one (named JS callbacks onDelta/onReasoning/onDone).
        - Your JavaScript MUST be valid ECMAScript — a single syntax error breaks the whole card (blank page). Common pitfalls: object keys that start with a digit (e.g. `7z:'x'`) are INVALID; write them as `'7z':'x'` or `'7z': 'x'`. Always quote such keys. Also avoid unescaped control chars inside strings.
        - For chat-like UI in the card, PREFER `generateStream` so the answer streams in and the model's chain-of-thought is delivered live via `onReasoning`. Render thinking separately (e.g. a collapsible `<details>`); never mix it into the displayed answer.
        - Use `AIAgents.search(query, resultSize, engine)` to get structured web results; render them as links with the returned titles/text. Pass `engine` (a name from `AIAgents.getInfo().searchEngines`) to pick a search engine, or omit it to use the default.
        - Long-running shell commands block until finished (default timeout 30s). Keep commands short.
        - The card cannot open new tabs/windows: `target="_blank"` and `window.open` are converted to
          an in-card navigation by the app. Do not design flows that depend on popups.
        - Workspace paths are container paths. Absolute paths (`/workspace/...`, `/upload/...`, `/screenshots/...`, `/sd/...`, `/memories/...`, `/skills/...`) and RELATIVE paths both work — relative paths resolve against the card cwd (default `/workspace`), e.g. `AIAgents.readText('bv_analysis/frame_01.jpg')` reads `/workspace/bv_analysis/frame_01.jpg`. To inline a workspace file as an image/video in your HTML, use the `show_file`-style `content://<app>.workspacefile/<workspaceId>/<path>` uri, or read it via `AIAgents.readBase64(path)` and embed as a data: URL.
        """.trimIndent()
    },
    execute = {
        val html = it.jsonObject["html"]?.jsonPrimitive?.contentOrNull?.takeIf { h -> h.isNotBlank() }
            ?: ""
        val url = it.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { u -> u.isNotBlank() }
            ?: ""
        if (html.isBlank() && url.isBlank()) {
            error("render_html_card requires either 'html' or 'url'")
        }
        val title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val height = it.jsonObject["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 480
        val metadata = buildJsonObject {
            put("workspaceId", workspaceId ?: "")
        }
        listOf(
            UIMessagePart.HtmlCard(
                url = url,
                html = html,
                title = title,
                height = height.coerceIn(160, 960),
                metadata = metadata,
            )
        )
    },
)
