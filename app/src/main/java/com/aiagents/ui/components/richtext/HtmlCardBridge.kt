package com.aiagents.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.provider.Model
import com.aiagents.ai.provider.ProviderManager
import com.aiagents.ai.provider.TextGenerationParams
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.findModelById
import com.aiagents.data.datastore.findProvider
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.search.SearchService
import com.aiagents.search.SearchServiceOptions
import com.aiagents.utils.JsonInstant
import com.aiagents.workspace.WorkspaceStorageArea
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent.inject
import java.io.ByteArrayOutputStream

/**
 * HTML 卡片 JS Bridge
 *
 * 暴露给卡片内页面一个 `AIAgents` 全局对象, 提供工作区访问(文件/目录/shell)、
 * 联网搜索与当前会话模型调用能力:
 *
 * ```js
 * const info = AIAgents.getInfo();                       // {workspaceId, cwd, appVersion, platform}
 * const files = AIAgents.listFiles('/workspace');        // JSON 数组
 * const text  = AIAgents.readText('/workspace/a.txt');
 * const bytes = AIAgents.readBase64('/workspace/img.png');
 * AIAgents.writeText('/workspace/b.txt', 'hello');
 * const sh = AIAgents.shell('ls -la', '/workspace');     // {exitCode, stdout, stderr}
 * const sr = AIAgents.search('端午节由来');               // 结构化联网搜索
 * const ai = AIAgents.generate('你好', '你是助手', 1024); // 非流式, 返回完整文本
 *
 * // 流式生成(推荐): 通过命名回调接收增量, 支持思维链
 * AIAgents.generateStream(JSON.stringify({
 *   prompt: '...', system: '...', maxTokens: 2048,
 *   onDelta: 'myDelta', onReasoning: 'myReasoning', onDone: 'myDone'
 * }));
 * // window.myDelta(text), window.myReasoning(text), window.myDone(fullText, reasoningText)
 * ```
 */
@SuppressLint("JavascriptInterface")
internal class HtmlCardBridge(
    context: Context,
    private val workspaceId: String?,
    private val cwd: String?,
    private val modelRef: () -> Model?,
    private val webViewRef: () -> WebView? = { null },
) {
    private val appContext = context.applicationContext
    private val workspaceRepository: WorkspaceRepository by inject(WorkspaceRepository::class.java)
    private val providerManager: ProviderManager by inject(ProviderManager::class.java)
    private val settingsStore: com.aiagents.data.datastore.SettingsStore by inject(
        com.aiagents.data.datastore.SettingsStore::class.java
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun requireWorkspace(): String =
        workspaceId ?: throw IllegalStateException("当前会话未绑定工作区")

    @JavascriptInterface
    fun getInfo(): String = runBlocking {
        val settings: Settings = settingsStore.settingsFlow.value
        buildJsonObject {
            put("workspaceId", workspaceId ?: "")
            put("cwd", cwd ?: "")
            put("appName", appContext.getString(com.aiagents.R.string.app_name))
            put("appVersion", com.aiagents.BuildConfig.VERSION_NAME)
            put("platform", "android")
            put("searchEngines", JsonArray(
                settings.searchServices.map { JsonPrimitive(it.displayName) }
            ))
        }.toString()
    }

    @JavascriptInterface
    fun listFiles(path: String?): String = runBlocking {
        val base = normalizePath(path?.takeIf { it.isNotBlank() } ?: cwd ?: "/workspace")
        runCatching {
            val entries = workspaceRepository.listFiles(
                id = requireWorkspace(),
                area = WorkspaceStorageArea.FILES,
                path = base,
            )
            buildJsonObject {
                put("ok", true)
                put("path", base)
                put("entries", JsonArray(entries.map { entry ->
                    buildJsonObject {
                        put("path", entry.path)
                        put("name", entry.name)
                        put("isDirectory", entry.isDirectory)
                        put("size", entry.sizeBytes)
                        put("updatedAt", entry.updatedAt)
                    }
                }))
            }.toString()
        }.getOrElse { e -> err(e) }
    }

    @JavascriptInterface
    fun readText(path: String?): String = runBlocking {
        val p = requirePath(path)
        runCatching {
            buildJsonObject {
                put("ok", true)
                put("path", p)
                put("content", workspaceRepository.readText(requireWorkspace(), p))
            }.toString()
        }.getOrElse { e -> err(e) }
    }

    @JavascriptInterface
    fun readBase64(path: String?): String = runBlocking {
        val p = requirePath(path)
        runCatching {
            val size = workspaceRepository.rootfsFileSize(requireWorkspace(), p)
            val bytes = ByteArrayOutputStream(size.toInt().coerceAtMost(64 * 1024 * 1024)).also { out ->
                workspaceRepository.exportRootfsFile(requireWorkspace(), p, out)
            }.toByteArray()
            buildJsonObject {
                put("ok", true)
                put("path", p)
                put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }.toString()
        }.getOrElse { e -> err(e) }
    }

    @JavascriptInterface
    fun writeText(path: String?, content: String?): String = runBlocking {
        val p = requirePath(path)
        runCatching {
            workspaceRepository.writeText(
                id = requireWorkspace(),
                path = p,
                text = content ?: "",
                overwrite = true,
            )
            buildJsonObject { put("ok", true); put("path", p) }.toString()
        }.getOrElse { e -> err(e) }
    }

    @JavascriptInterface
    fun shell(command: String?, cwdArg: String?): String = runBlocking {
        val cmd = command?.takeIf { it.isNotBlank() } ?: return@runBlocking err(IllegalArgumentException("command is required"))
        runCatching {
            val result = workspaceRepository.executeCommand(
                id = requireWorkspace(),
                command = cmd,
                cwd = toShellCwd(cwdArg?.takeIf { it.isNotBlank() }?.let { normalizePath(it) } ?: cwd),
            )
            buildJsonObject {
                put("ok", true)
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                put("truncated", result.truncated)
            }.toString()
        }.getOrElse { e -> err(e) }
    }

    /** 把容器绝对 cwd 转成 executeCommand 需要的相对 filesDir 路径(/workspace -> "", /workspace/xxx -> xxx) */
    private fun toShellCwd(containerCwd: String?): String {
        val norm = containerCwd?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        if (norm == "/workspace") return ""
        if (norm.startsWith("/workspace/")) return norm.removePrefix("/workspace/")
        // 其他挂载点(/upload 等)不在 filesDir 下, executeCommand 只支持 filesDir 相对路径,
        // 回退到 /workspace 根(命令内可自行 cd)
        return ""
    }

    /** 结构化联网搜索: 从用户配置的搜索引擎中选择(可按名称指定), 默认用当前选中的引擎 */
    @JavascriptInterface
    fun search(query: String?, resultSize: Int?, engine: String?): String = runBlocking {
        val q = query?.takeIf { it.isNotBlank() }
            ?: return@runBlocking err(IllegalArgumentException("query is required"))
        runCatching {
            val settings: Settings = settingsStore.settingsFlow.value
            val options = resolveSearchOptions(settings, engine)
            val service = SearchService.getService(options)
            val params = buildJsonObject { put("query", JsonPrimitive(q)) }
            val result = service.search(
                params = params,
                commonOptions = settings.searchCommonOptions.copy(
                    resultSize = resultSize?.takeIf { it > 0 } ?: settings.searchCommonOptions.resultSize
                ),
                serviceOptions = options,
            ).getOrThrow()
            buildJsonObject {
                put("ok", true)
                put("query", q)
                put("engine", options.displayName)
                put("answer", result.answer ?: "")
                put("items", JsonArray(result.items.map { item ->
                    buildJsonObject {
                        put("title", item.title)
                        put("url", item.url)
                        put("text", item.text)
                    }
                }))
            }.toString()
        }.getOrElse { e ->
            android.util.Log.e("HtmlCardBridge", "search failed: query=$q", e)
            err(e)
        }
    }

    /** 按引擎名从用户已配置的搜索引擎中选择; 未指定或不存在时回退到当前选中/默认引擎 */
    private fun resolveSearchOptions(settings: Settings, engine: String?): SearchServiceOptions {
        val name = engine?.trim().orEmpty()
        if (name.isNotEmpty()) {
            settings.searchServices.firstOrNull { it.displayName == name }?.let { return it }
        }
        return settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT },
        )
    }
    @JavascriptInterface
    fun generate(prompt: String?, system: String?, maxTokens: Int?): String = runBlocking {
        val p = prompt?.takeIf { it.isNotBlank() }
            ?: return@runBlocking err(IllegalArgumentException("prompt is required"))
        runCatching {
            val resolved = resolveModel()
            val settings = resolved.settings
            val model = resolved.model
            val providerSetting = resolved.providerSetting
            val provider = resolved.provider
            val messages = buildList {
                if (!system.isNullOrBlank()) add(UIMessage.system(system))
                add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(p))))
            }
            val chunk = provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    maxTokens = maxTokens?.takeIf { it > 0 },
                ),
            )
            val choice = chunk.choices.firstOrNull()
            val text = choice?.message?.toText()
                ?.ifBlank { choice.delta?.toText().orEmpty() }.orEmpty()
            val reasoning = choice?.message?.parts?.filterIsInstance<UIMessagePart.Reasoning>()
                ?.joinToString("\n") { it.reasoning } ?: ""
            buildJsonObject {
                put("ok", true)
                put("text", text)
                put("reasoning", reasoning)
            }.toString()
        }.getOrElse { e -> err(e) }
    }

    /**
     * 流式生成: 通过命名 JS 回调接收增量文本/思维链。
     * 参数为一个 JSON 字符串:
     * {prompt, system?, maxTokens?, onDelta?, onReasoning?, onDone?, onError?}
     * onDelta(text) 收到文本增量; onReasoning(text) 收到思维链增量; onDone(text, reasoning) 结束。
     */
    @JavascriptInterface
    fun generateStream(config: String?) {
        val cfg = runCatching {
            JsonInstant.parseToJsonElement(config ?: "{}").jsonObject
        }.getOrElse { null }
        val prompt = cfg?.get("prompt")?.jsonPrimitive?.contentOrNull
        if (prompt.isNullOrBlank()) {
            jsCallback(cfg?.get("onError")?.jsonPrimitive?.contentOrNull, "参数错误: prompt 不能为空")
            return
        }
        val system = cfg?.get("system")?.jsonPrimitive?.contentOrNull
        val maxTokens = cfg?.get("maxTokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val onDelta = cfg?.get("onDelta")?.jsonPrimitive?.contentOrNull
        val onReasoning = cfg?.get("onReasoning")?.jsonPrimitive?.contentOrNull
        val onDone = cfg?.get("onDone")?.jsonPrimitive?.contentOrNull
        val onError = cfg?.get("onError")?.jsonPrimitive?.contentOrNull

        // JS 线程上执行 runBlocking 阻塞整个流, 通过主线程 evaluateJavascript 回调增量
        runBlocking {
            runCatching {
                val resolved = resolveModel()
                val model = resolved.model
                val providerSetting = resolved.providerSetting
                val provider = resolved.provider
                val messages = buildList {
                    if (!system.isNullOrBlank()) add(UIMessage.system(system))
                    add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt))))
                }
                val params = TextGenerationParams(
                    model = model,
                    maxTokens = maxTokens?.takeIf { it > 0 },
                )
                val textBuilder = StringBuilder()
                val reasoningBuilder = StringBuilder()
                provider.streamText(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                ).collect { chunk ->
                    val choice = chunk.choices.firstOrNull() ?: return@collect
                    val message = choice.delta ?: choice.message ?: return@collect
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                textBuilder.append(part.text)
                                if (part.text.isNotBlank()) jsCallback(onDelta, part.text)
                            }
                            is UIMessagePart.Reasoning -> {
                                reasoningBuilder.append(part.reasoning)
                                if (part.reasoning.isNotBlank()) jsCallback(onReasoning, part.reasoning)
                            }
                            else -> {}
                        }
                    }
                }
                val result = buildJsonObject {
                    put("text", textBuilder.toString())
                    put("reasoning", reasoningBuilder.toString())
                }.toString()
                if (onDone != null) {
                    mainHandler.post { webViewRef()?.evaluateJavascript("$onDone('${result.escapeJs()}')", null) }
                }
            }.onFailure { e ->
                jsCallback(onError, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private data class ResolvedModel(
        val settings: Settings,
        val model: Model,
        val providerSetting: com.aiagents.ai.provider.ProviderSetting,
        val provider: com.aiagents.ai.provider.Provider<com.aiagents.ai.provider.ProviderSetting>,
    )

    /** 解析当前会话模型 + provider */
    private suspend fun resolveModel(): ResolvedModel {
        val settings: Settings = settingsStore.settingsFlow.value
        val model = modelRef() ?: settings.findModelById(settings.chatModelId)
            ?: error("未配置模型")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model: ${model.displayName}")
        val provider = providerManager.getProviderByType(providerSetting)
        @Suppress("UNCHECKED_CAST")
        return ResolvedModel(
            settings = settings,
            model = model,
            providerSetting = providerSetting,
            provider = provider as com.aiagents.ai.provider.Provider<com.aiagents.ai.provider.ProviderSetting>,
        )
    }

    private fun jsCallback(callback: String?, payload: String) {
        if (callback.isNullOrBlank()) return
        mainHandler.post { webViewRef()?.evaluateJavascript("$callback('${payload.escapeJs()}')", null) }
    }

    private fun requirePath(path: String?): String =
        normalizePath(path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("path is required"))

    /**
     * 相对路径 → 绝对容器路径。
     * - 以 `/` 开头: 原样保留(挂载点/绝对路径)
     * - 相对路径: 基于 [cwd] 解析(默认 /workspace)
     * - 支持 `.` / `..` 归一化
     */
    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return cwd?.takeIf { it.startsWith("/") } ?: "/workspace"
        if (trimmed.startsWith("/")) {
            return normalizeDotSegments(trimmed)
        }
        val base = cwd?.trim()?.takeIf { it.startsWith("/") } ?: "/workspace"
        return normalizeDotSegments("$base/${trimmed.trimStart('/')}")
    }

    private fun normalizeDotSegments(path: String): String {
        val isRoot = path.startsWith("/")
        val segments = ArrayDeque<String>()
        path.split("/").forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeLast()
                else -> segments.addLast(seg)
            }
        }
        return (if (isRoot) "/" else "") + segments.joinToString("/")
    }

    private fun err(e: Throwable): String = buildJsonObject {
        put("ok", false)
        put("error", e.message ?: e.javaClass.simpleName)
    }.toString()

    private fun String.escapeJs(): String =
        replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
}
