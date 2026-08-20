package com.aiagents.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.core.ReasoningLevel
import com.aiagents.ai.core.Tool
import com.aiagents.ai.provider.Model
import com.aiagents.ai.provider.ModelAbility
import com.aiagents.ai.provider.ProviderManager
import com.aiagents.ai.provider.TextGenerationParams
import com.aiagents.ai.ui.ToolApprovalState
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.ai.ui.canResumeToolExecution
import com.aiagents.ai.ui.finishPendingTools
import com.aiagents.ai.ui.finishReasoning
import com.aiagents.ai.ui.isEmptyInputMessage
import com.aiagents.common.android.Logging
import com.aiagents.AppScope
import com.aiagents.R
import com.aiagents.data.ai.GenerationChunk
import com.aiagents.data.ai.GenerationHandler
import com.aiagents.data.ai.mcp.McpManager
import com.aiagents.data.ai.tools.createConversationTools
import com.aiagents.data.ai.tools.local.LocalTools
import com.aiagents.data.ai.tools.createSearchTools
import com.aiagents.data.ai.tools.createSkillTools
import com.aiagents.data.ai.tools.createWorkspaceTools
import com.aiagents.data.ai.tools.buildTodoTools
import com.aiagents.data.ai.tools.buildWebFetchTool
import com.aiagents.data.ai.tools.buildStickerSearchTool
import com.aiagents.data.ai.tools.buildReminderTools
import com.aiagents.data.ai.tools.AgentRun
import com.aiagents.data.ai.tools.AgentRunManager
import com.aiagents.data.ai.tools.AgentRunStatus
import com.aiagents.data.ai.tools.buildAgentTools
import com.aiagents.data.ai.tools.buildAssistantTools
import com.aiagents.data.ai.tools.buildCronTools
import com.aiagents.data.ai.tools.buildFileShareTool
import com.aiagents.data.ai.tools.buildShowFileTool
import com.aiagents.data.ai.tools.buildHtmlCardTool
import com.aiagents.data.ai.tools.buildImageGenTool
import com.aiagents.data.ai.tools.buildCvImageTool
import com.aiagents.data.ai.tools.buildImageEditTool
import com.aiagents.data.ai.tools.buildMediaTool
import com.aiagents.data.ai.tools.buildImageAnalysisTool
import com.aiagents.data.ai.tools.local.LocalToolOption
import org.koin.java.KoinJavaComponent.getKoin
import com.aiagents.data.files.SkillManager
import com.aiagents.data.ai.transformers.Base64ImageToLocalFileTransformer
import com.aiagents.data.ai.transformers.UploadFileTransformer
import com.aiagents.data.ai.transformers.PlaceholderTransformer
import com.aiagents.data.ai.transformers.PromptInjectionTransformer
import com.aiagents.data.ai.transformers.RegexOutputTransformer
import com.aiagents.data.ai.transformers.TemplateTransformer
import com.aiagents.data.ai.transformers.ThinkTagTransformer
import com.aiagents.data.ai.transformers.TimeReminderTransformer
import com.aiagents.data.ai.transformers.ToolScenarioGuideTransformer
import com.aiagents.data.ai.transformers.UserPriorityTransformer
import com.aiagents.data.ai.transformers.WorkspaceReminderTransformer
import com.aiagents.data.ai.transformers.ProxyInjectionTransformer
import com.aiagents.data.ai.transformers.MemePathValidatorTransformer
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.findModelById
import com.aiagents.data.datastore.findProvider
import com.aiagents.data.datastore.getAssistantById
import com.aiagents.data.datastore.getCurrentAssistant
import com.aiagents.data.datastore.getCurrentChatModel
import com.aiagents.data.files.FilesManager
import com.aiagents.data.model.Conversation
import com.aiagents.data.model.Assistant
import com.aiagents.data.model.AssistantAffectScope
import com.aiagents.data.model.replaceRegexes
import com.aiagents.data.model.toMessageNode
import com.aiagents.data.repository.ConversationRepository
import com.aiagents.data.repository.FolderRepository
import com.aiagents.data.repository.MemoryRepository
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.web.BadRequestException
import com.aiagents.web.NotFoundException
import com.aiagents.utils.applyPlaceholders
import com.aiagents.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        UploadFileTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

/** 生成中消息落盘节流间隔(ms) */
private const val PERSIST_INTERVAL_MS = 400L

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val agentRunManager: AgentRunManager,
    private val proxyManager: com.aiagents.data.proxy.ProxyManager,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    // 内置工具场景指南 (用户意图 → 工具映射, 引导主动使用工具)
    private val toolScenarioGuideTransformer = ToolScenarioGuideTransformer()
    // 用户最高优先级注入 (用户设置"最重要的事情", 注入主 Agent 与子 Agent 的系统提示)
    private val userPriorityTransformer = UserPriorityTransformer()
    // 代理环境变量注入 (代理开启时注入 HTTP_PROXY 等信息到系统提示词)
    private val proxyInjectionTransformer = ProxyInjectionTransformer
    // 表情包/内联图片 /memes/ 路径真实性校验 (拦截模型幻觉出的假图路径)
    private val memeLinkValidatorTransformer = MemePathValidatorTransformer(context)

    // 常驻容器服务启动标志（首次有就绪工作区时启动一次）
    private var containerServiceStarted = false

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 生成中消息落盘节流
    private var lastPersistAt = 0L

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    /** 为当前助手新建一个会话并加载, 返回新会话 id。 */
    suspend fun createNewConversation(): Uuid {
        val id = Uuid.random()
        getOrCreateSession(id)
        val currentSettings = settingsStore.settingsFlowRaw.first()
        val assistant = currentSettings.getCurrentAssistant()
        val newConversation = Conversation.ofId(
            id = id,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
        updateConversation(id, newConversation)
        return id
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        // 消息进入 FIFO 队列, 由 worker 一次只处理一条, 类似一般 Agent 的排队发送
        val session = getOrCreateSession(conversationId)
        session.enqueueSend(content, answer)
        ensureQueueWorker(conversationId)
    }

    /**
     * 确保会话有一个队列 worker。该 worker 串行消费 pending 队列:
     * 当前一条还没回复完时, 后续消息排队等待; 前一条结束(或取消)后再放出去下一条。
     */
    private fun ensureQueueWorker(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.queueWorkerRunning) return
        session.markQueueWorker(true)
        appScope.launch {
            try {
                while (true) {
                    // 等待上一条生成结束
                    while (session.isGenerating) {
                        val job = session.getJob()
                        if (job != null && job.isActive) job.join() else delay(50)
                    }
                    val pending = session.pollPendingSend() ?: break
                    val job = appScope.launch {
                        processQueuedMessage(conversationId, pending)
                    }
                    session.setJob(job)
                    job.join()
                }
            } finally {
                session.markQueueWorker(false)
            }
        }
    }

    private suspend fun processQueuedMessage(
        conversationId: Uuid,
        pending: ConversationSession.PendingSend,
    ) {
        try {
            finishInterruptedPendingTools(conversationId)

            val currentConversation = getOrCreateSession(conversationId).state.value
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
            val processedContent = preprocessUserInputParts(pending.content, assistant)

            // 添加消息到列表
            val newConversation = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                ).toMessageNode(),
            )
            saveConversation(conversationId, newConversation)

            // 开始补全
            if (pending.answer) {
                handleMessageComplete(conversationId)
            }

            _generationDoneFlow.emit(conversationId)
        } catch (e: Exception) {
            e.printStackTrace()
            addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            val tools = try {
                buildConversationTools(
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    conversation = conversation,
                    conversationId = conversationId,
                )
            } catch (e: IllegalStateException) {
                addError(e, conversationId)
                return
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(toolScenarioGuideTransformer)
                    add(workspaceReminderTransformer)
                    add(userPriorityTransformer)
                    add(proxyInjectionTransformer)
                },
                outputTransformers = buildList {
                    addAll(outputTransformers)
                    add(memeLinkValidatorTransformer)
                },
                tools = tools,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新并落盘，
                // 避免停止生成/进程被杀时进行中的消息(含工具调用、图片)只停留在内存态丢失
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)
                runCatching { saveConversation(conversationId, updatedConversation) }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }

                        // 进行中的消息节流落盘(≥400ms 一次)：刷新 Activity / 结束进程重开 /
                        // 悬浮窗开关后仍能从 DB 恢复正在生成的回复(含工具调用、图片引用)，
                        // 而不是只停留在内存态丢失；onCompletion/onSuccess 保证最终完整落盘
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                            lastPersistAt = now
                            runCatching { saveConversation(conversationId, updatedConversation) }
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        ensureContainerServiceRunning()
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, filesManager, context)
    }

    /** 首次有就绪工作区时启动常驻容器前台服务（进程保活, 让 proot 会话持续存活） */
    private fun ensureContainerServiceRunning() {
        if (containerServiceStarted) return
        containerServiceStarted = true
        runCatching {
            val intent = Intent(context, ContainerService::class.java)
                .setAction(ContainerService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            Log.w(TAG, "Failed to start container service", it)
        }
    }

    /**
     * 构建 `agent` 委派工具：子 Agent 在后台独立运行（独立上下文），
     * 携带工作区/待办/网页抓取工具集；完成或失败后把结果报告写回父对话作为日志。
     */
    /**
     * 构建本次会话可用的完整工具列表。主对话生成与委派子 Agent 共用同一份构建逻辑,
     * 保证子 Agent 与父对话能力一致(工具列表同步)。
     *
     * @param includeDelegation 是否注入 `agent` 委派工具; 子 Agent 传 false 避免无限递归
     */
    private suspend fun buildConversationTools(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        conversation: Conversation,
        conversationId: Uuid,
        includeDelegation: Boolean = true,
    ): List<Tool> = buildList {
        if (assistant.enableWebSearch) {
            addAll(
                createSearchTools(
                    context = context,
                    settings = settings,
                    workspaceId = assistant.workspaceId?.toString(),
                    workspaceRepository = workspaceRepository,
                    proxyAddress = proxyManager.localProxyAddress,
                ) { params, content -> compressPageContent(params, content) }
            )
        }
        // 内置工具全部启用，不提供关闭选项（Agent 模式）；
        // 手机自动化工具需显式开启 enablePhoneAutomation，否则不注入，避免 AI 自发操作屏幕
        val localToolOptions = if (assistant.enablePhoneAutomation) {
            LocalToolOption.all
        } else {
            LocalToolOption.all - LocalToolOption.Automation
        }
        addAll(localTools.getTools(localToolOptions, assistant.id.toString()))
        // 浏览器自动化由 AI 发起: 仅当开启 enableBrowserAutomation 时注入整套 browser_* 工具
        if (assistant.enableBrowserAutomation) {
            addAll(localTools.browserTools)
        }
        addAll(buildTodoTools(conversationId.toString()))
        add(buildWebFetchTool(
            proxyManager = proxyManager,
            compress = { params, content -> compressPageContent(params, content) }
        ))
        add(buildStickerSearchTool(
            context = context,
            workspaceId = assistant.workspaceId?.toString(),
            workspaceRepository = workspaceRepository,
            proxyAddress = proxyManager.localProxyAddress,
        ))
        add(
            buildImageGenTool(
                context = context,
                settings = settings,
                providerManager = providerManager,
                workspaceRepository = workspaceRepository,
                workspaceId = assistant.workspaceId?.toString(),
                filesManager = filesManager,
            )
        )
        add(
            buildImageAnalysisTool(
                context = context,
                settings = settings,
                providerManager = providerManager,
                workspaceRepository = workspaceRepository,
                workspaceId = assistant.workspaceId?.toString(),
                filesManager = filesManager,
            )
        )
        add(
            buildCvImageTool(
                context = context,
                workspaceRepository = workspaceRepository,
                filesManager = filesManager,
            )
        )
        add(
            buildImageEditTool(
                context = context,
                workspaceRepository = workspaceRepository,
                filesManager = filesManager,
            )
        )
        add(
            buildMediaTool(
                context = context,
                workspaceId = assistant.workspaceId?.toString(),
                workspaceRepository = workspaceRepository,
            )
        )
        if (includeDelegation) {
            addAll(
                buildAgentDelegationTools(
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    conversationId = conversationId,
                    conversation = conversation,
                )
            )
        }
        addAll(buildAssistantTools(settingsStore, currentAssistantId = assistant.id))
        add(
            buildFileShareTool(
                workspaceRepository = workspaceRepository,
                workspaceId = assistant.workspaceId?.toString(),
            )
        )
        add(
            buildShowFileTool(
                context = context,
                workspaceRepository = workspaceRepository,
                workspaceId = assistant.workspaceId?.toString(),
            )
        )
        add(
            buildHtmlCardTool(
                workspaceId = assistant.workspaceId?.toString(),
            )
        )
        addAll(
            buildCronTools(
                dao = getKoin().get(),
                conversationId = conversationId.toString(),
                workspaceId = assistant.workspaceId?.toString(),
            )
        )
        addAll(
            buildReminderTools(
                dao = getKoin().get(),
                scheduler = getKoin().get(),
                conversationId = conversationId.toString(),
            )
        )
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepo, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
        // 系统内置 skill + 用户启用的 skill 都可用(系统 skill 不显示开关但始终可用)
        val allSkills = skillManager.listAllSkills()
        if (allSkills.any { it.name in assistant.enabledSkills || SkillManager.isSystemSkill(it.name) }) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = allSkills,
                )
            )
        }
        val allMcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = allMcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw IllegalStateException(
                context.getString(
                    R.string.error_mcp_invalid_server_name,
                    invalidNames.joinToString(", ")
                )
            )
        }
        allMcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
    }

    private suspend fun buildAgentDelegationTools(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        conversationId: Uuid,
        conversation: Conversation,
    ): List<Tool> {
        // 子 Agent 同步父对话的完整工具列表(能力一致), 仅排除再次委派, 避免无限递归
        val subAgentTools = buildConversationTools(
            settings = settings,
            model = model,
            assistant = assistant,
            conversation = conversation,
            conversationId = conversationId,
            includeDelegation = false,
        )
        return buildAgentTools(
            runManager = agentRunManager,
            parentConversationId = conversationId.toString(),
            runSubAgent = { prompt ->
                val subMessages = listOf(UIMessage.user(prompt))
                var result = ""
                generationHandler.generateText(
                    settings = settings,
                    model = model,
                    messages = subMessages,
                    assistant = assistant,
                    tools = subAgentTools,
                    workspaceCwd = conversation.workspaceCwd,
                    inputTransformers = listOf(toolScenarioGuideTransformer, userPriorityTransformer, proxyInjectionTransformer),
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            result = chunk.messages.lastOrNull()?.toText().orEmpty()
                        }
                    }
                }
                result.ifBlank { "(子 Agent 无输出)" }
            },
            onSubAgentFinished = { run -> appendAgentRunReport(conversationId, run) },
            onSubAgentFailed = { run, _ -> appendAgentRunReport(conversationId, run) },
            onSubAgentHeartbeat = { run -> appendAgentHeartbeat(conversationId, run) },
        )
    }

    /**
     * 以指定对话为父上下文, 在后台运行一次性子 Agent(带完整工具集)。
     * 供定时任务调度器(scheduledTask actionType=agent)在非生成链上复用委派能力。
     * 结果写回父对话并唤醒主 Agent, 返回子 Agent 的结果文本。
     */
    suspend fun runScheduledSubAgent(conversationId: Uuid, prompt: String): String {
        val settings = settingsStore.settingsFlow.first()
        val conversation = getConversationFlow(conversationId).value ?: return "(对话不存在)"
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return "(无可用模型)"
        val subAgentTools = buildConversationTools(
            settings = settings,
            model = model,
            assistant = assistant,
            conversation = conversation,
            conversationId = conversationId,
            includeDelegation = false,
        )
        val subMessages = listOf(UIMessage.user(prompt))
        var result = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = subMessages,
            assistant = assistant,
            tools = subAgentTools,
            workspaceCwd = conversation.workspaceCwd,
            inputTransformers = listOf(toolScenarioGuideTransformer, userPriorityTransformer, proxyInjectionTransformer),
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    result = chunk.messages.lastOrNull()?.toText().orEmpty()
                }
            }
        }
        val text = result.ifBlank { "(子 Agent 无输出)" }
        appendAndWakeMainAgent(
            conversationId,
            conversation,
            "定时委派任务已完成, 结果如下:\n$text",
        )
        return text
    }

    /**
     * 把一条事件/侦测结果注入到对话并唤醒主 Agent（供定时心跳等后台任务调用）。
     * 事件以用户消息形式进入上下文, 主 Agent 据此判断是否需要主动开口/行动。
     */
    suspend fun injectHeartbeatEvent(conversationId: Uuid, text: String) {
        val conversation = getConversationFlow(conversationId).value ?: return
        appendAndWakeMainAgent(conversationId, conversation, text)
    }

    /** 把周期性侦测(心跳)的每次结果写回父对话, 并再次唤醒主 Agent 处理, 形成自我激活闭环 */
    private suspend fun appendAgentHeartbeat(conversationId: Uuid, run: AgentRun) {
        val conversation = getConversationFlow(conversationId).value ?: return
        val report = buildString {
            appendLine("定时侦测（心跳 ${run.id}）第 ${run.cycleCount} 次汇报:")
            appendLine()
            append(run.result.orEmpty())
        }
        appendAndWakeMainAgent(conversationId, conversation, report.trim())
    }

    /** 把委派任务的结果写回父对话, 并触发主模型基于结果再次总结(不直接展示原始结果) */
    private suspend fun appendAgentRunReport(conversationId: Uuid, run: AgentRun) {
        val conversation = getConversationFlow(conversationId).value ?: return
        if (run.status == AgentRunStatus.RUNNING) return
        val report = buildString {
            when (run.status) {
                AgentRunStatus.COMPLETED -> {
                    appendLine("委派任务 ${run.id} 已完成, 结果如下:")
                    appendLine()
                    append(run.result.orEmpty())
                }

                AgentRunStatus.FAILED -> {
                    appendLine("委派任务 ${run.id} 失败: ${run.error ?: "未知错误"}")
                }

                AgentRunStatus.CANCELLED -> {
                    appendLine("委派任务 ${run.id} 已取消")
                }

                AgentRunStatus.RUNNING -> return
            }
        }
        appendAndWakeMainAgent(conversationId, conversation, report.trim())
    }

    /**
     * 以用户消息形式把 [text] 追加到对话, 保存后等待当前生成自然结束再触发主模型总结。
     * 该追加文本会进入后续主 Agent 的上下文, 从而让主 Agent 据此行动/汇报给用户。
     */
    private suspend fun appendAndWakeMainAgent(conversationId: Uuid, conversation: Conversation, text: String) {
        val updated = conversation.copy(
            messageNodes = conversation.messageNodes + UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(text)),
            ).toMessageNode(),
            updateAt = Instant.now(),
        )
        updateConversation(conversationId, updated)
        saveConversation(conversationId, updated)

        // 委派在后台运行期间主模型可能仍在生成(如继续写代码、正在流式填充工具参数)。
        // 绝不能 cancel 打断当前生成(会导致工具参数传不全、API 拒绝)。
        // 正确做法: 若正在生成, 先等待当前生成自然完成, 再基于包含委派结果的最新对话触发总结。
        val session = getOrCreateSession(conversationId)
        appScope.launch {
            try {
                // 等待当前生成自然结束(不打断流式输出); 若未在生成则立即继续
                session.generationJob.value?.join()
                handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    private suspend fun compressPageContent(toolParams: JsonObject, content: String): String {
        if (content.isBlank()) return content
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: return content
        val provider = model.findProvider(settings.providers) ?: return content
        return runCatching {
            val providerHandler = providerManager.getProviderByType(provider)
            val prompt = buildString {
                appendLine("You are a web page content extractor for an AI assistant.")
                appendLine("The tool call parameters (what the caller asked to do):")
                appendLine(toolParams.toString())
                appendLine()
                appendLine("Below is the raw scraped/fetched page content. Extract the information the caller asked for, summarize it concisely, keep important facts, numbers and URLs, and output clean readable text.")
                appendLine()
                appendLine("=== PAGE CONTENT ===")
                appendLine(content)
            }
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )
            result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotBlank() } ?: content
        }.getOrElse { content }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        // 停止当前生成并清空待发送队列
        sessions[conversationId]?.clearPendingSends()
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
