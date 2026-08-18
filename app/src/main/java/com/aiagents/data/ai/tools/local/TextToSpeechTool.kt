package com.aiagents.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.datastore.getSelectedTTSProvider
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.tts.model.AudioFormat
import com.aiagents.tts.model.TTSRequest
import com.aiagents.tts.provider.TTSManager
import com.aiagents.tts.provider.TTSProviderSetting
import com.aiagents.utils.cleanForSpeech
import java.io.ByteArrayOutputStream
import java.io.File

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "text_to_speech",
    description = """
        Speak text aloud to the user using the device's text-to-speech engine.
        Use this when the user asks you to read something aloud, or when audio output is appropriate.
        The tool returns immediately; audio plays in the background on the device.
        Provide natural, readable text without markdown formatting.
        Optionally pass saveTo (a workspace or device path) to save the synthesized audio to a file
        instead of playing it aloud; the file path is returned in the result.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        buildString {
            // 朗读规范: 引导 AI 提供干净、自然、适合听觉的文本
            appendLine("使用 text_to_speech (TTS) 工具朗读文本时：")
            appendLine("1. 不要在文本中写反斜杠（\\），因为反斜杠会被语音引擎逐字读出来（比如\"反斜杠n\"），毫无用处且影响听感。")
            appendLine("2. 朗读时不必注重排版格式（如Markdown、缩进、特殊符号等），因为是读给用户听的，格式标记只会干扰朗读效果。")
            appendLine("3. 提供给 TTS 的文本应该是干净、自然的口语化文本，适合听觉理解。")
            // 当前选中的 TTS provider 若硬编码了语气标记引导，则追加注入（否则为空）
            settingsStore.settingsFlow.value.getSelectedTTSProvider()
                ?.let { ttsManager.getPromptGuidance(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { appendLine(it) }
        }.trim()
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to speak aloud")
                })
                put("saveTo", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional. A workspace path (e.g. /workspace/media/tts.mp3) or device path to save the synthesized audio file. When set, audio is written to the file instead of played aloud.")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val args = it.jsonObject
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")
        val saveTo = args["saveTo"]?.jsonPrimitive?.contentOrNull

        // 防串扰: 清洗 AI 生成的文本, 移除反斜杠并把格式修饰替换为句号
        val cleanText = text.cleanForSpeech()

        if (saveTo.isNullOrBlank()) {
            eventBus.emit(AppEvent.Speak(cleanText))
            val payload = buildJsonObject {
                put("success", true)
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        // 保存到文件: 合成完整音频并写入目标路径
        val provider = settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?: error("No TTS provider selected")
        val (bytes, format) = synthesizeToBytes(ttsManager, provider, cleanText)
        val target = resolveTargetFile(workspaceRepository, saveTo, format)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target.setReadable(true, false)
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "664", target.absolutePath)).waitFor()
        }

        val payload = buildJsonObject {
            put("success", true)
            put("path", saveTo)
            put("devicePath", target.absolutePath)
            put("bytes", bytes.size)
            put("format", format.name.lowercase())
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 合成整段文本为音频字节, 返回 (字节, 音频格式) */
private suspend fun synthesizeToBytes(
    ttsManager: TTSManager,
    provider: TTSProviderSetting,
    text: String,
): Pair<ByteArray, AudioFormat> {
    val output = ByteArrayOutputStream()
    var format = AudioFormat.MP3
    ttsManager.generateSpeech(provider, TTSRequest(text = text)).collect { chunk ->
        format = chunk.format
        output.write(chunk.data)
    }
    return output.toByteArray() to format
}

/** 解析保存目标: 设备路径直接使用, 否则按工作区 Rootfs 路径解析; 目录则自动生成文件名 */
private suspend fun resolveTargetFile(
    workspaceRepository: WorkspaceRepository,
    saveTo: String,
    format: AudioFormat,
): File {
    val raw = saveTo.trim().replace('\\', '/').trimEnd('/')
    if (raw.isBlank()) error("saveTo is required")

    val isDevice = raw.startsWith("/storage/") || raw.startsWith("/storage/emulated") ||
        raw.startsWith("/sdcard") || raw.startsWith("/data/") || raw.startsWith("/system/") ||
        raw.startsWith("/mnt/") || raw.startsWith("/cache/")

    val base = if (isDevice) {
        File(raw)
    } else {
        val normalized = if (raw.startsWith("/")) raw else "/workspace/$raw"
        val workspace = workspaceRepository.getDefaultWorkspace() ?: error("No workspace available")
        workspaceRepository.resolveRootfsHostFile(workspace.id, normalized)
    }

    // 目录(以 / 结尾或已存在且为目录) → 追加自动生成的文件名
    val isDir = saveTo.endsWith("/") || (base.exists() && base.isDirectory)
    return if (isDir) {
        File(base, "tts_${System.currentTimeMillis()}.${format.extension()}")
    } else {
        base
    }
}

private fun AudioFormat.extension(): String = when (this) {
    AudioFormat.MP3 -> "mp3"
    AudioFormat.WAV -> "wav"
    AudioFormat.OGG -> "ogg"
    AudioFormat.AAC -> "aac"
    AudioFormat.OPUS -> "opus"
    AudioFormat.PCM -> "pcm"
}
