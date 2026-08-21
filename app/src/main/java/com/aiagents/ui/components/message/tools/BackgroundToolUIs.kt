package com.aiagents.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagents.R
import com.aiagents.ui.components.message.tools.getStringContent
import com.aiagents.ui.modifier.shimmer
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Code

// ── shell_bg ──
object ShellBgToolUI : ToolUIRenderer {
    override val toolName: String = "shell_bg"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val cmd = context.arguments.getStringContent("command") ?: return stringResource(R.string.tool_ui_shell_bg_default)
        val preview = cmd.replace("\n", " ").trim()
        val truncated = if (preview.length > 40) preview.take(40) + "…" else preview
        return stringResource(R.string.tool_ui_shell_bg, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val id = content.getStringContent("id") ?: ""
        val msg = content.getStringContent("message") ?: ""
        Text(
            text = "ID: $id\n$msg",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}

// ── shell_output ──
object ShellOutputToolUI : ToolUIRenderer {
    override val toolName: String = "shell_output"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("id") ?: ""
        return stringResource(R.string.tool_ui_shell_output, id)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val stdout = content.getStringContent("stdout") ?: ""
        val stderr = content.getStringContent("stderr") ?: ""
            val running = content.getStringContent("running") == "true"
        val combined = listOf(stdout, stderr).filterNot { it.isBlank() }.joinToString("\n").trim()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (running) "\u25B6 Running" else "\u23F9 Stopped",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = combined.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── shell_kill ──
object ShellKillToolUI : ToolUIRenderer {
    override val toolName: String = "shell_kill"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("id") ?: ""
        return stringResource(R.string.tool_ui_shell_kill, id)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = false
}

// ── shell_list ──
object ShellListToolUI : ToolUIRenderer {
    override val toolName: String = "shell_list"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.tool_ui_shell_list)

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val procs = content.getStringContent("processes") ?: "[]"
        Text(
            text = procs,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 11.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── node_bg ──
object NodeBgToolUI : ToolUIRenderer {
    override val toolName: String = "node_bg"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String {
        val code = context.arguments.getStringContent("code") ?: return stringResource(R.string.tool_ui_node_bg_default)
        val preview = code.replace("\n", " ").trim()
        val truncated = if (preview.length > 40) preview.take(40) + "…" else preview
        return stringResource(R.string.tool_ui_node_bg, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val id = content.getStringContent("id") ?: ""
        val msg = content.getStringContent("message") ?: ""
        Text(
            text = "ID: $id\n$msg",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}

// ── node_output ──
object NodeOutputToolUI : ToolUIRenderer {
    override val toolName: String = "node_output"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("id") ?: ""
        return stringResource(R.string.tool_ui_node_output, id)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val output = content.getStringContent("output") ?: ""
        val result = content.getStringContent("result") ?: ""
            val running = content.getStringContent("running") == "true"
        val combined = listOf(output, result).filterNot { it.isBlank() }.joinToString("\n").trim()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (running) "\u25B6 Running" else "\u23F9 Done",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = combined.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── node_kill ──
object NodeKillToolUI : ToolUIRenderer {
    override val toolName: String = "node_kill"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("id") ?: ""
        return stringResource(R.string.tool_ui_node_kill, id)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = false
}

// ── node_list ──
object NodeListToolUI : ToolUIRenderer {
    override val toolName: String = "node_list"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.tool_ui_node_list)

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val procs = content.getStringContent("processes") ?: "[]"
        Text(
            text = procs,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 11.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── eval_javascript (WebView-based) ──
object EvalJavascriptToolUI : ToolUIRenderer {
    override val toolName: String = "eval_javascript"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String {
        val code = context.arguments.getStringContent("code") ?: return stringResource(R.string.tool_ui_eval_js_default)
        val preview = code.replace("\n", " ").trim()
        val truncated = if (preview.length > 40) preview.take(40) + "…" else preview
        return stringResource(R.string.tool_ui_eval_js, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val logs = content.getStringContent("logs")
        val result = content.getStringContent("result") ?: ""
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!logs.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = logs.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "Result: ${result.take(200)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 11.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
