package com.aiagents.data.ai.tools.local

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.utils.hasUsageStatsPermission
import java.time.Instant

/**
 * 前台 App 检测工具：轻量、不做完整推理, 供心跳/定期侦测低成本地判断
 * "用户现在正在用什么"。基于 UsageStatsManager 最近的前台/后台事件。
 */
internal fun buildForegroundAppTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_foreground_app",
    description = """
        Get the app currently in the foreground (what the user is using right now).
        Lightweight and fast, suitable for periodic heartbeat monitoring.
        Returns the foreground package, app name, and when it came to the foreground.
        Requires the 'Usage access' special permission; if not granted, the device usage
        access settings page is opened automatically and an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    needsApproval = { false },
    execute = {
        if (!context.hasUsageStatsPermission()) {
            eventBus.emit(AppEvent.OpenUsageAccessSettings)
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Usage access permission is not granted. The system settings page has been " +
                        "opened; please ask the user to enable 'Usage access' for this app and try again."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 只看最近几分钟的事件, 找到最新一条"进入前台"即当前前台 App; 覆盖息屏/连续使用足够
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()

        var foregroundPkg: String? = null
        var foregroundTime: Long? = null
        var screenOn = true

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foregroundPkg = event.packageName
                    foregroundTime = event.timeStamp
                    screenOn = true
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    // 系统全局前台被移走(如回家键), 之后可能停在桌面, 由下一轮 MOVE_TO_FOREGROUND 重新判定
                    if (foregroundPkg == event.packageName) {
                        foregroundPkg = null
                        foregroundTime = null
                    }
                }

                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    foregroundPkg = null
                    foregroundTime = null
                }
            }
        }

        val pm = context.packageManager
        val payload = buildJsonObject {
            put("screen_on", screenOn)
            put("foreground_package", foregroundPkg ?: "")
            put(
                "foreground_app",
                foregroundPkg?.let { resolveAppName(pm, it) } ?: "(none)"
            )
            foregroundTime?.let { put("since_wall_clock_iso", Instant.ofEpochMilli(it).toString()) }
            foregroundTime?.let { put("since_epoch_ms", it) }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private const val LOOKBACK_MS = 30 * 60 * 1000L // 回看 30 分钟足够还原当前前台

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}