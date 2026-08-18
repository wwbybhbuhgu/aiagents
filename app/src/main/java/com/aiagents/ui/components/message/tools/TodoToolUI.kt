package com.aiagents.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Circle
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Tools
import com.aiagents.R
import com.aiagents.common.http.jsonObjectOrNull
import com.aiagents.utils.jsonPrimitiveOrNull

/**
 * todo_write 工具渲染器：每次调用后在聊天中显示任务清单卡片，
 * 主 Agent 的每个 todo 操作都会把当前完整清单渲染给用户。
 */
object TodoToolUI : ToolUIRenderer {
    override val toolName: String = "todo_write"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    @Composable
    override fun title(context: ToolUIContext): String {
        val todos = todoItems(context.content) ?: emptyList()
        return if (todos.isEmpty()) {
            stringResource(R.string.tool_ui_todo_title_empty)
        } else {
            val done = todos.count { it.status == "completed" }
            stringResource(R.string.tool_ui_todo_title, done, todos.size)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.tool.isExecuted

    @Composable
    override fun Summary(context: ToolUIContext) {
        TodoCard(context = context)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title(context),
                style = MaterialTheme.typography.headlineSmall,
            )
            TodoCard(context = context)
        }
    }
}

private data class TodoItemView(
    val id: Long,
    val content: String,
    val status: String,
)

private fun todoItems(content: JsonElement?): List<TodoItemView>? {
    val todos = content?.jsonObjectOrNull?.get("todos") as? JsonArray ?: return null
    return todos.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
        val text = obj["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
        val status = obj["status"]?.jsonPrimitiveOrNull?.contentOrNull ?: "pending"
        TodoItemView(id = id, content = text, status = status)
    }
}

/** 状态对应图标：完成 / 进行中 / 未完成 */
private fun statusIcon(status: String): ImageVector = when (status) {
    "completed" -> HugeIcons.CheckmarkCircle02
    "in_progress" -> HugeIcons.Clock01
    else -> HugeIcons.Circle
}

/** 状态对应主色 */
@Composable
private fun statusColor(status: String): Color = when (status) {
    "completed" -> MaterialTheme.colorScheme.primary
    "in_progress" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusLabelRes(status: String): Int = when (status) {
    "completed" -> R.string.tool_ui_todo_status_completed
    "in_progress" -> R.string.tool_ui_todo_status_in_progress
    else -> R.string.tool_ui_todo_status_pending
}

@Composable
private fun TodoCard(context: ToolUIContext) {
    val todos = todoItems(context.content) ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (todos.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_todo_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            todos.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = statusIcon(item.status),
                        contentDescription = stringResource(statusLabelRes(item.status)),
                        tint = statusColor(item.status),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.status == "completed") {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        },
                        color = if (item.status == "completed") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(statusLabelRes(item.status)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = statusColor(item.status),
                        modifier = Modifier
                            .background(
                                color = statusColor(item.status).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
