package com.aiagents.data.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.provider.ProviderManager
import com.aiagents.ai.provider.TextGenerationParams
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.findModelById
import com.aiagents.data.datastore.findProvider
import com.aiagents.data.files.FilesManager
import com.aiagents.data.repository.WorkspaceRepository
import kotlin.math.max

private const val MAX_ANALYSIS_IMAGE_BYTES = 8L * 1024 * 1024

/**
 * 构建 image_analysis 工具：使用设置页配置的 OCR/视觉模型分析工作区内的图片。
 * - `path` 为容器内绝对路径（/workspace/images/... 等）
 * - `prompt` 传入要询问的问题/指令
 * - `grid` 为 true 时在图片上叠加网格线（适合 UI 布局/坐标分析），分析模型可据此定位
 * 分析结果回显到对话，叠加网格的图片也一并显示。
 */
fun buildImageAnalysisTool(
    context: Context,
    settings: Settings,
    providerManager: ProviderManager,
    workspaceRepository: WorkspaceRepository,
    workspaceId: String?,
    filesManager: FilesManager,
): Tool = Tool(
    name = "image_analysis",
    description = """
        Analyzes an image using the configured OCR/vision model.
        Provide the absolute image path inside the workspace rootfs (e.g. /workspace/images/xxx.png),
        a `prompt` describing what to analyze or ask about the image,
        and optionally enable `grid` to overlay grid lines on the image
        (useful for layout/coordinate analysis, e.g. UI screenshots).
        `cv` runs local computer vision first (OpenCV contour detection + OCR): it finds the
        clickable/text elements, annotates them with numbered boxes and pixel coordinates on the
        image, and passes that region list to the vision model together with the (gridded) image.
        This is the recommended mode for UI screenshots / element location: CV pinpoints physical
        elements (even icon-only buttons with no visible text, e.g. a star-shaped favorite button),
        and the vision model recognizes each element's meaning and reports exact coordinates.
        Recommended CV operation by element type:
        - Text input fields / labels / any text → OCR (returns each text block's text + bounding box + center).
        - Icon-only buttons (star, heart, etc.) → contour detection (find_contours) pinpoints them even with no text.
        - Colored buttons / regions → find_color.
        - Known template icons → template matching.
        Prefer the operations that directly return X/Y coordinates (center_x, center_y) so you can click precisely.
        IMPORTANT: enabling `grid` automatically forces `cv` on, because requesting a grid means you want
        coordinates — CV annotation is then mandatory to give you precise element coordinates.
        Note: the image is automatically compressed (quality/downscaled) by the app before being sent to the
        vision model to fit input limits. Do NOT pre-compress, downscale or otherwise preprocess the image yourself —
        just pass the original path.
        The analysis result and the annotated image are returned.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute image path inside the workspace rootfs, e.g. /workspace/images/xxx.png")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "What to analyze or ask about the image (e.g. describe the layout, read the text, find coordinates, locate specific UI elements)")
                })
                put("grid", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overlay grid lines on the image for layout/coordinate analysis. Defaults to false. Enabling grid automatically forces cv on.")
                })
                put("cv", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Run local computer vision first (OpenCV contour + OCR) to detect and annotate elements with numbered boxes and coordinates, then analyze with the vision model. Recommended for UI screenshots so the model can identify icon-only buttons (e.g. a star = favorite) and their exact coordinates. Automatically forced on when grid is enabled. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("path is required")
        // 未显式传入 prompt 时, 使用设置页配置的 OCR 提示词作为默认
        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: settings.ocrPrompt
        val grid = params["grid"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        // 开启网格即代表 AI 想要坐标, 强制启用 CV 辅助标注
        val cv = (params["cv"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false) || grid

        if (workspaceId.isNullOrBlank()) {
            error("图片分析需要绑定工作区，请先为助手配置工作区")
        }

        // 1. 读图
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        val bytes = ByteArrayOutputStream(size.toInt()).also { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
        }.toByteArray()

// 2. 压缩: 图片过大时主动压缩, 而不是直接失败
        val compressed = compressImageIfNeeded(bytes, MAX_ANALYSIS_IMAGE_BYTES)

        // 3. 可选 CV 识别: OpenCV + OCR 检测元素, 标注编号/坐标
        val cvAnnotated = if (cv) detectCvElements(context, compressed) else null

        // 4. 可选网格叠加; CV 标注图已自带编号框, 不重复叠加网格
        val displayBytes = if (cvAnnotated != null) cvAnnotated.annotatedImage
            else if (grid) overlayGrid(compressed) else compressed

        // 5. 生成图片 URI(统一用 file://, 其他工具如 auto_screenshot/cv_image 都用此方式, 兼容性好):
        val fileUri = filesManager.createChatFilesByByteArrays(listOf(displayBytes)).firstOrNull()?.toString()
            ?: error("无法将图片写入本地缓存")
        val displayUri = fileUri
        val modelUri = fileUri

        // 6. 调用 OCR/视觉模型分析
        val model = settings.findModelById(settings.ocrModelId)
            ?: error("未配置 OCR/视觉模型，请先在 设置-模型 中配置")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model: ${model.displayName}")
        val provider = providerManager.getProviderByType(providerSetting)
        val systemPrompt = if (cvAnnotated != null) {
            """
                你是图片分析助手。图片上已用本地计算机视觉标注了带编号的元素框:
                - 每个元素有一个编号 (number), 一个像素坐标框 (x, y, width, height) 与中心点 (center_x, center_y)。
                - 坐标原点是图片左上角, x 向右, y 向下。
                - 标注覆盖了文本、输入框、按钮、图标等各类元素。某些图标按钮(如星形收藏、心形喜欢等)没有文字,
                  但本地 CV 已把它们标注出来, 请根据图示确认它们的含义(比如星形是收藏按钮)。
                - 当用户需要定位/点击某个元素时, 必须给出该元素的编号与中心坐标 (center_x, center_y), 便于直接点击。
                根据用户的问题, 结合标注编号与坐标给出答案。
            """.trimIndent()
        } else {
            "你是图片分析助手。根据用户的问题分析图片内容。若图片带网格，可结合网格坐标定位元素。"
        }
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(systemPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = buildList {
                        add(UIMessagePart.Image(modelUri))
                        if (cvAnnotated != null) {
                            add(
                                UIMessagePart.Text(
                                    "本地计算机视觉已标注了元素编号与坐标(左上角为原点, x向右, y向下)。" +
                                        "请先结合下图标注, 识别每个元素的含义(如星形=收藏按钮), " +
                                        "再给出回答所需元素的编号与中心坐标。\n" +
                                        cvAnnotated.regions.joinToString("\n") { r ->
                                            "#${r.index} box=(${r.x},${r.y} ${r.width}x${r.height}) " +
                                                "center=(${r.centerX},${r.centerY})" +
                                                (r.text?.let { " text=$it" } ?: "")
                                        }
                                )
                            )
                        }
                        add(UIMessagePart.Text(prompt))
                    },
                ),
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices.firstOrNull()?.message?.toText()
            ?: error("图片分析失败：模型无输出")

        buildList {
            displayUri?.let { add(UIMessagePart.Image(url = it.toString())) }
            add(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("grid", grid)
                        put("cv", cv)
                        put("analysis", content)
                    }.toString()
                )
            )
        }
    },
)

/** 在图片上叠加带像素坐标的网格, 返回重编码后的 PNG 字节。
 *  网格线逐像素反色（不遮挡画面内容、任何底色下都清晰），
 *  上边标注 x 像素值、左边标注 y 像素值，右下角标注整图分辨率，供 AI 精确定位点击坐标。 */
private fun overlayGrid(bytes: ByteArray): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: return bytes // 非图片/解码失败时原样返回
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return bytes

    val src = IntArray(width * height)
    bitmap.getPixels(src, 0, width, 0, 0, width, height)
    bitmap.recycle()

    // 反色: 保留不透明度, 仅翻转 RGB
    fun invert(color: Int): Int = 0xFF000000.toInt() or (color xor 0x00FFFFFF)

    // 16 等分网格
    val cols = 16
    val rows = (16.0 * height / width).toInt().coerceAtLeast(4)
    val colX = IntArray(cols - 1) { width * (it + 1) / cols }
    val rowY = IntArray(rows - 1) { height * (it + 1) / rows }

    // 网格线像素直接取源图对应位置的反色, 不叠加半透明色, 不减弱原图信息
    val dst = src.copyOf()
    for (x in colX) {
        var y = 0
        while (y < height) {
            val i = y * width + x
            dst[i] = invert(src[i])
            y++
        }
    }
    for (y in rowY) {
        var x = 0
        while (x < width) {
            dst[y * width + x] = invert(src[y * width + x])
            x++
        }
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(dst, 0, width, 0, 0, width, height)
    val canvas = Canvas(out)

    // 坐标标签: 文字用所在位置像素的反色, 同位置原色做阴影, 任何底色下都可读
    val labelSize = (width / 52f).coerceIn(12f, 26f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun drawLabel(value: Int, cx: Float, cy: Float, anchorIndex: Int) {
        val anchor = src[anchorIndex.coerceIn(0, src.lastIndex)]
        textPaint.color = invert(anchor)
        textPaint.setShadowLayer(3f, 0f, 0f, anchor)
        canvas.drawText(value.toString(), cx, cy, textPaint)
    }

    // 上边: 每个 x 坐标标签 (右对齐到网格线)
    textPaint.textAlign = Paint.Align.RIGHT
    drawLabel(0, 4f, labelSize, 0)
    for (x in colX) {
        drawLabel(x, x - 3f, labelSize, x)
    }

    // 左边: 每个 y 坐标标签 (垂直居中于网格线, 原点已在左上角)
    textPaint.textAlign = Paint.Align.LEFT
    for (y in rowY) {
        drawLabel(y, 3f, y + labelSize * 0.35f, y * width)
    }

    // 右下角: 整图分辨率
    val dim = "${width}×${height}"
    val dimIndex = src.lastIndex
    textPaint.color = invert(src[dimIndex])
    textPaint.setShadowLayer(3f, 0f, 0f, src[dimIndex])
    textPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText(dim, width - 4f, height - labelSize * 0.25f, textPaint)

    val outStream = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, outStream)
    return outStream.toByteArray()
}

/**
 * 图片超过上限时主动压缩:
 * 1. 先重编码为 JPEG(质量逐步降低), 保持分辨率
 * 2. 仍超限则按比例缩小尺寸后重编码
 * 返回压缩后的字节; 解码失败/非图片时原样返回。
 */
private fun compressImageIfNeeded(bytes: ByteArray, maxBytes: Long): ByteArray {
    if (bytes.size.toLong() <= maxBytes) return bytes
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes

    var current = bitmap
    val outStream = ByteArrayOutputStream()
    // 质量阶梯: 85 -> 70 -> 55 -> 40
    for (quality in listOf(85, 70, 55, 40)) {
        outStream.reset()
        current.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
        if (outStream.size().toLong() <= maxBytes) {
            return outStream.toByteArray()
        }
    }
    // 质量压到底仍超限, 缩小尺寸(每次 0.8x)
    var scale = 0.8f
    while (outStream.size().toLong() > maxBytes && scale > 0.2f) {
        val w = (current.width * scale).toInt().coerceAtLeast(64)
        val h = (current.height * scale).toInt().coerceAtLeast(64)
        current = Bitmap.createScaledBitmap(current, w, h, true)
        outStream.reset()
        current.compress(Bitmap.CompressFormat.JPEG, 70, outStream)
        scale *= 0.8f
    }
    if (current !== bitmap) bitmap.recycle()
    return outStream.toByteArray()
}
