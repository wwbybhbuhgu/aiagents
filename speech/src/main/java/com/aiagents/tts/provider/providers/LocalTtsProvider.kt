package com.aiagents.tts.provider.providers

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import com.aiagents.tts.model.AudioChunk
import com.aiagents.tts.model.AudioFormat
import com.aiagents.tts.model.TTSRequest
import com.aiagents.tts.provider.TTSProvider
import com.aiagents.tts.provider.TTSProviderSetting
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "LocalTtsProvider"

/**
 * 本地智能 TTS Provider。
 *
 * 使用 sherpa-onnx 运行库加载 VITS 模型进行本地语音合成。
 * 模型文件内置在 assets/tts_models 目录。
 *
 * 模型文件:
 * - model.onnx   (VITS 模型)
 * - tokens.txt   (词表)
 * - lexicon.txt  (词典)
 * - dict/        (jieba 分词词典)
 * - *.fst        (数字/日期/电话等规则)
 */
class LocalTtsProvider : TTSProvider<TTSProviderSetting.LocalTTS> {

    override val promptGuidance: String = """
        本地 TTS 支持多音色和多语言:
        - 通过 voice 参数选择音色 (sid)
        - 支持中/英/日/韩等多语言
        - 完全离线运行
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.LocalTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        Log.i(TAG, "generateSpeech: synthesizing with sherpa-onnx")
        val wavData = synthesizeWithSherpaOnnx(context, providerSetting, request.text)
        if (wavData == null) {
            Log.w(TAG, "generateSpeech: synthesis failed, using placeholder")
            emit(createSilenceChunk())
            return@flow
        }

        emit(
            AudioChunk(
                data = wavData,
                format = AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "local",
                    "engine" to "sherpa-onnx",
                )
            )
        )
    }

    /**
     * 使用 sherpa-onnx 合成语音。
     * 模型从 assets 加载, 返回 WAV 格式音频。
     */
    private suspend fun synthesizeWithSherpaOnnx(
        context: Context,
        providerSetting: TTSProviderSetting.LocalTTS,
        text: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val modelDir = "tts_models"

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "$modelDir/model.onnx",
                lexicon = "$modelDir/lexicon.txt",
                tokens = "$modelDir/tokens.txt",
                dataDir = modelDir,
                dictDir = "$modelDir/dict",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = providerSetting.numThreads,
                debug = false,
                provider = "cpu",
            )
            val ruleFsts = listOf("date", "number", "phone", "new_heteronym")
                .joinToString(",") { "$modelDir/$it.fst" }
            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = ruleFsts,
                ruleFars = "",
                maxNumSentences = 2,
                silenceScale = 0.8f,
            )

            val tts = OfflineTts(context.assets, ttsConfig)
            try {
                val sid = 0 // 默认音色
                val speed = 1.0f
                val audio = tts.generate(text, sid, speed)
                val sampleRate = audio.sampleRate
                val samples = audio.samples

                Log.i(TAG, "DEBUG text='$text' sampleRate=$sampleRate samples=${samples.size}")

            // 手动转换 FloatArray → 16-bit PCM → WAV
            if (samples.isEmpty()) {
                Log.e(TAG, "generateSpeech: empty samples")
                return@runCatching null
            }
            Log.i(TAG, "generateSpeech: sampleRate=$sampleRate, samples=${samples.size}")

            // FloatArray → 16-bit PCM (小端序, 有符号)
            // 先按峰值归一化, 避免模型输出过轻(峰值通常只有 ~0.2)导致听感发闷发糊
            var peak = 0f
            for (s in samples) {
                val a = kotlin.math.abs(s)
                if (a > peak) peak = a
            }
            val scale = if (peak > 1e-6f && peak < 0.9f) 0.9f / peak else 1.0f

            val pcmBytes = ByteArray(samples.size * 2)
            val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                // 钳位到 [-1.0, 1.0] 并转为 16-bit
                val clamped = (s * scale).coerceIn(-1.0f, 1.0f)
                buffer.putShort((clamped * 32767f).toInt().toShort())
            }

            // 封装 WAV 头 (16-bit, 单声道)
            val dataSize = pcmBytes.size
            val wavBytes = ByteArray(44 + dataSize)
            val wavBuffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

            wavBuffer.put("RIFF".toByteArray())       // ChunkID
            wavBuffer.putInt(36 + dataSize)            // ChunkSize
            wavBuffer.put("WAVE".toByteArray())        // Format
            wavBuffer.put("fmt ".toByteArray())        // Subchunk1ID
            wavBuffer.putInt(16)                       // Subchunk1Size (PCM)
            wavBuffer.putShort(1)                      // AudioFormat (PCM)
            wavBuffer.putShort(1)                      // NumChannels (mono)
            wavBuffer.putInt(sampleRate)               // SampleRate
            wavBuffer.putInt(sampleRate * 2)           // ByteRate
            wavBuffer.putShort(2)                      // BlockAlign
            wavBuffer.putShort(16)                     // BitsPerSample
            wavBuffer.put("data".toByteArray())        // Subchunk2ID
            wavBuffer.putInt(dataSize)                 // Subchunk2Size
            wavBuffer.put(pcmBytes)                    // Data

            wavBytes
            } finally {
                tts.release()
            }
        }.getOrElse { e ->
            Log.e(TAG, "synthesizeWithSherpaOnnx failed", e)
            null
        }
    }

    private fun createSilenceChunk(): AudioChunk {
        val sampleRate = 24000
        val durationMs = 500
        val numSamples = sampleRate * durationMs / 1000
        val pcmData = ByteArray(numSamples * 2)

        return AudioChunk(
            data = pcmData,
            format = AudioFormat.WAV,
            isLast = true,
            metadata = mapOf(
                "provider" to "local",
                "sampleRate" to sampleRate.toString(),
            )
        )
    }

    companion object {
        /** FloatArray (-1.0 ~ 1.0) → 16-bit PCM 字节 */
        fun floatArrayToPcm16(samples: FloatArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                buffer.putShort((clamped * 32767).toInt().toShort())
            }
            return bytes
        }

        /** 创建 WAV 文件头 + PCM 数据 (16-bit, 单声道) */
        fun createWavFile(pcmData: ByteArray, sampleRate: Int): ByteArray {
            val dataSize = pcmData.size
            val fileSize = 44 + dataSize

            val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buffer.put("RIFF".toByteArray())
            buffer.putInt(fileSize - 8)
            buffer.put("WAVE".toByteArray())

            // fmt chunk
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16)
            buffer.putShort(1) // PCM
            buffer.putShort(1) // mono
            buffer.putInt(sampleRate)
            buffer.putInt(sampleRate * 2) // byte rate
            buffer.putShort(2) // block align
            buffer.putShort(16) // bits per sample

            // data chunk
            buffer.put("data".toByteArray())
            buffer.putInt(dataSize)
            buffer.put(pcmData)

            return buffer.array()
        }
    }
}