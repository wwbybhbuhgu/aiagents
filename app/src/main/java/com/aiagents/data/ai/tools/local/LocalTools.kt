package com.aiagents.data.ai.tools.local

import android.content.Context
import com.aiagents.ai.core.Tool
import com.aiagents.data.automation.AutoAccessibilityController
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.event.AppEventBus
import com.aiagents.tts.provider.TTSManager
import java.io.File

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: com.aiagents.data.repository.WorkspaceRepository,
) {
    val javascriptTool by lazy {
        val proxyAddr: String? = try {
            val pm = org.koin.java.KoinJavaComponent.get<com.aiagents.data.proxy.ProxyManager>(
                com.aiagents.data.proxy.ProxyManager::class.java
            )
            pm.localProxyAddress
        } catch (_: Exception) { null }
        buildJavascriptTool(context, workspaceRepository, proxyAddr)
    }

    private val pathResolver by lazy { WorkspacePathResolver(context, workspaceRepository) }

    val backgroundProcessManager by lazy {
        val mgr = BackgroundProcessManager()
        try {
            val pm = org.koin.java.KoinJavaComponent.get<com.aiagents.data.proxy.ProxyManager>(
                com.aiagents.data.proxy.ProxyManager::class.java
            )
            mgr.proxyEnv = pm.proxyEnv()
        } catch (_: Exception) {}
        mgr
    }
    val backgroundShellTools by lazy {
        buildBackgroundShellTools(backgroundProcessManager) {
            runCatching {
                val ws = kotlinx.coroutines.runBlocking { workspaceRepository.getDefaultWorkspace() }
                if (ws != null) File(context.filesDir, "workspaces/${ws.id}/files").also { it.mkdirs() }
                else null
            }.getOrDefault(null)
        }
    }
    val nodeProcessManager by lazy { NodeProcessManager(context) }
    val backgroundNodeTools by lazy {
        buildBackgroundNodeTools(nodeProcessManager, workspaceRepository, context)
    }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore, workspaceRepository) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val deviceInfoTool by lazy { buildDeviceInfoTool(context, eventBus) }

    val screenTextTool by lazy { buildScreenTextTool(context, eventBus) }

    val sendNotificationTool by lazy { buildSendNotificationTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val autoReadScreenTool by lazy { buildAutoReadScreenTool(context, eventBus) }

    val autoClickTool by lazy { buildAutoClickTool(context, eventBus) }

    val autoInputTool by lazy { buildAutoInputTool(context, eventBus) }

    val autoSwipeTool by lazy { buildAutoSwipeTool(context, eventBus) }

    val autoScrollTool by lazy { buildAutoScrollTool(context, eventBus) }

    val autoScreenshotTool by lazy { buildAutoScreenshotTool(context, eventBus) }

    val autoShellTool by lazy { buildAutoShellTool(context, eventBus) }

    val appListTool by lazy { buildAppListTool(context) }

    val appLaunchTool by lazy { buildAppLaunchTool(context) }

    val activityTool by lazy { buildActivityTool(context) }

    val stopAppTool by lazy { buildStopAppTool(context) }

    val openUrlTool by lazy { buildOpenUrlTool(context) }

    val browserTools by lazy { buildBrowserTools(context, eventBus) }

    val floatingWindowTool by lazy { buildFloatingWindowTool() }

    val waitTool by lazy { buildWaitTool() }

    val keyboardInputTool by lazy { buildKeyboardInputTool(context) }

    val restoreImeTool by lazy { buildRestoreImeTool(context) }

    val openDialerTool by lazy { buildOpenDialerTool(context) }

    val volumeControlTool by lazy { buildVolumeControlTool(context) }

    val mediaControlTool by lazy { buildMediaControlTool(context) }

    val setBrightnessTool by lazy { buildSetBrightnessTool(context) }

    val vibrateTool by lazy { buildVibrateTool(context) }

    val flashlightTool by lazy { buildFlashlightTool(context, eventBus) }

    val wifiToggleTool by lazy { buildWifiToggleTool(context) }

    val notificationShadeTool by lazy { buildNotificationShadeTool(context) }

    val installApkTool by lazy { buildInstallApkTool(context, pathResolver) }

    val setWallpaperTool by lazy { buildSetWallpaperTool(context, pathResolver) }

    val sendSmsTool by lazy { buildSendSmsTool(context, eventBus) }

    val readContactsTool by lazy { buildReadContactsTool(context, eventBus) }

    val getLocationTool by lazy { buildGetLocationTool(context, eventBus) }

    val phoneCallTool by lazy { buildPhoneCallTool(context, eventBus) }

    fun getTools(options: List<LocalToolOption>, assistantId: String? = null): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.DeviceInfo)) {
            tools.add(deviceInfoTool)
        }
        if (options.contains(LocalToolOption.ScreenText)) {
            tools.add(screenTextTool)
        }
        if (options.contains(LocalToolOption.SendNotification)) {
            tools.add(sendNotificationTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.Automation)) {
            tools.add(autoReadScreenTool)
            tools.add(autoClickTool)
            tools.add(autoInputTool)
            tools.add(autoSwipeTool)
            tools.add(autoScrollTool)
            tools.add(buildAutoKeyTool("auto_back", "Press the system back button.", AutoAccessibilityController::pressBack))
            tools.add(buildAutoKeyTool("auto_home", "Go to the home screen.", AutoAccessibilityController::pressHome))
            tools.add(buildAutoKeyTool("auto_enter", "Press enter / submit the focused field.", AutoAccessibilityController::pressEnter))
            tools.add(autoScreenshotTool)
            tools.add(autoShellTool)
            tools.add(appListTool)
            tools.add(appLaunchTool)
            tools.add(activityTool)
            tools.add(stopAppTool)
            tools.add(openUrlTool)
            tools.add(openDialerTool)
            tools.add(volumeControlTool)
            tools.add(mediaControlTool)
            tools.add(setBrightnessTool)
            tools.add(vibrateTool)
            tools.add(flashlightTool)
            tools.add(wifiToggleTool)
            tools.add(notificationShadeTool)
            tools.add(installApkTool)
            tools.add(setWallpaperTool)
            tools.add(sendSmsTool)
            tools.add(readContactsTool)
            tools.add(getLocationTool)
            tools.add(phoneCallTool)
            tools.add(keyboardInputTool)
            tools.add(restoreImeTool)
        }
        tools.add(floatingWindowTool)
        tools.add(waitTool)
        if (options.contains(LocalToolOption.LocalSpeechRecognition)) {
            tools.add(buildLocalSpeechRecognitionTool(context, pathResolver, workspaceRepository))
        }
        if (options.contains(LocalToolOption.AgentState)) {
            tools.addAll(buildAgentStateTools(context, assistantId))
        }
        if (options.contains(LocalToolOption.ForegroundApp)) {
            tools.add(buildForegroundAppTool(context, eventBus))
        }
        // 后台进程工具 (始终可用)
        tools.addAll(backgroundShellTools)
        tools.addAll(backgroundNodeTools)
        return tools
    }
}
