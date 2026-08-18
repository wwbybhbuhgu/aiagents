package com.aiagents.data.ai.tools

import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.db.dao.ScheduledTaskDAO
import com.aiagents.data.db.entity.ScheduledTaskEntity
import com.aiagents.data.repository.ConversationRepository
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.data.model.toMessageNode
import com.aiagents.service.ChatService
import com.aiagents.service.NotificationItem
import com.aiagents.service.NotificationRepository
import com.aiagents.utils.hasUsageStatsPermission
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/**
 * App 内定时任务调度器。
 *
 * 每 30 秒检查一次到期任务并执行：
 * - actionType=command: 在工作区容器内执行命令
 * - actionType=agent: 委派子 Agent 后台执行提示词
 * 执行结果写入工作区 /tool_outputs/<taskId>-<ts>.txt(不直接作为消息发给 AI),
 * 模型可通过 cron_get 工具查看结果文件路径, 再用 workspace 工具读取。
 */
class TaskScheduler(
    private val scope: CoroutineScope,
    private val dao: ScheduledTaskDAO,
    private val workspaceRepository: WorkspaceRepository,
    private val conversationRepo: ConversationRepository,
    private val agentRunManager: AgentRunManager,
    private val chatService: ChatService,
    private val context: android.content.Context,
) {
    private var job: Job? = null

    // 每个心跳任务上一次的设备快照基线(仅内存, 进程重启后重新建基线, 首轮静默不打扰)
    private val lastHeartbeat = java.util.concurrent.ConcurrentHashMap<String, HeartbeatSnapshot>()
    // 已提示过"通知使用权未开启"的任务 id, 避免每 tick 重复打扰
    private val notificationWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { checkDueTasks() }
                    .onFailure { it.printStackTrace() }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun checkDueTasks() {
        val now = System.currentTimeMillis()
        val due = dao.getDue(now)
        for (task in due) {
            runCatching { executeTask(task) }
                .onFailure { appendTaskLog(task, "⚠️ 定时任务 ${task.name} 执行失败: ${it.message}") }
            val next = runCatching { CronParser.parseNext(task.schedule, System.currentTimeMillis()) }
                .getOrDefault(now + 60_000L)
            dao.upsert(task.copy(lastRunAt = System.currentTimeMillis(), nextRunAt = next))
        }
    }

    private suspend fun executeTask(task: ScheduledTaskEntity) {
        val fileName = "${task.id}-${System.currentTimeMillis()}.txt"
        val outputFile = writeTaskOutput(task, "▶️ 定时任务 ${task.name} 开始执行", fileName)
        when (task.actionType) {
            "command" -> {
                val workspaceId = task.workspaceId ?: return
                val result = workspaceRepository.executeCommand(workspaceId, task.action)
                val output = result.stderr.ifBlank { result.stdout }.trim().take(4000)
                writeTaskOutput(
                    task,
                    "✅ 定时任务 ${task.name} 完成 (exit=${result.exitCode})\n" +
                        if (output.isBlank()) "(无输出)" else output,
                    fileName,
                    append = true,
                )
                appendTaskLog(task, "定时任务 ${task.name} 完成, 输出见 $outputFile")
            }

            "agent" -> {
                writeTaskOutput(task, "▶️ 定时任务 ${task.name} 委派子 Agent 后台执行", fileName, append = true)
                val prompt = task.action
                agentRunManager.launch(
                    parentConversationId = task.conversationId,
                    prompt = prompt,
                    runBlock = {
                        val result = chatService.runScheduledSubAgent(
                            conversationId = Uuid.parse(task.conversationId),
                            prompt = prompt,
                        )
                        writeTaskOutput(
                            task,
                            "✅ 定时任务 ${task.name} 子 Agent 完成\n$result",
                            fileName,
                            append = true,
                        )
                        appendTaskLog(task, "定时任务 ${task.name} 子 Agent 完成, 输出见 $outputFile")
                        result
                    },
                )
                appendTaskLog(task, "定时任务 ${task.name} 已委派, 结果将在子 Agent 完成后写入 $outputFile")
            }

            "heartbeat" -> {
                runHeartbeat(task)
            }

            else -> {}
        }
        // 记录本次输出文件名, 供 cron_get 定位
        dao.upsert(task.copy(lastOutputFile = fileName))
    }

    /**
     * 轻量心跳: 每个 tick 只做便宜的 Kotlin 侧设备状态检查(不做 LLM 推理)。
     * 只有检测到"值得注意的变化"(前台 App 切换 / 屏幕亮起 / 新通知到达) 时,
     * 才把事件注入对话并唤醒主 Agent —— 事件驱动, 而非每 tick 烧一次完整推理。
     *
     * 通知事件支持过滤: 任务 `action` 可选填 JSON 配置, 如
     * `{"notifications":true,"packages":["com.tencent.mm"],"keywords":["宝贝"]}`,
     * 只关心指定 App 或含关键词的通知; 未配置时捕获所有新通知(排除本应用自身)。
     */
    private suspend fun runHeartbeat(task: ScheduledTaskEntity) {
        val snapshot = snapshotDeviceState() ?: return
        val previous = lastHeartbeat.get(task.id)
        lastHeartbeat[task.id] = snapshot

        // 首次运行无基线: 静默记录, 不打扰
        if (previous == null) return

        val filter = parseNotificationFilter(task.action)
        val piece = buildList<String> {
            if (snapshot.screenOn != previous.screenOn) {
                add(if (snapshot.screenOn) "屏幕刚刚亮起(用户解锁/点亮屏幕)" else "屏幕已熄灭")
            }
            if (snapshot.foregroundPackage.isNotBlank() &&
                snapshot.foregroundPackage != previous.foregroundPackage
            ) {
                add("当前前台 App 切换为: ${snapshot.foregroundPackage}")
            }
            if (filter.watchNotifications) {
                if (!NotificationRepository.connected) {
                    // 通知监听未开启: 只提示一次, 避免每 tick 重复打扰
                    val warned = notificationWarned.contains(task.id)
                    if (!warned) {
                        notificationWarned.add(task.id)
                        add("通知监控已配置, 但\"通知使用权\"未开启, 无法捕获新通知(需在系统设置中允许本应用读取通知)")
                    }
                } else {
                    val newOnes = NotificationRepository
                        .since(previous.notificationSeq, excludePackage = context.packageName)
                        .filter { filter.matches(it) }
                    if (newOnes.isNotEmpty()) {
                        val lines = newOnes.joinToString("\n") { item ->
                            val label = item.title.ifBlank { item.packageName }
                            val body = item.text.ifBlank { "" }
                            if (body.isBlank()) "· $label" else "· $label: $body"
                        }
                        add("收到新通知(${newOnes.size}条):\n$lines")
                    }
                }
            }
        }
        if (piece.isEmpty()) return

        val event = "【心跳事件】\n" + piece.joinToString("\n")
        writeTaskOutput(task, "♥️ ${event}", "${task.id}-${System.currentTimeMillis()}.txt", append = false)
        // 事件驱动: 注入父对话并唤醒主 Agent, 由主 Agent 决定是否主动开口/行动
        chatService.injectHeartbeatEvent(Uuid.parse(task.conversationId), event)
    }

    /** 读取用户当前设备状态(轻量, 不做任何推理)。需要 Usage access, 否则返回 null 跳过本轮。 */
    private fun snapshotDeviceState(): HeartbeatSnapshot? {
        if (!context.hasUsageStatsPermission()) return null
        val now = System.currentTimeMillis()
        val usageStatsManager =
            context.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val events = usageStatsManager.queryEvents(now - HEARTBEAT_LOOKBACK_MS, now)
        val event = android.app.usage.UsageEvents.Event()

        var foreground: String? = null
        var screenOn = true
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foreground = event.packageName
                    screenOn = true
                }

                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (foreground == event.packageName) {
                        foreground = null
                    }
                }

                android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    foreground = null
                }
            }
        }
        return HeartbeatSnapshot(
            foregroundPackage = foreground ?: "",
            screenOn = screenOn,
            notificationSeq = NotificationRepository.latestSeq,
            timestamp = now,
        )
    }

    /** 把任务输出写入工作区 /tool_outputs/ 目录文件, 返回容器内路径 */
    private suspend fun writeTaskOutput(
        task: ScheduledTaskEntity,
        content: String,
        fileName: String,
        append: Boolean = false,
    ): String {
        val dir = java.io.File(context.filesDir, "tool_outputs").apply { mkdirs() }
        val file = java.io.File(dir, fileName)
        if (append && file.exists()) {
            file.appendText("\n$content\n")
        } else {
            file.writeText(content)
        }
        return "/tool_outputs/$fileName"
    }

    /** 把调度日志作为助手消息追加到所属对话 */
    private suspend fun appendTaskLog(task: ScheduledTaskEntity, log: String) {
        runCatching {
            val conversation = conversationRepo.getConversationById(Uuid.parse(task.conversationId))
                ?: return
            val updated = conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(log)),
                ).toMessageNode(),
                updateAt = Instant.now(),
            )
            conversationRepo.updateConversation(updated)
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_LOOKBACK_MS = 30 * 60 * 1000L
    }
}

/** 心跳基线快照: 记录一次 tick 时的设备状态, 用于与下次比对找出值得注意的变化 */
private data class HeartbeatSnapshot(
    val foregroundPackage: String,
    val screenOn: Boolean,
    val notificationSeq: Long,
    val timestamp: Long,
)

/** 通知事件过滤配置: 解析自心跳任务的 `action` 字段(JSON), 非法/为空时退化为不过滤 */
private data class NotificationFilter(
    val watchNotifications: Boolean,
    val packages: Set<String>,
    val keywords: List<String>,
) {
    fun matches(item: NotificationItem): Boolean {
        if (packages.isEmpty() && keywords.isEmpty()) return true
        if (packages.isNotEmpty() && item.packageName in packages) return true
        if (keywords.isEmpty()) return false
        val haystack = "${item.title} ${item.text}".lowercase()
        return keywords.any { it in haystack }
    }
}

private fun parseNotificationFilter(action: String): NotificationFilter {
    val config = runCatching {
        kotlinx.serialization.json.Json
            .parseToJsonElement(action.trim())
            .jsonObject
    }.getOrNull()
    if (config == null) return NotificationFilter(watchNotifications = false, packages = emptySet(), keywords = emptyList())
    val watch = config["notifications"]?.jsonPrimitive?.booleanOrNull == true
    val packages = config["packages"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet().orEmpty()
    val keywords = config["keywords"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?.map { it.lowercase() }.orEmpty()
    return NotificationFilter(watchNotifications = watch, packages = packages, keywords = keywords)
}
