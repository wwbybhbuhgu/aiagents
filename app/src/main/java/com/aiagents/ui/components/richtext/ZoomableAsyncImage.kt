package com.aiagents.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import com.aiagents.R
import com.aiagents.ui.components.ui.ImagePreviewDialog
import com.aiagents.ui.components.ui.LocalExportContext
import com.aiagents.ui.media.WorkspaceFileFetcher
import com.aiagents.ui.modifier.shimmer
import com.aiagents.ui.theme.LocalDarkMode
import com.aiagents.ui.theme.LocalOverlaySafe
import org.koin.compose.koinInject

/**
 * 已加载图片的固有尺寸缓存(按 URL), 用于在 LazyColumn 滚动回收重组合时
 * 保持图片布局稳定, 避免"抽搐"(先占位后跳到图片实际尺寸)。
 */
private val loadedImageSizes = mutableMapOf<String, Size>()

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    enablePreview: Boolean = !LocalOverlaySafe.current,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current

    val workspaceRepository: com.aiagents.data.repository.WorkspaceRepository = koinInject()
    val imageLoader = remember(workspaceRepository) {
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    com.aiagents.ui.media.WorkspaceMtimeKeyer("${context.packageName}.workspacefile")
                )
                add(WorkspaceFileFetcher.Factory(workspaceRepository = workspaceRepository))
            }
            .build()
    }

    // 若该图此前已加载过, 用其固有尺寸固定宽高比, 滚动回收重组合时布局稳定不跳
    val knownSize = remember(model) { model?.let { loadedImageSizes[it] } }
    val stableModifier = if (knownSize != null && knownSize.width > 0f && knownSize.height > 0f) {
        modifier.aspectRatio(
            ratio = knownSize.width / knownSize.height,
            matchHeightConstraintsFirst = false,
        )
    } else {
        modifier
    }

    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }
    AsyncImage(
        model = coilModel,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        modifier = stableModifier
            .shimmer(isLoading = loading)
            .then(
                if (enablePreview) {
                    Modifier.clickable {
                        showImageViewer = true
                    }
                } else {
                    Modifier
                }
            ),
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = { state ->
            loading = false
            model?.let { url ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    loadedImageSizes[url] = size
                }
            }
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
