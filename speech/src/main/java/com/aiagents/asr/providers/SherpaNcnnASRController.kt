package com.aiagents.asr.providers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.aiagents.asr.ASRController
import com.aiagents.asr.ASRState
import com.aiagents.asr.ASRStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SAMPLE_RATE = 16000
private const val CHUNK_SIZE = 1600
private const val TAG = "SherpaNcnnASR"

/**
 * 进程级共享的 sherpa-ncnn recognizer。
 *
 * ncnn 会启动 OpenMP 工作线程并在加载模型时分配大量内存; 若在同一进程里反复创建/销毁
 * 多个 recognizer (浮窗、主页面语音等入口并发), 会在非持有线程关闭 native 实例, 导致
 * 堆损坏 (SIGSEGV in je_malloc / arena_run_dalloc)。因此全局只保留一个实例, 会话之间复用,
 * 退出会话时只 reset() 不 delete()。
 */
object SherpaNcnnInstance {
    private val lock = Any()
    private var instance: SherpaNcnn? = null
    private val inUse = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 返回共享 recognizer; 首次调用时创建。失败返回 null。 */
    fun acquire(config: RecognizerConfig, assets: android.content.res.AssetManager): SherpaNcnn? {
        synchronized(lock) {
            if (instance != null) return instance
            if (!SherpaNcnn.ensureLoaded()) return null
            return try {
                instance = SherpaNcnn(config, assets)
                instance
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** 尝试占用会话; 已有人在使用时返回 false (避免并发解码破坏堆)。 */
    fun tryBeginSession(): Boolean {
        return inUse.compareAndSet(false, true)
    }

    /** 结束会话占用。 */
    fun endSession() {
        inUse.set(false)
        reset()
    }

    /** 会话结束后调用, 清空识别状态但保留 native 实例, 避免 delete/close 竞态。 */
    fun reset() {
        synchronized(lock) {
            instance?.let { runCatching { it.reset(recreate = false) } }
        }
    }

    /** 应用退出时彻底释放 (正常情况下不会被调用)。 */
    fun shutdown() {
        synchronized(lock) {
            instance?.let { runCatching { it.close() } }
            instance = null
        }
    }
}

class SherpaNcnnASRController(
    private val context: Context,
) : ASRController {
    private val _state = MutableStateFlow(ASRState(status = ASRStatus.Idle, isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null

    /** 当前会话持有的 AudioRecord; stop() 时需要同步 stop/release 以释放麦克风。 */
    @Volatile
    private var activeRecord: AudioRecord? = null

    /** 静音超过该秒数后自动结束会话 (默认 3 秒)。 */
    var silenceSeconds: Int = 3

    private fun acquireAudioFocus() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .build()
            am.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
            )
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (_state.value.status == ASRStatus.Listening) return
        this.onTranscriptChange = onTranscriptChange

        val modelDir = "sherpa"
        val modelConfig = ModelConfig(
            encoderParam = "$modelDir/64/encoder_jit_trace-pnnx.ncnn.param",
            encoderBin = "$modelDir/64/encoder_jit_trace-pnnx.ncnn.bin",
            decoderParam = "$modelDir/64/decoder_jit_trace-pnnx.ncnn.param",
            decoderBin = "$modelDir/64/decoder_jit_trace-pnnx.ncnn.bin",
            joinerParam = "$modelDir/64/joiner_jit_trace-pnnx.ncnn.param",
            joinerBin = "$modelDir/64/joiner_jit_trace-pnnx.ncnn.bin",
            tokens = "$modelDir/64/tokens.txt",
            numThreads = 2,
            useGPU = false,
        )
        val config = RecognizerConfig(
            featConfig = FeatureExtractorConfig(sampleRate = SAMPLE_RATE.toFloat(), featureDim = 80),
            modelConfig = modelConfig,
            decoderConfig = DecoderConfig(method = "modified_beam_search", numActivePaths = 4),
            enableEndpoint = true,
            rule1MinTrailingSilence = 2.4f,
            rule2MinTrailingSilence = 1.0f,
            rule3MinUtteranceLength = 30.0f,
        )
        val recognizer = SherpaNcnnInstance.acquire(config, context.assets)
        if (recognizer == null) {
            Log.w(TAG, "start: native load failed")
            _state.value = ASRState(status = ASRStatus.Error, isAvailable = false, errorMessage = "Native library not found")
            return
        }
        if (!SherpaNcnnInstance.tryBeginSession()) {
            // 会话仍被占用 (例如 save_audio 长录音还在进行, 或上一次会话因异常残留)。
            // 绝不能在这里 endSession()/reset() 共享 native 实例并发接管: 那会与仍在
            // 解码的另一份 native 工作线程并发操作堆, 产生确定性 UAF (destroyed mutex /
            // regex_error, hwuiTask 崩溃)。宁可本次启动失败, 也不破坏共享实例。
            Log.w(TAG, "start: session busy with active recorder, skip to avoid native race")
            _state.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
            return
        }
        Log.d(TAG, "start: session acquired, silenceSeconds=$silenceSeconds")
        _state.value = ASRState(status = ASRStatus.Listening, isAvailable = true)
        acquireAudioFocus()

        val job = CoroutineScope(Dispatchers.IO + Job()).launch {
            try {
                // 会话开始前重置识别状态
                runCatching { recognizer.reset(recreate = false) }

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
                activeRecord = record
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord init failed, state=${record.state}")
                    releaseRecord()
                    SherpaNcnnInstance.endSession()
                    abandonFocus()
                    finishWithResult("")
                    return@launch
                }
                try {
                    record.startRecording()
                } catch (e: Throwable) {
                    Log.w(TAG, "startRecording failed: ${e.message}")
                    releaseRecord()
                    SherpaNcnnInstance.endSession()
                    abandonFocus()
                    finishWithResult("")
                    return@launch
                }

                val buf = ShortArray(CHUNK_SIZE)
                // 累积所有已确认分段, 防止 endpoint 触发 reset() 时丢前文
                val committed = StringBuilder()
                var lastSegment = ""
                var silenceFrames = 0
                val silenceLimit = silenceSeconds.coerceAtLeast(1) * 10

                while (isActive && _state.value.status == ASRStatus.Listening) {
                    val read = record.read(buf, 0, CHUNK_SIZE)
                    if (read <= 0) {
                        delay(5)
                        continue
                    }
                    val samples = FloatArray(read) { buf[it] / 32768f }
                    recognizer.acceptSamples(samples)
                    while (recognizer.isReady()) {
                        recognizer.decode()
                    }

                    val text = recognizer.text.trim()

                    if (recognizer.isEndpoint()) {
                        // 分句结束: 先确认当前段再 reset() 开新段, 不丢前文
                        if (text.isNotEmpty()) {
                            appendSegment(committed, text)
                        }
                        recognizer.reset()
                        lastSegment = ""
                        silenceFrames = 0
                        onTranscriptChange(committed.toString())
                    } else if (text.isNotEmpty() && text != lastSegment) {
                        lastSegment = text
                        silenceFrames = 0
                        onTranscriptChange(accumulatedText(committed, text))
                    } else {
                        silenceFrames++
                    }

                    if (silenceFrames >= silenceLimit) {
                        Log.d(TAG, "silence timeout, frames=$silenceFrames limit=$silenceLimit text='$lastSegment'")
                        // 结束前把仍在进行的未确认段也并入, 保留整段内容
                        if (text.isNotEmpty()) appendSegment(committed, text)
                        break
                    }
                }

                // 停止录音以解除阻塞, 随后清理会话状态 (不 close native)
                releaseRecord()
                SherpaNcnnInstance.endSession()
                abandonFocus()

                finishWithResult(committed.toString().trim())
            } catch (t: Throwable) {
                Log.w(TAG, "record loop throwable: ${t.message} (${t.javaClass.simpleName})")
                releaseRecord()
                runCatching { SherpaNcnnInstance.endSession() }
                abandonFocus()
                finishWithResult("")
            }
        }
        sessionJob = job
    }

    private fun appendSegment(sb: StringBuilder, segment: String) {
        if (segment.isBlank()) return
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(segment)
    }

    private fun accumulatedText(sb: StringBuilder, current: String): String =
        if (sb.isEmpty()) current else "${sb.toString()} $current"

    private fun releaseRecord() {
        val rec = activeRecord
        activeRecord = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun finishWithResult(text: String) {
        _state.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
        Handler(Looper.getMainLooper()).post {
            onDone?.invoke(text)
        }
    }

    override fun stop() {
        val job = sessionJob
        sessionJob = null
        Log.d(TAG, "stop: cancelling job=$job record=${activeRecord != null}")
        job?.cancel()
        // 同步释放麦克风, 避免第二次 start 时 AudioRecord 被占用
        releaseRecord()
        if (job != null) {
            runCatching { kotlinx.coroutines.runBlocking { job.join() } }
        }
        _state.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
        runCatching { SherpaNcnnInstance.endSession() }
        abandonFocus()
    }

    override fun dispose() {
        stop()
    }
}