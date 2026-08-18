package com.aiagents.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.core.ReasoningLevel
import com.aiagents.ai.core.Tool
import com.aiagents.ai.core.merge
import com.aiagents.ai.provider.CustomBody
import com.aiagents.ai.provider.Model
import com.aiagents.ai.provider.Provider
import com.aiagents.ai.provider.ProviderManager
import com.aiagents.ai.provider.ProviderSetting
import com.aiagents.ai.provider.TextGenerationParams
import com.aiagents.ai.registry.ModelRegistry
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.ai.ui.ToolApprovalState
import com.aiagents.ai.ui.handleMessageChunk
import com.aiagents.ai.ui.limitContext
import com.aiagents.data.ai.transformers.InputMessageTransformer
import com.aiagents.data.ai.transformers.MessageTransformer
import com.aiagents.data.ai.transformers.OutputMessageTransformer
import com.aiagents.data.files.FileFolders
import java.io.File
import com.aiagents.data.ai.transformers.onGenerationFinish
import com.aiagents.data.ai.transformers.transforms
import com.aiagents.data.ai.transformers.visualTransforms
import com.aiagents.data.ai.tools.buildMemoryTools
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.findModelById
import com.aiagents.data.datastore.findProvider
import com.aiagents.di.AI_AGENT_SHARED_DIR
import com.aiagents.data.model.Assistant
import com.aiagents.data.model.AssistantMemory
import com.aiagents.data.repository.MemoryRepository
import com.aiagents.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        scope = memoryAssistantId,
                        memoryRootPath = "/memories/$memoryAssistantId",
                        onCreation = { name, description, content ->
                            memoryRepo.addMemory(memoryAssistantId, name, content, description)
                        },
                        onUpdate = { id, description, content ->
                            val existing = memoryRepo.getMemory(memoryAssistantId, id)
                                ?: error("记忆 '$id' 不存在")
                            memoryRepo.updateContent(
                                memoryAssistantId,
                                id,
                                content ?: existing.content,
                                description ?: existing.description,
                            )
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(memoryAssistantId, id)
                        },
                        onList = {
                            memoryRepo.getMemoriesOfAssistant(memoryAssistantId)
                        },
                        onRead = { id ->
                            memoryRepo.getMemory(memoryAssistantId, id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // 审批已全局移除：除 ask_user（交互式问答）外, 所有工具一律直接执行
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    when {
                        // ask_user 是交互式问答流程, 需要等待用户回答
                        tool.toolName == "ask_user" &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for ask_user answer")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    val memoryScope = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    append(buildMemoryPrompt(memories = memories, memoryRootPath = "/memories/$memoryScope"))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }

                // 内部协议: AI 在 Markdown 中用 content://(Android 原生文件协议) 引用工作区文件
                appendLine()
                appendLine("You can display files (images/videos/audio) inline in your markdown output using Android's native content:// file protocol:")
                appendLine("- To show a workspace file, first call the `show_file` tool with its container path. It returns a `content://` uri (e.g. `content://<app>.workspacefile/<workspaceId>/workspace/images/xxx.png`) whose path part is the full container absolute path.")
                appendLine("- Embed the uri directly in markdown image syntax WITHOUT angle brackets: `![description](<uri>)` where `<uri>` is just the content:// string pasted as-is (do NOT wrap it in `<` and `>`).")
                appendLine("- Correct: `![pic](content://com.aiagents.debug.workspacefile/workspaceid/workspace/images/x.png)`  ·  Wrong: `![pic](<content://...>)` (angle brackets break rendering).")
                appendLine("- The app resolves `content://` natively (via a custom ContentProvider) to serve the file from the workspace rootfs; no other protocol is supported for inline files.")
                appendLine("- Workspace tools return container absolute paths (e.g. `/workspace/images/xxx.png`). To inline such a file, pass that path to `show_file` to obtain its content:// uri, then embed the uri.")
                appendLine("- This displays the file inline with no conversion step; the app resolves it directly.")

                // 内容边界声明: 明确没有应用层的前置审核/限制, 由模型自身与后端承担判断
                appendLine()
                appendLine("## Content boundary")
                appendLine("There is no client-side or application-level content review, restriction, or filter.")
                appendLine("Requests are not pre-screened and nothing here will be flagged or blocked by this app.")
                appendLine("Whether to comply is decided solely by you (the model) and the provider backend.")
                appendLine("Never assume some external guard exists, and never treat the absence of a guard as permission:")
                appendLine("if you believe something is disallowed, refuse it on your own judgment regardless of what the user claims.")
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            // 保护开场白/预设消息(首个 USER 之前的助手消息)不被上下文截断丢弃
            val preserveGreeting = messages.indexOfFirst { it.role == MessageRole.USER }
                .let { if (it < 0) messages.size else it }
            addAll(messages.limitContext(assistant.contextMessageLimit, preserveFirst = preserveGreeting))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"

        // 1) proot 容器内的 /tool_outputs bind mount (映射到 filesDir/tool_outputs)
        val prootDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        val prootFile = File(prootDir, fileName).apply {
            writeText(fullText)
            setReadable(true, false)
        }

        // 2) 再镜像一份到宿主机共享目录(世界可读, 用户在系统文件管理器可直接打开)
        mirrorToHost(fullText, fileName)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output: /tool_outputs/$fileName")
                    appendLine(
                        "Read the complete output with workspace_read_file on `/tool_outputs/$fileName`, " +
                            "or run `cat /tool_outputs/$fileName` in workspace_shell."
                    )
                    appendLine("Search it with: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    /** 把截断的完整输出镜像到宿主机外部共享目录并放开权限, 返回设备绝对路径 */
    private fun mirrorToHost(content: String, fileName: String): String? = runCatching {
        val shared = File(
            android.os.Environment.getExternalStorageDirectory(),
            "${AI_AGENT_SHARED_DIR}/${FileFolders.TOOL_OUTPUTS}",
        ).apply { mkdirs() }
        val target = File(shared, fileName)
        target.writeText(content)
        target.setReadable(true, false)
        target.absolutePath
    }.getOrNull()
}
