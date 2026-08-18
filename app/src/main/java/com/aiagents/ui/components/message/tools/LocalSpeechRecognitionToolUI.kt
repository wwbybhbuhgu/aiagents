package com.aiagents.ui.components.message.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiagents.R
import com.aiagents.data.ai.tools.local.LocalSpeechRecognitionUiState
import com.aiagents.ui.modifier.shimmer
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Voice

/**
 * 本地语音识别 (local_speech_recognition) 渲染器:
 * - 录音进行中(卡片 loading)时, 实时显示已识别的文本 (来自 [LocalSpeechRecognitionUiState]);
 * - 录音结束后显示最终识别结果。
 */
object LocalSpeechRecognitionToolUI : ToolUIRenderer {
    override val toolName: String = "local_speech_recognition"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Voice

    @Composable
    override fun title(context: ToolUIContext): String =
        if (context.loading) {
            stringResource(R.string.tool_ui_speech_recording)
        } else {
            stringResource(R.string.tool_ui_speech_recognized)
        }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.tool.isExecuted

    @Composable
    override fun Summary(context: ToolUIContext) {
        val recording by LocalSpeechRecognitionUiState.recording.collectAsState()
        val partial by LocalSpeechRecognitionUiState.partialText.collectAsState()
        val saveAudioMode by LocalSpeechRecognitionUiState.saveAudioMode.collectAsState()

        val finalText = if (!context.loading && context.tool.isExecuted) {
            context.tool.output
                .filterIsInstance<com.aiagents.ai.ui.UIMessagePart.Text>()
                .joinToString("\n") { it.text }
        } else {
            ""
        }

        val display = when {
            context.loading && recording && partial.isNotBlank() -> partial
            context.loading -> stringResource(R.string.tool_ui_speech_waiting)
            finalText.isNotBlank() -> finalText
            else -> return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            if (context.loading) {
                Icon(
                    imageVector = HugeIcons.Voice,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Tick01,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                // save_audio 长录音下文本行多、更新频繁, 关闭 shimmer 常驻动画,
                // 避免 HWUI 渲染线程在持续重组+动画下出现 use-after-free (destroyed mutex / regex)
                Text(
                    text = display,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.shimmer(isLoading = context.loading && recording && !saveAudioMode),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (context.loading && saveAudioMode) {
                    StopRecordingButton()
                }
            }
        }
    }
}

/** save_audio 长录音模式的"终止"按钮: 点击后通知录音循环停止并保存音频文件 */
@Composable
private fun StopRecordingButton() {
    // 注意: 不能使用 Material3 Button (含 ripple 波纹动画 + elevation 阴影, 在点击瞬间会创建
    // HWUI 硬件层, 然后请求稍后即被从组合树移除 → 渲染线程销毁图层时仍在绘制 → 确定性 UAF,
    // 症状: hwuiTask0/1 同时 pthread_mutex destroyed + std::regex_error)。改用无 indication、
    // 无 elevation 的静态 Box, 点击路径不会产生任何动画图层。
    val interactionSource = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { LocalSpeechRecognitionUiState.requestStop() },
            )
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.tool_ui_speech_stop),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}