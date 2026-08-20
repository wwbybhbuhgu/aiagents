package com.aiagents.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("device_info")
    data object DeviceInfo : LocalToolOption()

    @Serializable
    @SerialName("screen_text")
    data object ScreenText : LocalToolOption()

    @Serializable
    @SerialName("send_notification")
    data object SendNotification : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("todo")
    data object Todo : LocalToolOption()

    @Serializable
    @SerialName("web_fetch")
    data object WebFetch : LocalToolOption()

    @Serializable
    @SerialName("agent")
    data object Agent : LocalToolOption()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption()

    @Serializable
    @SerialName("image_analysis")
    data object ImageAnalysis : LocalToolOption()

    @Serializable
    @SerialName("automation")
    data object Automation : LocalToolOption()

    @Serializable
    @SerialName("local_speech_recognition")
    data object LocalSpeechRecognition : LocalToolOption()

    @Serializable
    @SerialName("keyboard")
    data object Keyboard : LocalToolOption()

    @Serializable
    @SerialName("agent_state")
    data object AgentState : LocalToolOption()

    @Serializable
    @SerialName("foreground_app")
    data object ForegroundApp : LocalToolOption()

    companion object {
        /** 全部内置工具（Agent 模式下全部启用，不提供关闭选项） */
        val all: List<LocalToolOption> = listOf(
            JavascriptEngine,
            TimeInfo,
            Clipboard,
            Tts,
            AskUser,
            ScreenTime,
            DeviceInfo,
            ScreenText,
            SendNotification,
            Calendar,
            Automation,
            LocalSpeechRecognition,
            AgentState,
            ForegroundApp,
        )
    }
}
