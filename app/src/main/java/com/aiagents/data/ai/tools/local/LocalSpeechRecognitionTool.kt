package com.aiagents.data.ai.tools.local

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.WorkerThread
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.repository.WorkspaceRepository
import java.io.File
import kotlinx.serialization.json.boolean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 进程级共享的录音工具状态: 供工具卡片实时显示正在识别的文本。
 * `partialText` 在录音循环里随每次识别更新, 渲染层 collectAsState 即可实时刷新;
 * `recording` 表示录音工具是否正在录音, 用于展示状态。
 */
object LocalSpeechRecognitionUiState {
    private val _recording = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _partialText = kotlinx.coroutines.flow.MutableStateFlow("")
    private val _saveAudioMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _stopRequested = kotlinx.coroutines.flow.MutableStateFlow(false)
    val recording: kotlinx.coroutines.flow.StateFlow<Boolean> = _recording
    val partialText: kotlinx.coroutines.flow.StateFlow<String> = _partialText
    /** save_audio 模式(长录音): 卡片需要显示"终止"按钮, 录音不会因静音自动停止 */
    val saveAudioMode: kotlinx.coroutines.flow.StateFlow<Boolean> = _saveAudioMode
    /** 卡片"终止"按钮请求结束长录音 */
    val stopRequested: kotlinx.coroutines.flow.StateFlow<Boolean> = _stopRequested

    fun onRecordingStart(saveAudio: Boolean = false) {
        _recording.value = true
        _saveAudioMode.value = saveAudio
        _partialText.value = ""
        _stopRequested.value = false
    }

    fun onPartial(text: String) {
        _partialText.value = text
    }

    fun requestStop() {
        _stopRequested.value = true
    }

    fun onRecordingEnd() {
        _recording.value = false
        _saveAudioMode.value = false
        _partialText.value = ""
        _stopRequested.value = false
    }
}

private const val SAMPLE_RATE = 16000
private const val CHUNK_SIZE = 1600
private const val MAX_RECORD_MS = 120_000L
/** save_audio 长录音模式的兜底上限: 即便没点终止也不至于无限录制(30 分钟) */
private const val MAX_SAVE_RECORD_MS = 30 * 60 * 1000L
private val RECORD_CONFIG = RecognizerConfig(
    featConfig = FeatureExtractorConfig(sampleRate = SAMPLE_RATE.toFloat(), featureDim = 80),
    modelConfig = ModelConfig(
        encoderParam = "sherpa/64/encoder_jit_trace-pnnx.ncnn.param",
        encoderBin = "sherpa/64/encoder_jit_trace-pnnx.ncnn.bin",
        decoderParam = "sherpa/64/decoder_jit_trace-pnnx.ncnn.param",
        decoderBin = "sherpa/64/decoder_jit_trace-pnnx.ncnn.bin",
        joinerParam = "sherpa/64/joiner_jit_trace-pnnx.ncnn.param",
        joinerBin = "sherpa/64/joiner_jit_trace-pnnx.ncnn.bin",
        tokens = "sherpa/64/tokens.txt",
        numThreads = 2,
        useGPU = false,
    ),
    decoderConfig = DecoderConfig(method = "modified_beam_search", numActivePaths = 4),
    enableEndpoint = true,
    rule1MinTrailingSilence = 2.4f,
    rule2MinTrailingSilence = 1.0f,
    rule3MinUtteranceLength = 30.0f,
)

internal fun buildLocalSpeechRecognitionTool(
    context: Context,
    pathResolver: WorkspacePathResolver,
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "local_speech_recognition",
    description = """
        Recognize speech using the on-device speech-to-text engine (no network required).
        Two modes:
        1) Microphone mode (default): listen in real time. Stops automatically after silence.
           silence_timeout: seconds of silence before auto-stop (default 5, min 2, max 30).
        2) File mode: transcribe a 16kHz mono WAV file.
           file_path: workspace path (/workspace/..., /screenshots/..., /sd/...) or absolute Android path.
        save_audio (microphone mode only): if true, the recording does NOT auto-stop on silence —
           it keeps recording as a long recording until the user taps the stop button on the result card —
           and the recorded audio is saved as a 16kHz mono WAV file into the workspace
           (/workspace/media/...) plus its device path. Returns both the transcript and the file paths.
        Returns the recognized text (and saved audio paths when save_audio is enabled).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("silence_timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Seconds of silence before auto-stop (default 5, min 2, max 30)")
                })
                put("file_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to a 16kHz mono WAV file. When provided, transcribes the file instead of recording")
                })
                put("save_audio", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Long-recording mode (microphone only, default false). Disables auto-stop on silence; recording continues until the user stops it on the card, and the audio is saved to /workspace/media/")
                })
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val json = args.jsonObject
        val filePath = json["file_path"]?.jsonPrimitive?.contentOrNull
        val saveAudio = json["save_audio"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val result = if (filePath != null) {
            transcribeFile(context, pathResolver, filePath)
        } else {
            val silenceSec = json["silence_timeout"]?.jsonPrimitive?.int ?: 5
            if (saveAudio) {
                recordAndSaveAudio(context, workspaceRepository).also {
                    if (it.transcript.isNotBlank()) {
                        LocalSpeechRecognitionUiState.onPartial(it.transcript)
                    }
                }.toText()
            } else {
                recordAndTranscribe(context, silenceSec.coerceIn(2, 30))
            }
        }

        listOf(UIMessagePart.Text(result))
    }
)

private suspend fun transcribeFile(
    context: Context,
    pathResolver: WorkspacePathResolver,
    path: String,
): String {
    return try {
        // 工作区路径(/workspace/... /screenshots/... /sd/...)需先解析为设备真实路径,
        // 否则 File(path) 在 Android 上找不到容器内的文件
        val devicePath = pathResolver.toDevicePath(path) ?: return "File not found: $path"
        val file = File(devicePath)
        if (!file.exists() || !file.isFile) return "File not found: $path"
        val samples = readWav16kMono(file)
        if (samples.isEmpty()) return "Unable to decode audio file: $path"

        val rec = com.aiagents.asr.providers.SherpaNcnnInstance.acquire(RECORD_CONFIG, context.assets)
            ?: return "Native library not available"
        if (!com.aiagents.asr.providers.SherpaNcnnInstance.tryBeginSession()) return "Speech engine busy"
        try {
            // 长文件分段喂入; endpoint 触发时确认当前段, 避免大段内容被 reset() 丢弃
            val committed = StringBuilder()
            var lastSegment = ""
            var offset = 0
            while (offset < samples.size) {
                val end = (offset + CHUNK_SIZE).coerceAtMost(samples.size)
                rec.acceptSamples(samples.copyOfRange(offset, end))
                while (rec.isReady()) rec.decode()
                offset = end

                val text = rec.text.trim()
                if (rec.isEndpoint()) {
                    if (text.isNotEmpty()) appendSegment(committed, text)
                    rec.reset()
                    lastSegment = ""
                } else if (text.isNotEmpty() && text != lastSegment) {
                    lastSegment = text
                }
            }
            rec.inputFinished()
            rec.decode()
            val tail = rec.text.trim()
            if (tail.isNotEmpty()) appendSegment(committed, tail)
            committed.toString().trim().ifEmpty { "No speech detected" }
        } finally {
            com.aiagents.asr.providers.SherpaNcnnInstance.endSession()
        }
    } catch (e: Exception) {
        "Transcription failed: ${e.message}"
    }
}

private fun readWav16kMono(file: File): FloatArray {
    return try {
        val data = file.readBytes()
        val sampleRate = (data[24].toInt() and 0xff) or
            ((data[25].toInt() and 0xff) shl 8) or
            ((data[26].toInt() and 0xff) shl 16) or
            ((data[27].toInt() and 0xff) shl 24)
        val channels = (data[22].toInt() and 0xff) or ((data[23].toInt() and 0xff) shl 8)
        val bitsPerSample = (data[34].toInt() and 0xff) or ((data[35].toInt() and 0xff) shl 8)

        var dataOffset = 12
        while (dataOffset < data.size) {
            val chunkId = String(data, dataOffset, 4)
            val chunkSize = (data[dataOffset + 4].toInt() and 0xff) or
                ((data[dataOffset + 5].toInt() and 0xff) shl 8) or
                ((data[dataOffset + 6].toInt() and 0xff) shl 16) or
                ((data[dataOffset + 7].toInt() and 0xff) shl 24)
            if (chunkId == "data") {
                dataOffset += 8
                val raw = data.copyOfRange(dataOffset, dataOffset + chunkSize)
                val step = channels.coerceAtLeast(1)
                val result = if (bitsPerSample >= 16) {
                    FloatArray(chunkSize / (step * 2)) { i ->
                        val b = raw[i * step * 2]
                        val a = raw[i * step * 2 + 1]
                        ((a.toInt() and 0xff) shl 8 or (b.toInt() and 0xff)).toShort().toFloat() / 32768f
                    }
                } else {
                    FloatArray(chunkSize / step) { i ->
                        ((raw[i * step].toInt() and 0xff) - 128) / 128f
                    }
                }
                return if (sampleRate == SAMPLE_RATE) result else resample(result, sampleRate, SAMPLE_RATE)
            }
            dataOffset += 8 + chunkSize + (chunkSize % 2)
        }
        FloatArray(0)
    } catch (e: Exception) {
        FloatArray(0)
    }
}

private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return input
    val ratio = toRate.toDouble() / fromRate.toDouble()
    val outSize = (input.size * ratio).toInt()
    val out = FloatArray(outSize)
    for (i in out.indices) {
        val srcPos = i / ratio
        val idx = srcPos.toInt().coerceIn(0, input.lastIndex)
        out[i] = input[idx]
    }
    return out
}

private fun recordAndTranscribe(context: Context, silenceTimeoutSec: Int): String {
    val rec = com.aiagents.asr.providers.SherpaNcnnInstance.acquire(RECORD_CONFIG, context.assets)
        ?: return "Native library not available"
    if (!com.aiagents.asr.providers.SherpaNcnnInstance.tryBeginSession()) return "Speech engine busy"

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        com.aiagents.asr.providers.SherpaNcnnInstance.endSession()
        return "Microphone initialization failed"
    }

    LocalSpeechRecognitionUiState.onRecordingStart()
    try {
        record.startRecording()
        val buf = ShortArray(CHUNK_SIZE)
        val silenceLimit = silenceTimeoutSec * 1000 / (CHUNK_SIZE * 1000 / SAMPLE_RATE)
        // 累积所有已确认分段, 防止 endpoint 触发 reset() 时丢前文
        val committed = StringBuilder()
        var lastSegment = ""
        var silenceCounter = 0
        val startedAt = System.currentTimeMillis()

        while (true) {
            val read = record.read(buf, 0, CHUNK_SIZE)
            if (read <= 0) continue

            val samples = FloatArray(read) { buf[it] / 32768f }
            rec.acceptSamples(samples)
            while (rec.isReady()) rec.decode()

            val text = rec.text.trim()
            if (rec.isEndpoint()) {
                // 分句结束: 先确认当前段再 reset() 开新段, 不丢前文
                if (text.isNotEmpty()) appendSegment(committed, text)
                rec.reset()
                lastSegment = ""
                silenceCounter = 0
                LocalSpeechRecognitionUiState.onPartial(committed.toString())
            } else if (text.isNotEmpty() && text != lastSegment) {
                lastSegment = text
                silenceCounter = 0
                LocalSpeechRecognitionUiState.onPartial(accumulatedText(committed, text))
            } else {
                silenceCounter++
                if (silenceCounter >= silenceLimit) break
            }

            // 兜底: 单次录音最长不超过 2 分钟, 避免一直空转
            if (System.currentTimeMillis() - startedAt > MAX_RECORD_MS) break
        }

        // 结束前把仍在进行的未确认段也并入, 保留整段内容
        if (lastSegment.isNotEmpty()) appendSegment(committed, lastSegment)
        rec.inputFinished()
        rec.decode()
        val tail = rec.text.trim()
        if (tail.isNotEmpty()) appendSegment(committed, tail)
        return committed.toString().trim().ifEmpty { "No speech detected" }
    } finally {
        runCatching { record.stop() }
        runCatching { record.release() }
        com.aiagents.asr.providers.SherpaNcnnInstance.endSession()
        LocalSpeechRecognitionUiState.onRecordingEnd()
    }
}

private fun appendSegment(sb: StringBuilder, segment: String) {
    if (segment.isBlank()) return
    if (sb.isNotEmpty()) sb.append(' ')
    sb.append(segment)
}

private fun accumulatedText(sb: StringBuilder, current: String): String =
    if (sb.isEmpty()) current else "${sb.toString()} $current"

/** save_audio 长录音的结果: 转写文本 + 保存的音频文件信息 */
private data class SaveAudioResult(
    val transcript: String,
    val workspacePath: String?,
    val devicePath: String?,
    val sizeBytes: Long,
    val durationMs: Long,
)

private fun SaveAudioResult.toText(): String {
    val fileInfo = if (workspacePath != null) {
        buildString {
            append("\n\nsaved audio:")
            append("\nworkspace_path: $workspacePath")
            if (devicePath != null) append("\ndevice_path: $devicePath")
            append("\nsize_bytes: $sizeBytes")
            append("\nduration_ms: $durationMs")
        }
    } else {
        "\n\n(audio could not be saved: no ready workspace)"
    }
    return (transcript.ifEmpty { "No speech detected" }) + fileInfo
}

/**
 * 长录音模式(save_audio=true): 打开麦克风持续录音, 遇到静音/端点都不自动停止,
 * 直到用户点击卡片上的"终止"按钮(经 [LocalSpeechRecognitionUiState.requestStop])或到达
 * 30 分钟兜底上限。录制的 PCM 音频(16kHz 单声道 16bit)最终保存为 WAV 文件到
 * 工作区 /workspace/media/, 并返回工作区路径 + 设备路径, 供 AI 继续使用。
 */
private suspend fun recordAndSaveAudio(
    context: Context,
    repository: WorkspaceRepository,
): SaveAudioResult {
    val rec = com.aiagents.asr.providers.SherpaNcnnInstance.acquire(RECORD_CONFIG, context.assets)
        ?: return SaveAudioResult("Native library not available", null, null, 0, 0)
    if (!com.aiagents.asr.providers.SherpaNcnnInstance.tryBeginSession()) {
        return SaveAudioResult("Speech engine busy", null, null, 0, 0)
    }

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        com.aiagents.asr.providers.SherpaNcnnInstance.endSession()
        return SaveAudioResult("Microphone initialization failed", null, null, 0, 0)
    }

    LocalSpeechRecognitionUiState.onRecordingStart(saveAudio = true)
    val pcm = ByteArrayOutputStream()
    val committed = StringBuilder()
    try {
        record.startRecording()
        val buf = ShortArray(CHUNK_SIZE)
        var lastSegment = ""
        val startedAt = System.currentTimeMillis()

        while (true) {
            if (LocalSpeechRecognitionUiState.stopRequested.value) break
            // 兜底: 最长 30 分钟, 防止误点 save_audio 后无限空转
            if (System.currentTimeMillis() - startedAt > MAX_SAVE_RECORD_MS) break

            val read = record.read(buf, 0, CHUNK_SIZE)
            if (read <= 0) continue

            // 累积 PCM(小端 16bit)
            for (i in 0 until read) {
                val s = buf[i].toInt() and 0xFFFF
                pcm.write(s and 0xFF)
                pcm.write((s shr 8) and 0xFF)
            }

            val samples = FloatArray(read) { buf[it] / 32768f }
            rec.acceptSamples(samples)
            while (rec.isReady()) rec.decode()

            val text = rec.text.trim()
            if (rec.isEndpoint()) {
                if (text.isNotEmpty()) appendSegment(committed, text)
                rec.reset()
                lastSegment = ""
            } else if (text.isNotEmpty() && text != lastSegment) {
                lastSegment = text
            }

            // save_audio 长录音不会在录音期间推流式文本: 卡片内容保持完全静态,
            // 避免高频 partial 文本触发 Compose 持续重组/尺寸动画, 进而刺激 HWUI
            // 渲染线程并行操作正在被回收的文本节点 (destroyed-mutex / regex 的确定性 UAF)。
            // 转写仍累积到 committed/lastSegment, 录音结束后一次性作为结果文本落到卡片。
        }

        if (lastSegment.isNotEmpty()) appendSegment(committed, lastSegment)
        rec.inputFinished()
        rec.decode()
        val tail = rec.text.trim()
        if (tail.isNotEmpty()) appendSegment(committed, tail)

        // 面包屑: 便于在 crash 日志里定位执行到哪一步被杀(native 栈在本机不可用)
        android.util.Log.i("SaveAudio", "crumb: after decode tail=$tail")

        val transcript = committed.toString().trim()
        val durationMs = System.currentTimeMillis() - startedAt

        val pcmBytes = pcm.toByteArray()
        val wavBytes = if (pcmBytes.isNotEmpty()) buildWav(pcmBytes) else null
        android.util.Log.i("SaveAudio", "crumb: wav built size=${wavBytes?.size}")
        val saved = if (wavBytes != null) saveWavToWorkspace(repository, wavBytes) else null
        android.util.Log.i("SaveAudio", "crumb: saved=$saved")

        return SaveAudioResult(
            transcript = transcript,
            workspacePath = saved?.first,
            devicePath = saved?.second,
            sizeBytes = wavBytes?.size?.toLong() ?: 0,
            durationMs = durationMs,
        )
    } finally {
        runCatching { record.stop() }
        runCatching { record.release() }
        com.aiagents.asr.providers.SherpaNcnnInstance.endSession()
        LocalSpeechRecognitionUiState.onRecordingEnd()
    }
}

/** 把 PCM 16bit 数据封装成标准 WAV(16kHz 单声道 16bit) */
private fun buildWav(pcm16BitMono: ByteArray): ByteArray {
    val dataSize = pcm16BitMono.size
    val wav = ByteArray(44 + dataSize)
    val putAscii = { s: String, at: Int ->
        s.toByteArray().forEachIndexed { i, b -> wav[at + i] = b }
    }
    putAscii("RIFF", 0)
    writeLeInt(wav, 4, 36 + dataSize)
    putAscii("WAVE", 8)
    putAscii("fmt ", 12)
    writeLeInt(wav, 16, 16)                    // fmt chunk size
    writeLeShort(wav, 20, 1)                   // PCM
    writeLeShort(wav, 22, 1)                   // mono
    writeLeInt(wav, 24, SAMPLE_RATE)
    writeLeInt(wav, 28, SAMPLE_RATE * 2)       // byte rate
    writeLeShort(wav, 32, 2)                   // block align
    writeLeShort(wav, 34, 16)                  // bits per sample
    putAscii("data", 36)
    writeLeInt(wav, 40, dataSize)
    pcm16BitMono.copyInto(wav, 44)
    return wav
}

private fun writeLeInt(wav: ByteArray, at: Int, value: Int) {
    wav[at] = (value and 0xFF).toByte()
    wav[at + 1] = ((value shr 8) and 0xFF).toByte()
    wav[at + 2] = ((value shr 16) and 0xFF).toByte()
    wav[at + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeLeShort(wav: ByteArray, at: Int, value: Int) {
    wav[at] = (value and 0xFF).toByte()
    wav[at + 1] = ((value shr 8) and 0xFF).toByte()
}

/** 保存 WAV 到默认工作区 /workspace/media/, 返回 (工作区路径, 设备路径) */
private suspend fun saveWavToWorkspace(
    repository: WorkspaceRepository,
    wavBytes: ByteArray,
): Pair<String, String>? {
    val workspace = repository.getDefaultWorkspace() ?: return null
    val fileName = "recording_${System.currentTimeMillis()}.wav"
    val workspacePath = "/workspace/media/$fileName"
    return runCatching {
        val host = repository.resolveRootfsHostFile(workspace.id, workspacePath)
        host.parentFile?.mkdirs()
        host.writeBytes(wavBytes)
        host.setReadable(true, false)
        Runtime.getRuntime().exec(arrayOf("chmod", "664", host.absolutePath)).waitFor()
        workspacePath to host.absolutePath
    }.getOrNull()
}
