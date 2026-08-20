package com.aiagents.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import kotlin.math.min

/**
 * 已加载图片的固有尺寸缓存(按 URL), 用于在 LazyColumn 滚动回收重组合时
 * 保持图片布局稳定, 避免"抽搐"(先占位后跳到图片实际尺寸)。
 */
private val loadedImageSizes = mutableMapOf<String, Size>()

/**
 * 图片尺寸配置
 * - minSizeDp: 最小尺寸（设备小屏时的下限）
 * - maxSizeDp: 最大尺寸（大屏设备时的上限）
 * - 中间区域由图片原始分辨率决定
 */
private const val IMAGE_MIN_SIZE_DP = 120
private const val IMAGE_MAX_SIZE_DP = 400

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
    val density = LocalDensity.current
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
                add(com.aiagents.ui.media.MemesPathFetcher.Factory(context))
            }
            .build()
    }

    // 若该图此前已加载过, 用其固有尺寸固定宽高比, 滚动回收重组合时布局稳定不跳
    val knownSize = remember(model) { model?.let { loadedImageSizes[it] } }
    
    // 根据图片原始尺寸和屏幕尺寸计算约束
    val sizeConstraints = remember(knownSize) {
        if (knownSize != null && knownSize.width > 0f && knownSize.height > 0f) {
            // 有已知尺寸：计算合适的宽高比和尺寸约束
            val aspectRatio = knownSize.width / knownSize.height
            
            // 根据图片原始尺寸决定显示尺寸
            val originalWidthDp = with(density) { knownSize.width.toDp() }
            val originalHeightDp = with(density) { knownSize.height.toDp() }
            
            // 计算目标宽度：在最小和最大之间，尽量保持原始尺寸
            val targetWidth = originalWidthDp.coerceIn(IMAGE_MIN_SIZE_DP.dp, IMAGE_MAX_SIZE_DP.dp)
            val targetHeight = (targetWidth / aspectRatio).coerceIn(IMAGE_MIN_SIZE_DP.dp, IMAGE_MAX_SIZE_DP.dp)
            
            Pair(targetWidth, targetHeight)
        } else {
            // 未知尺寸：使用默认约束
            null
        }
    }

    val stableModifier = if (sizeConstraints != null) {
        modifier
            .widthIn(max = sizeConstraints.first)
            .heightIn(max = sizeConstraints.second)
            .aspectRatio(
                ratio = knownSize!!.width / knownSize.height,
                matchHeightConstraintsFirst = false,
            )
    } else {
        modifier
            .widthIn(min = IMAGE_MIN_SIZE_DP.dp, max = IMAGE_MAX_SIZE_DP.dp)
            .heightIn(min = IMAGE_MIN_SIZE_DP.dp, max = IMAGE_MAX_SIZE_DP.dp)
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
