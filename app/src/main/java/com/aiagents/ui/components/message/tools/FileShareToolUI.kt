package com.aiagents.ui.components.message.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.json.longOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import org.koin.java.KoinJavaComponent.getKoin
import com.aiagents.R
import com.aiagents.common.http.jsonObjectOrNull
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.ui.context.LocalToaster
import com.aiagents.utils.jsonPrimitiveOrNull

/**
 * file_share 工具渲染器：把工作区文件渲染成"分享卡片"，
 * 提供保存到设备（Downloads）与系统分享（ACTION_SEND）两个操作。
 */
object FileShareToolUI : ToolUIRenderer {
    override val toolName: String = "file_share"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.content?.getStringContent("name")
            ?: context.arguments.getStringContent("path")?.substringAfterLast('/')
        return if (!name.isNullOrBlank()) {
            stringResource(R.string.tool_ui_share_file, name)
        } else {
            stringResource(R.string.tool_ui_share_file_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.tool.isExecuted

    @Composable
    override fun Summary(context: ToolUIContext) {
        FileShareCard(context = context)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title(context),
                style = MaterialTheme.typography.headlineSmall,
            )
            FileShareCard(context = context, expanded = true)
        }
    }
}

@Composable
private fun FileShareCard(context: ToolUIContext, expanded: Boolean = false) {
    val content = context.content ?: return
    val path = content.getStringContent("path") ?: return
    val workspaceId = content.getStringContent("workspaceId")
    val name = content.getStringContent("name") ?: path.substringAfterLast('/')
    val sizeBytes = content.jsonObjectOrNull?.get("sizeBytes")?.jsonPrimitiveOrNull?.longOrNull ?: 0L

    val contextProvider = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val exportFailedMsg = stringResource(R.string.file_share_export_failed)
    val savedToDownloadsMsg = stringResource(R.string.file_share_saved_to_downloads)
    val saveFailedMsg = stringResource(R.string.file_share_save_failed)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.FileView,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSize(sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val bytes = exportWorkspaceFile(workspaceId, path)
                        if (bytes == null) {
                            toaster.show(
                                message = exportFailedMsg,
                                type = ToastType.Error,
                            )
                        } else if (saveToDownloads(contextProvider, name, bytes)) {
                            toaster.show(
                                message = savedToDownloadsMsg,
                                type = ToastType.Success,
                            )
                        } else {
                            toaster.show(
                                message = saveFailedMsg,
                                type = ToastType.Error,
                            )
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.file_share_save))
            }
            Button(
                onClick = {
                    scope.launch {
                        val bytes = exportWorkspaceFile(workspaceId, path)
                        if (bytes == null) {
                            toaster.show(
                                message = exportFailedMsg,
                                type = ToastType.Error,
                            )
                        } else {
                            shareFile(contextProvider, name, bytes)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.file_share_share))
            }
        }
    }
}

/** 从工作区导出文件字节 */
private suspend fun exportWorkspaceFile(workspaceId: String?, path: String): ByteArray? {
    if (workspaceId.isNullOrBlank()) return null
    return runCatching {
        val repo = getKoin().get<WorkspaceRepository>()
        val size = repo.rootfsFileSize(workspaceId, path)
        ByteArrayOutputStream(size.toInt()).also { out ->
            repo.exportRootfsFile(workspaceId, path, out)
        }.toByteArray()
    }.getOrNull()
}

/** 保存到公共下载目录（MediaStore） */
private fun saveToDownloads(context: Context, name: String, bytes: ByteArray): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)
}

/** 通过系统分享面板分享文件 */
private fun shareFile(context: Context, name: String, bytes: ByteArray) {
    runCatching {
        val file = File(context.cacheDir, "share_${System.currentTimeMillis()}_$name")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}

/** 文件大小人类可读格式 */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
