package com.aiagents.ui.components.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiagents.data.model.Conversation
import com.aiagents.R
import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.FloatingWindowController
import com.aiagents.data.datastore.getCurrentAssistant
import com.aiagents.data.datastore.getSelectedASRProvider
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.data.repository.ConversationRepository
import com.aiagents.service.ChatService
import com.aiagents.service.ScreenReaderOverlayService
import com.aiagents.service.ScreenTextRepository
import com.aiagents.ui.components.message.ChatMessageReasoningStep
import com.aiagents.ui.components.message.ChatMessageToolStep
import com.aiagents.ui.components.message.MessagePartBlock
import com.aiagents.ui.components.message.ThinkingStep
import com.aiagents.ui.components.message.groupMessageParts
import com.aiagents.ui.components.richtext.MarkdownBlock
import com.aiagents.ui.theme.LocalOverlaySafe
import com.aiagents.ui.context.LocalSettings
import com.aiagents.ui.hooks.readStringPreference
import com.aiagents.ui.hooks.writeStringPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aiagents.asr.ASRState
import com.aiagents.asr.ASRStatus
import com.aiagents.asr.ASRProviderSetting
import com.aiagents.asr.providers.SherpaNcnnASRController
import kotlinx.coroutines.isActive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Conversation
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.Maximize01
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.Minimize01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Voice
import org.koin.compose.koinInject
import org.koin.java.KoinJavaComponent
import kotlin.uuid.Uuid

class OverlayState(
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onFocusToggle: (focusable: Boolean) -> Unit,
    private val onResize: ((width: Int, height: Int) -> Unit)? = null,
) {
    fun dragBy(dx: Int, dy: Int) = onDrag(dx, dy)
    fun resizeTo(width: Int, height: Int) = onResize?.invoke(width, height)
    fun requestFocus() = onFocusToggle(true)
    fun releaseFocus() = onFocusToggle(false)
}

@Composable
fun ScreenReaderFloatingWindow() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var showPermissionDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                startOverlayService(context)
            }
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                stopOverlayService(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!Settings.canDrawOverlays(context)) {
            showPermissionDialog = true
        }
    }

    if (showPermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.screen_reader_permission_title)) },
            text = { Text(stringResource(R.string.screen_reader_permission_desc)) },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }) {
                    Text(stringResource(R.string.screen_reader_permission_grant))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun startOverlayService(context: Context) {
    if (!Settings.canDrawOverlays(context)) return
    runCatching {
        context.startForegroundService(
            Intent(context, ScreenReaderOverlayService::class.java)
                .setAction(ScreenReaderOverlayService.ACTION_START)
        )
    }
}

private fun stopOverlayService(context: Context) {
    runCatching {
        context.startService(
            Intent(context, ScreenReaderOverlayService::class.java)
                .setAction(ScreenReaderOverlayService.ACTION_STOP)
        )
    }
}

@Composable
fun ScreenReaderOverlayContent(context: Context, overlayState: OverlayState) {
    val chatService = remember { KoinJavaComponent.get<ChatService>(ChatService::class.java) }
    val eventBus = koinInject<AppEventBus>()

    var message by remember { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var pendingAsrStart by remember { mutableStateOf(false) }
    // 悬浮窗录音真正状态跟随 MicForegroundService
    val micRecording by com.aiagents.service.MicForegroundSession.recording.collectAsState()
    val floatingAsrSilenceSeconds = LocalSettings.current.displaySetting.floatingAsrSilenceSeconds
    val recordAudioLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("OverlayFl", "permission callback: granted=$granted pendingAsrStart=$pendingAsrStart")
        if (granted && pendingAsrStart) {
            pendingAsrStart = false
            val cid = conversationId?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
            com.aiagents.service.MicForegroundService.sendAsrStart(context, floatingAsrSilenceSeconds, cid)
        } else {
            pendingAsrStart = false
        }
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val window = FloatingWindowController.get(FloatingWindowController.CHAT)
    val minimized = window?.isMinimized ?: false

    if (minimized) {
        // 最小化: 只显示一个小圆角药丸, 可拖动, 点击恢复
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 10.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            overlayState.dragBy(dragAmount.x.toInt(), dragAmount.y.toInt())
                        }
                    },
            ) {
                IconButton(
                    onClick = { window?.setMinimizedState(false) },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        HugeIcons.BubbleChat,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        return
    }

    val currentConvId = conversationId?.let { id ->
        runCatching { Uuid.parse(id) }.getOrNull()
    }

    // 解析当前对话: 有记录则恢复, 否则默认新建一个对话(像主界面)
    LaunchedEffect(Unit) {
        val lastId = context.readStringPreference("lastConversationId")
        val target = lastId?.takeIf { it.isNotBlank() }
        if (target != null) {
            conversationId = target
        } else {
            val newId = runCatching { chatService.createNewConversation() }.getOrNull()
            if (newId != null) {
                context.writeStringPreference("lastConversationId", newId.toString())
                conversationId = newId.toString()
            }
        }
    }

    // 维持会话存活并加载历史(防止空闲后 session 被回收, 导致消息/标题不刷新)
    LaunchedEffect(currentConvId) {
        val id = currentConvId ?: return@LaunchedEffect
        runCatching { chatService.initializeConversation(id) }
    }
    DisposableEffect(currentConvId) {
        val id = currentConvId
        if (id != null) runCatching { chatService.addConversationReference(id) }
        onDispose {
            if (id != null) runCatching { chatService.removeConversationReference(id) }
        }
    }

    val conversation = currentConvId?.let { id ->
        runCatching { chatService.getConversationFlow(id) }.getOrNull()
    }

    val conv by conversation?.collectAsState() ?: remember { mutableStateOf(null) }

    val messageNodes = conv?.messageNodes ?: emptyList()

    // 是否已在底部: 贴底判定兼顾两种情形
    // 1) 内容未超出视口(canScrollForward=false) → 无下方内容可滚, 视为贴底;
    // 2) 末条消息超出视口高度时 → 仅当末条消息底部与视口底距离 <= 阈值才算贴底。
    // 用像素距离替代 canScrollForward 作为"末条消息在底部"的判据,
    // 使流式输出中末条消息变高时仍能被正确识别为贴底, 从而持续跟随。
    val bottomSnapPx = LocalDensity.current.let { with(it) { 16.dp.toPx() } }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIndex = messageNodes.lastIndex
            if (lastIndex < 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            if (lastVisible == null || lastVisible.index < lastIndex) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val gap = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
            gap <= bottomSnapPx
        }
    }

    val generationJob = conversationId?.let { id ->
        runCatching { chatService.getGenerationJobStateFlow(Uuid.parse(id)) }.getOrNull()
    }

    val activeJob by generationJob?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isGenerating = activeJob != null

    // ---- 对话选择 ----
    val conversationRepo = remember {
        KoinJavaComponent.get<ConversationRepository>(ConversationRepository::class.java)
    }
    var showSelector by remember { mutableStateOf(false) }
    var creatingConversation by remember { mutableStateOf(false) }

    fun selectConversation(id: Uuid) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { chatService.initializeConversation(id) }
            withContext(Dispatchers.Main) {
                conversationId = id.toString()
                context.writeStringPreference("lastConversationId", id.toString())
                showSelector = false
            }
        }
    }

    val assistantId = conv?.assistantId ?: LocalSettings.current.getCurrentAssistant().id
    val conversations by remember(assistantId) {
        conversationRepo.getConversationsOfAssistant(assistantId)
    }.collectAsState(initial = emptyList())

// 自动跟随最新: 在底部时跟随新消息, 用户上翻时停止, 回到底部时恢复。
// 同时监听 atBottom(用户滑回底部)和 messageNodes.size(新消息到达), 任一变化时触发。
LaunchedEffect(Unit) {
    snapshotFlow { atBottom to messageNodes.size }.collect { (isAtBottom, count) ->
        if (isAtBottom && count > 0 && !listState.isScrollInProgress) {
            listState.requestScrollToItem(messageNodes.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }
}

    Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Title bar: drag strip (title text) separate from buttons so the
            // drag detector does not swallow the IconButton clicks.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                overlayState.dragBy(dragAmount.x.toInt(), dragAmount.y.toInt())
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                IconButton(
                    onClick = { showSelector = !showSelector },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        HugeIcons.Conversation,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (showSelector) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { window?.setMinimizedState(true) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        HugeIcons.MinusSign,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { openMainActivity(context) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        HugeIcons.Home01,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = {
                        runCatching {
                            context.stopService(
                                Intent(context, ScreenReaderOverlayService::class.java)
                            )
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        HugeIcons.Cancel01,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (showSelector) {
                ConversationSelectorPanel(
                    conversations = conversations,
                    currentId = conversationId,
                    creating = creatingConversation,
                    onSelect = { selectConversation(it) },
                    onCreate = {
                        if (!creatingConversation) {
                            creatingConversation = true
                            CoroutineScope(Dispatchers.IO).launch {
                                val id = runCatching { chatService.createNewConversation() }.getOrNull()
                                withContext(Dispatchers.Main) {
                                    creatingConversation = false
                                    if (id != null) {
                                        conversationId = id.toString()
                                        context.writeStringPreference("lastConversationId", id.toString())
                                        showSelector = false
                                    }
                                }
                            }
                        }
                    },
                )
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messageNodes.size) { idx ->
                    val node = messageNodes[idx]
                    val selectedMessage = node.messages.getOrNull(node.selectIndex)
                    val isUser = node.role == MessageRole.USER
                    OverlayMessageContent(
                        parts = selectedMessage?.parts ?: emptyList(),
                        isUser = isUser,
                        isGenerating = isGenerating,
                    )
                }

                if (isGenerating) {
                    item {
                        if (LocalSettings.current.displaySetting.showAssistantBubble) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                    Text("...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        }
                    }
                }
            }

            // Bottom bar: input area (when toggled) above the action buttons.
            // Buttons stay at the very bottom and are never hidden; the whole
            // block is wrapped in imePadding() so it rides above the IME.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                // Input area (only when toggled on)
                if (showInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            placeholder = {
                                Text(stringResource(R.string.screen_reader_input_hint), style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            ),
                        )

                        IconButton(
                            onClick = {
                                if (message.isNotBlank()) {
                                    sendToAi(context, message, currentConvId)
                                    message = ""
                                    showInput = false
                                    overlayState.releaseFocus()
                                }
                            },
                            enabled = message.isNotBlank(),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (message.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                        ) {
                            Icon(
                                HugeIcons.ArrowUp02,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = if (message.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Action buttons row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                // Screen text button
                IconButton(
                    onClick = {
                        if (ScreenTextRepository.isConnected()) {
                            val snapshots = ScreenTextRepository.latestText(3)
                            val text = snapshots.joinToString("\n") { it.texts.joinToString("\n") }
                            if (text.isNotBlank()) {
                                sendToAi(context, "当前屏幕内容:\n$text", currentConvId)
                            }
                        } else {
                            CoroutineScope(Dispatchers.Main).launch {
                                eventBus.emit(AppEvent.OpenAccessibilitySettings)
                            }
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.GlobalSearch,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Toggle input area button
                IconButton(
                    onClick = {
                        showInput = !showInput
                        if (showInput) {
                            overlayState.requestFocus()
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            overlayState.releaseFocus()
                            message = ""
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.Keyboard,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (showInput) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // ASR mic button (跟随主界面 ASR 配置显示)
                if (LocalSettings.current.isAsrConfigured()) {
                IconButton(
                        onClick = {
                            android.util.Log.d("OverlayFl", "mic tap: micRecording=$micRecording")
                            if (micRecording) {
                                com.aiagents.service.MicForegroundService.sendAsrStop(context)
                                return@IconButton
                            }
                            // 悬浮窗运行在 Service 中, ActivityResultRegistry 的回调不会回到本进程。
                            // 已授权时直接指令 MicForegroundService (前台已启动) 开启录音;
                            // 未授权时仍尝试系统授权页(带 Activity 时可用)。
                            val granted = com.aiagents.service.MicForegroundService.hasMicPermission(context)
                            android.util.Log.d("OverlayFl", "mic tap: granted=$granted")
                            if (granted) {
                                com.aiagents.service.MicForegroundService.sendAsrStart(
                                    context, floatingAsrSilenceSeconds, currentConvId
                                )
                            } else {
                                pendingAsrStart = true
                                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
Icon(
                        HugeIcons.Voice,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (micRecording) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                }
            }
        }
    }

        if (!showInput) {
            Box(Modifier.fillMaxSize()) {
                OverlayResizeHandle(
                    window = window,
                    overlayState = overlayState,
                )
            }
        }
    }
}

@Composable
private fun ConversationSelectorPanel(
    conversations: List<Conversation>,
    currentId: String?,
    creating: Boolean,
    onSelect: (Uuid) -> Unit,
    onCreate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.screen_reader_select_conversation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onCreate,
                    enabled = !creating,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    } else {
                        Icon(
                            HugeIcons.PlusSign,
                            null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(R.string.history_page_new_conversation),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_reader_no_conversation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 180.dp),
                ) {
                    items(conversations.size) { idx ->
                        val conversation = conversations[idx]
                        val selected = conversation.id.toString() == currentId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onSelect(conversation.id) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = conversation.title.ifBlank {
                                    stringResource(R.string.history_page_new_conversation)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.OverlayResizeHandle(
    window: FloatingWindowController.Window?,
    overlayState: OverlayState,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(28.dp)
            .clip(RoundedCornerShape(topStart = 10.dp))
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(window?.id) {
                var startWidth = window?.width ?: 0
                var startHeight = window?.height ?: 0
                var accX = 0f
                var accY = 0f
                detectDragGestures(
                    onDragStart = {
                        startWidth = window?.width ?: 0
                        startHeight = window?.height ?: 0
                        accX = 0f
                        accY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x
                        accY += dragAmount.y
                        overlayState.resizeTo(
                            startWidth + accX.toInt(),
                            startHeight + accY.toInt(),
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            HugeIcons.Maximize01,
            null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private fun openMainActivity(context: Context) {
    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return@runCatching
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        context.startActivity(intent)
    }
}

private fun sendToAi(context: Context, text: String, conversationId: Uuid?) {
    if (conversationId == null) return
    CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            val chatService = KoinJavaComponent.get<ChatService>(ChatService::class.java)
            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(text)),
                answer = true,
            )
        }
    }
}

@Composable
private fun OverlayMessageContent(
    parts: List<UIMessagePart>,
    isUser: Boolean,
    isGenerating: Boolean,
) {
    if (isUser) {
        val text = parts.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> stringResource(R.string.screen_reader_image_label)
                is UIMessagePart.Document -> stringResource(R.string.screen_reader_file_label, part.fileName)
                else -> null
            }
        }.joinToString("\n")
        if (text.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    MarkdownBlock(
                        content = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        return
    }

    val groupedParts = remember(parts) { parts.groupMessageParts() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groupedParts.forEach { block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> {
                    if (block.steps.isNotEmpty()) {
                        val isReasoningOnly = block.steps.all { it is ThinkingStep.ReasoningStep }
                        ChainOfThought(
                            modifier = Modifier.fillMaxWidth(),
                            cardColors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            steps = block.steps,
                            collapsedVisibleCount = 3,
                            collapsedAdaptiveWidth = isReasoningOnly,
                        ) { step ->
                            when (step) {
                                is ThinkingStep.ReasoningStep -> {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = null,
                                        assistant = null,
                                        collapsedAdaptiveWidth = isReasoningOnly,
                                    )
                                }

                                is ThinkingStep.ToolStep -> {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = isGenerating && !step.tool.isExecuted,
                                        overlaySafe = true,
                                    )
                                }
                            }
                        }
                    }
                }

                is MessagePartBlock.ContentBlock -> {
                    when (val part = block.part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    val showBubble = LocalSettings.current.displaySetting.showAssistantBubble
                                    Surface(
                                        shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
                                        color = if (showBubble) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                        },
                                        modifier = Modifier.widthIn(max = 280.dp),
                                    ) {
                                        CompositionLocalProvider(
                                            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                                            LocalOverlaySafe provides true,
                                        ) {
                                            MarkdownBlock(
                                                content = part.text,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is UIMessagePart.Image -> {
                            Text(
                                text = stringResource(R.string.screen_reader_image_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is UIMessagePart.Document -> {
                            Text(
                                text = stringResource(R.string.screen_reader_file_label, part.fileName),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
    }

/** 跟随主界面: 已配置可用的 ASR provider 时才显示悬浮窗录音按钮 */
private fun com.aiagents.data.datastore.Settings.isAsrConfigured(): Boolean {
    val provider = getSelectedASRProvider() ?: return false
    return when (provider) {
        is ASRProviderSetting.LocalStreamingSTT -> true
        is ASRProviderSetting.OpenAIRealtime -> provider.apiKey.isNotBlank()
        is ASRProviderSetting.DashScope -> provider.apiKey.isNotBlank()
        is ASRProviderSetting.Volcengine -> provider.apiKey.isNotBlank()
        is ASRProviderSetting.MiMo -> provider.apiKey.isNotBlank()
        is ASRProviderSetting.Step -> provider.apiKey.isNotBlank()
    }
}
