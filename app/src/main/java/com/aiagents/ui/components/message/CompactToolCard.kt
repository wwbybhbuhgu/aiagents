package com.aiagents.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.ui.components.message.tools.ToolUIContext
import com.aiagents.ui.components.message.tools.ToolUIRegistry
import com.aiagents.utils.JsonInstant

/**
 * 轻量场景(如悬浮窗)使用的紧凑工具卡片:
 * 标题行(图标 + 本地化名称/状态) + 注册渲染器的内联摘要(如 todo 清单卡片、记忆内容等)。
 */
@Composable
fun CompactToolCard(
    tool: UIMessagePart.Tool,
    modifier: Modifier = Modifier,
) {
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = false,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = renderer.title(context),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (renderer.hasSummary(context)) {
                renderer.Summary(context)
            }
        }
    }
}
