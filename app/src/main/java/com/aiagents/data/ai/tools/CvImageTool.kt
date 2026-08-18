package com.aiagents.data.ai.tools

import android.content.Context
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.ai.tools.local.WorkspacePathResolver
import com.aiagents.data.automation.ShizukuController
import com.aiagents.data.files.FilesManager
import com.aiagents.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max

private val openCvLock = Any()
@Volatile
private var openCvLoaded = false

/** 首次使用前加载 OpenCV 原生库(带缓存, 并发安全)。 */
internal fun ensureOpenCvLoaded() {
    if (openCvLoaded) return
    synchronized(openCvLock) {
        if (openCvLoaded) return
        check(OpenCVLoader.initLocal()) { "OpenCV 初始化失败，请检查原生库是否已打包" }
        openCvLoaded = true
    }
}

/**
 * 构建 cv_image 工具：基于 OpenCV 的计算机视觉工具包。
 * - `path` 同时支持工作区路径(/workspace/...、/screenshots/...、/sd/...)与安卓目录路径(/storage/emulated/0/...)
 * - 截图 / 浏览器截图可直接叠加网格(带像素坐标)供 AI 定位，或做模板匹配 / 颜色定位 / 轮廓识别得到 X/Y 坐标
 */
fun buildCvImageTool(
    context: Context,
    workspaceRepository: WorkspaceRepository,
    filesManager: FilesManager,
): Tool {
    val pathResolver = WorkspacePathResolver(context, workspaceRepository)
    return Tool(
        name = "cv_image",
        description = """
            Computer vision toolkit built on OpenCV. Analyze an image (e.g. a screenshot) and return pixel coordinates.
            `path` accepts a workspace path (/workspace/... or /screenshots/... or /sd/... or /tool_outputs/...) or an Android path (/storage/emulated/0/...).
            Coordinate system: origin (0,0) at the top-left corner, x increases rightward, y increases downward.
            Operations:
            - grid: overlay a fine grid labeled with pixel coordinates (default), returns the gridded image for coordinate-based location.
            - template: find `template_path` inside the image via template matching; returns the match rect and its center (x, y).
            - find_color: locate regions of a given `color` (hex like "#FF0000"); returns bounding boxes and centers.
            - find_contours: detect prominent object contours; returns bounding boxes and centers.
            - ocr: recognize text locally (Chinese + Latin); returns each text block with its text, bounding box and center coordinates, plus the full extracted text.
            - canny: edge detection result image.
            - gray: grayscale result image.
            - resize: resize by `scale` or `width`.
            - rotate: rotate by `degrees`.
            The processed/detected image is shown with boxes, crosshairs and coordinate labels, and the JSON result contains exact pixel coordinates for clicking.
            The result image is also saved to the shared screenshots directory (visible at /screenshots/<name> in the workspace and on the device under AI-Agent/screenshots); its image_path / image_workspace_path are returned so you can keep it or pass it to other tools.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("description", "Operation: grid (default), template, find_color, find_contours, ocr, canny, gray, resize, rotate")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Workspace path (/workspace/..., /screenshots/..., /sd/..., /tool_outputs/...) or Android path (/storage/emulated/0/...) of the source image")
                    })
                    put("template_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path of the template image to search for (template operation)")
                    })
                    put("grid_cols", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of vertical grid divisions (default auto, ~48px per cell for fine granularity)")
                    })
                    put("grid_rows", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of horizontal grid divisions (default auto by aspect ratio)")
                    })
                    put("color", buildJsonObject {
                        put("type", "string")
                        put("description", "Hex color to locate, e.g. #FF0000 (find_color operation)")
                    })
                    put("threshold", buildJsonObject {
                        put("type", "integer")
                        put("description", "Color tolerance per channel (default 30)")
                    })
                    put("scale", buildJsonObject {
                        put("type", "number")
                        put("description", "Resize scale factor (resize operation, default 1.0)")
                    })
                    put("width", buildJsonObject {
                        put("type", "integer")
                        put("description", "Target width in px (resize operation, keeps aspect ratio)")
                    })
                    put("degrees", buildJsonObject {
                        put("type", "number")
                        put("description", "Rotation angle in degrees (rotate operation)")
                    })
                    put("canny1", buildJsonObject {
                        put("type", "integer")
                        put("description", "Lower Canny threshold (default 100)")
                    })
                    put("canny2", buildJsonObject {
                        put("type", "integer")
                        put("description", "Upper Canny threshold (default 200)")
                    })
                    put("max_contours", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max number of returned regions (default 10)")
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = { false },
        execute = { payload ->
            val params = payload.jsonObject
            val operation = params["operation"]?.jsonPrimitive?.contentOrNull ?: "grid"
            val path = params["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("path is required")
            ensureOpenCvLoaded()

            val devicePath = pathResolver.toDevicePath(path)
                ?: error("找不到图片: $path")
            val src = Imgcodecs.imread(devicePath)
            if (src.empty()) {
                src.release()
                error("无法解码图片: $path")
            }

            val result: CvOpResult = try {
                when (operation) {
                    "grid" -> opGrid(src, params)
                    "template" -> opTemplate(src, params, pathResolver)
                    "find_color" -> opFindColor(src, params)
                    "find_contours" -> opFindContours(src, params)
                    "ocr" -> opOcr(src, context, devicePath)
                    "canny" -> opCanny(src, params)
                    "gray" -> opGray(src)
                    "resize" -> opResize(src, params)
                    "rotate" -> opRotate(src, params)
                    else -> error("未知 operation: $operation")
                }
            } finally {
                src.release()
            }

            buildList {
                if (result.imageBytes != null) {
                    filesManager.createChatFilesByByteArrays(listOf(result.imageBytes))
                        .firstOrNull()?.toString()
                        ?.let { add(UIMessagePart.Image(url = it)) }
                }
                val savedImage = result.imageBytes?.let { saveResultImage(context, it) }
                add(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("operation", operation)
                            put("path", path)
                            put("image_size", buildJsonObject {
                                put("width", result.width)
                                put("height", result.height)
                            })
                            if (savedImage != null) {
                                put("image_path", savedImage.absolutePath)
                                put("image_workspace_path", "/screenshots/${savedImage.name}")
                            }
                            put("result", result.json)
                        }.toString()
                    )
                )
            }
        },
    )
}

/** 把 cv_image 结果图保存到共享 screenshots 目录(设备 AI-Agent/screenshots 与 workspace /screenshots 均可见), 供本地使用或后续工具引用。 */
internal fun saveResultImage(context: Context, bytes: ByteArray): File? = runCatching {
    val dir = ShizukuController.screenshotDir(context)
    val file = File(dir, "cv_${System.currentTimeMillis()}.png")
    file.writeBytes(bytes)
    file.setReadable(true, false)
    file
}.getOrNull()

private class CvOpResult(
    val json: kotlinx.serialization.json.JsonObject,
    val imageBytes: ByteArray?,
    val width: Int,
    val height: Int,
)

/** 默认网格密度: 目标每格约 48px, 保证按钮等小控件也能精确定位 */
internal fun autoGridCols(width: Int): Int = (width / 48).coerceIn(8, 64)

private fun opGrid(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val explicitCols = params["grid_cols"]?.jsonPrimitive?.intOrNull ?: 0
    val cols = if (explicitCols > 0) explicitCols else autoGridCols(src.cols())
    val rowsParam = params["grid_rows"]?.jsonPrimitive?.intOrNull ?: 0
    val rows = if (rowsParam > 0) rowsParam else max(4, cols * src.rows() / src.cols())

    val out = drawGrid(src, cols, rows)
    val w = out.cols()
    val h = out.rows()
    val json = buildJsonObject {
        put("grid_cols", cols)
        put("grid_rows", rows)
        put("cell_width", w / cols)
        put("cell_height", h / rows)
        put("x_lines", buildJsonArray { addAll((listOf(0) + (1 until cols).map { w * it / cols }).map { JsonPrimitive(it) }) })
        put("y_lines", buildJsonArray { addAll((listOf(0) + (1 until rows).map { h * it / rows }).map { JsonPrimitive(it) }) })
        put("message", "Coordinate grid overlaid. Origin (0,0) at top-left corner, x increases rightward, y increases downward. Top edge labels are x, left edge labels are y, crosshairs mark cell centers.")
    }
    return CvOpResult(json, toPngBytes(out), w, h).also { out.release() }
}

/** 在图上叠加带像素坐标的网格: 白+黑双色线任意底色可见, 顶部标 x, 左侧标 y, 原点标 (0,0), 右下标分辨率。 */
internal fun drawGrid(src: Mat, cols: Int, rows: Int): Mat {
    val out = src.clone()
    val w = out.cols()
    val h = out.rows()
    val colX = (1 until cols).map { w * it / cols }
    val rowY = (1 until rows).map { h * it / rows }

    val lineWidth = max(1, w / 900)
    for (x in colX) {
        drawLinePair(out, Point(x.toDouble(), 0.0), Point(x.toDouble(), (h - 1).toDouble()), lineWidth)
    }
    for (y in rowY) {
        drawLinePair(out, Point(0.0, y.toDouble()), Point((w - 1).toDouble(), y.toDouble()), lineWidth)
    }

    val fontScale = (w / 1000f).coerceIn(0.4f, 1.3f).toDouble()
    val thickness = max(1, (fontScale * 2).toInt())

    // 网格过密时每隔一个标一次, 避免文字重叠
    val labelEvery = if (cols > 40) 2 else 1

    // 顶部: x 坐标(右对齐到网格线)
    for ((i, x) in colX.withIndex()) {
        if ((i + 1) % labelEvery != 0) continue
        drawTextPair(out, "$x", Point((x - 26).toDouble(), 16.0), fontScale, thickness)
    }
    // 左侧: y 坐标
    for ((i, y) in rowY.withIndex()) {
        if ((i + 1) % labelEvery != 0) continue
        drawTextPair(out, "$y", Point(4.0, (y + 10).toDouble()), fontScale, thickness)
    }
    // 原点与坐标轴方向提示
    drawTextPair(out, "(0,0)", Point(4.0, 16.0), fontScale, thickness)
    drawTextPair(out, "x→", Point((w - 34).toDouble(), 16.0), fontScale, thickness)
    drawTextPair(out, "y↓", Point(4.0, (h - 8).toDouble()), fontScale, thickness)
    // 右下角分辨率
    drawTextPair(out, "${w}×${h}", Point((w - 4).toDouble(), (h - 6).toDouble()), fontScale, thickness)
    return out
}

/** 对图片字节应用坐标网格叠加(OpenCV), 供截图等工具直接生成带坐标的检测图。cols/rows<=0 时自动按 48px 密度计算。 */
internal fun overlayGridBytes(bytes: ByteArray, cols: Int, rows: Int): ByteArray {
    ensureOpenCvLoaded()
    val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
    if (mat.empty()) {
        mat.release()
        return bytes
    }
    try {
        val c = if (cols > 0) cols else autoGridCols(mat.cols())
        val r = if (rows > 0) rows else max(4, c * mat.rows() / mat.cols())
        val out = drawGrid(mat, c, r)
        return toPngBytes(out).also { out.release() }
    } finally {
        mat.release()
    }
}

/** CV 检测出的单个元素: 带像素坐标框、中心点, 若有文字则附带 text。 */
internal data class CvElementRegion(
    val index: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int,
    val text: String?,
)

/** CV 检测 + 标注结果: 标注后的图像字节 + 元素列表。 */
internal data class CvAnnotatedImage(
    val annotatedImage: ByteArray,
    val regions: List<CvElementRegion>,
)

/**
 * 本地计算机视觉元素检测: 轮廓(OpenCV) + OCR(ML Kit) 找出图中元素,
 * 在原图上画编号框/十字中心/文字, 返回标注图与元素坐标列表。
 * 供 vision 模型(如 image_analysis)结合标注定位图标按钮等无法用文字描述的控件。
 */
internal suspend fun detectCvElements(
    context: Context,
    imageBytes: ByteArray,
    maxRegions: Int = 16,
): CvAnnotatedImage? {
    ensureOpenCvLoaded()

    val mat = Imgcodecs.imdecode(MatOfByte(*imageBytes), Imgcodecs.IMREAD_COLOR)
    if (mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) {
        mat.release()
        return null
    }
    val w = mat.cols()
    val h = mat.rows()

    // OCR(需要设备端文件路径)
    val tmp = File(context.cacheDir, "cv_ocr_${System.currentTimeMillis()}.png")
    tmp.writeBytes(imageBytes)
    val ocrBlocks = try {
        recognizeTextBlocks(context, tmp.absolutePath)
    } catch (t: Throwable) {
        emptyList()
    } finally {
        tmp.delete()
    }

    // 轮廓: 找出明显元素框(用于无文字图标, 如星形按钮)
    val gray = Mat()
    val edges = Mat()
    val dilated = Mat()
    val contourBoxes = mutableListOf<android.graphics.Rect>()
    try {
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Canny(gray, edges, 100.0, 200.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, kernel)
        kernel.release()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val minArea = max(24.0, w * h * 0.0003)
            contourBoxes.addAll(
                contours.asSequence()
                    .map { Imgproc.boundingRect(it) }
                    .map { android.graphics.Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
                    .filter { it.width() * it.height() >= minArea }
                    .sortedByDescending { it.width() * it.height() }
                    .take(48)
            )
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    } finally {
        gray.release()
        edges.release()
        dilated.release()
    }

    // 合并 OCR 文本框 + 轮廓框, 丢弃被更大框完全包含的重复框, 按面积取前 maxRegions
    data class Item(val rect: android.graphics.Rect, val text: String?)
    val all = (ocrBlocks.map { Item(it.rect, it.text) } +
        contourBoxes.map { Item(it, null) })
        .sortedByDescending { it.rect.width() * it.rect.height() }

    val kept = mutableListOf<Item>()
    for (candidate in all) {
        if (kept.size >= maxRegions) break
        // 若已被保留框完全覆盖则跳过
        val contained = kept.any { existing ->
            existing.rect.contains(candidate.rect) && (existing.text != null || candidate.text == null)
        }
        if (!contained) kept.add(candidate)
    }

    if (kept.isEmpty()) {
        mat.release()
        return null
    }

    val out = mat.clone()
    val color = Scalar(0.0, 255.0, 0.0)
    val thickness = max(2, w / 400)
    val fontScale = (w / 1000f).coerceIn(0.4f, 1.3f).toDouble()
    val textThickness = max(1, (fontScale * 2).toInt())

    val regions = kept.mapIndexed { index, item ->
        val rect = item.rect
        val cx = rect.left + rect.width() / 2
        val cy = rect.top + rect.height() / 2
        Imgproc.rectangle(
            out,
            Point(rect.left.toDouble(), rect.top.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            color,
            thickness,
            Imgproc.LINE_AA,
        )
        drawCrosshair(out, cx, cy, color, thickness)
        val label = "${index + 1}:($cx,$cy)${item.text?.let { " $it" } ?: ""}"
        drawTextPair(
            out,
            label,
            Point(rect.left.toDouble(), max(12, rect.top - 6).toDouble()),
            fontScale,
            textThickness,
        )
        CvElementRegion(
            index = index + 1,
            x = rect.left,
            y = rect.top,
            width = rect.width(),
            height = rect.height(),
            centerX = cx,
            centerY = cy,
            text = item.text,
        )
    }

    val annotated = CvAnnotatedImage(annotatedImage = toPngBytes(out), regions = regions)
    out.release()
    mat.release()
    return annotated
}

private suspend fun opTemplate(
    src: Mat,
    params: kotlinx.serialization.json.JsonObject,
    pathResolver: WorkspacePathResolver,
): CvOpResult {
    val templatePath = params["template_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("template 操作需要 template_path")
    val minScore = params["score"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.7

    val templateDevice = pathResolver.toDevicePath(templatePath)
        ?: error("找不到模板图片: $templatePath")
    val tpl = Imgcodecs.imread(templateDevice)
    if (tpl.empty()) {
        tpl.release()
        error("无法解码模板图片: $templatePath")
    }
    val result = Mat()
    try {
        Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
        val mmr = Core.minMaxLoc(result)
        val score = mmr.maxVal
        val x = mmr.maxLoc.x.toInt()
        val y = mmr.maxLoc.y.toInt()
        val w = tpl.cols()
        val h = tpl.rows()
        val found = score >= minScore

        val json = buildJsonObject {
            put("found", found)
            put("score", score)
            put("rect", buildJsonObject {
                put("x", x)
                put("y", y)
                put("width", w)
                put("height", h)
            })
            put("center", buildJsonObject {
                put("x", x + w / 2)
                put("y", y + h / 2)
            })
        }

        if (found) {
            val cx = x + w / 2
            val cy = y + h / 2
            val out = src.clone()
            val thickness = max(2, src.cols() / 400)
            Imgproc.rectangle(
                out,
                Point(x.toDouble(), y.toDouble()),
                Point((x + w).toDouble(), (y + h).toDouble()),
                Scalar(0.0, 255.0, 0.0),
                thickness,
                Imgproc.LINE_AA,
            )
            drawCrosshair(out, cx, cy, Scalar(0.0, 0.0, 255.0), thickness)
            val fontScale = (src.cols() / 1000f).coerceIn(0.4f, 1.3f).toDouble()
            drawTextPair(
                out,
                "($cx,$cy)",
                Point(x.toDouble(), max(16, y - 8).toDouble()),
                fontScale,
                max(1, (fontScale * 2).toInt()),
            )
            return CvOpResult(json, toPngBytes(out), src.cols(), src.rows()).also { out.release() }
        }
        return CvOpResult(json, null, src.cols(), src.rows())
    } finally {
        tpl.release()
        result.release()
    }
}

private fun opFindColor(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val hex = params["color"]?.jsonPrimitive?.contentOrNull?.trim()?.trimStart('#')
        ?: error("find_color 操作需要 color (如 #FF0000)")
    if (hex.length != 6) error("color 必须是 #RRGGBB 格式, 收到: $hex")
    val r = hex.substring(0, 2).toInt(16)
    val g = hex.substring(2, 4).toInt(16)
    val b = hex.substring(4, 6).toInt(16)
    val tolerance = params["threshold"]?.jsonPrimitive?.intOrNull ?: 30

    val lower = Scalar(
        (b - tolerance).coerceAtLeast(0).toDouble(),
        (g - tolerance).coerceAtLeast(0).toDouble(),
        (r - tolerance).coerceAtLeast(0).toDouble(),
    )
    val upper = Scalar(
        (b + tolerance).coerceAtMost(255).toDouble(),
        (g + tolerance).coerceAtMost(255).toDouble(),
        (r + tolerance).coerceAtMost(255).toDouble(),
    )
    val mask = Mat()
    try {
        Core.inRange(src, lower, upper, mask)
        return extractRegions(src, mask, params)
    } finally {
        mask.release()
    }
}

private fun opFindContours(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val canny1 = params["canny1"]?.jsonPrimitive?.intOrNull ?: 100
    val canny2 = params["canny2"]?.jsonPrimitive?.intOrNull ?: 200

    val gray = Mat()
    val edges = Mat()
    val dilated = Mat()
    try {
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Canny(gray, edges, canny1.toDouble(), canny2.toDouble())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, kernel)
        kernel.release()
        return extractRegions(src, dilated, params)
    } finally {
        gray.release()
        edges.release()
        dilated.release()
    }
}

/** 从二值掩码提取连通区域, 按面积排序, 在源图绘制框并返回坐标列表。 */
private fun extractRegions(src: Mat, binary: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val maxContours = params["max_contours"]?.jsonPrimitive?.intOrNull ?: 10
    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    val out = src.clone()
    try {
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = max(24.0, src.cols() * src.rows() * 0.0003)
        val boxes = contours
            .asSequence()
            .map { Imgproc.boundingRect(it) }
            .filter { it.area() >= minArea }
            .sortedByDescending { it.area() }
            .take(maxContours)
            .toList()

        val color = Scalar(0.0, 255.0, 0.0)
        val thickness = max(2, src.cols() / 400)
        val fontScale = (src.cols() / 1000f).coerceIn(0.4f, 1.3f).toDouble()
        val textThickness = max(1, (fontScale * 2).toInt())
        val regions = boxes.mapIndexed { index, rect ->
            val cx = rect.x + rect.width / 2
            val cy = rect.y + rect.height / 2
            Imgproc.rectangle(
                out,
                Point(rect.x.toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                color,
                thickness,
                Imgproc.LINE_AA,
            )
            drawCrosshair(out, cx, cy, color, thickness)
            drawTextPair(
                out,
                "($cx,$cy)",
                Point(rect.x.toDouble(), max(12, rect.y - 6).toDouble()),
                fontScale,
                textThickness,
            )
            buildJsonObject {
                put("index", index)
                put("x", rect.x)
                put("y", rect.y)
                put("width", rect.width)
                put("height", rect.height)
                put("area", rect.area())
                put("center", buildJsonObject {
                    put("x", cx)
                    put("y", cy)
                })
            }
        }

        val json = buildJsonObject {
            put("count", regions.size)
            put("regions", buildJsonArray { addAll(regions) })
        }
        return CvOpResult(json, toPngBytes(out), src.cols(), src.rows())
    } finally {
        contours.forEach { it.release() }
        hierarchy.release()
        out.release()
    }
}

private fun opCanny(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val canny1 = params["canny1"]?.jsonPrimitive?.intOrNull ?: 100
    val canny2 = params["canny2"]?.jsonPrimitive?.intOrNull ?: 200
    val gray = Mat()
    val edges = Mat()
    try {
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Canny(gray, edges, canny1.toDouble(), canny2.toDouble())
        val json = buildJsonObject {
            put("message", "Canny edge detection (thresholds $canny1/$canny2)")
        }
        return CvOpResult(json, toPngBytes(edges), src.cols(), src.rows())
    } finally {
        gray.release()
        edges.release()
    }
}

private fun opGray(src: Mat): CvOpResult {
    val gray = Mat()
    try {
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val json = buildJsonObject { put("message", "Converted to grayscale") }
        return CvOpResult(json, toPngBytes(gray), src.cols(), src.rows())
    } finally {
        gray.release()
    }
}

private fun opResize(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val width = params["width"]?.jsonPrimitive?.intOrNull
    val scale = params["scale"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
    val dst = Mat()
    try {
        if (width != null && width > 0) {
            val h = max(1, src.rows() * width / src.cols())
            Imgproc.resize(src, dst, Size(width.toDouble(), h.toDouble()))
        } else {
            Imgproc.resize(src, dst, Size(), scale, scale, Imgproc.INTER_LINEAR)
        }
        val json = buildJsonObject {
            put("width", dst.cols())
            put("height", dst.rows())
        }
        return CvOpResult(json, toPngBytes(dst), dst.cols(), dst.rows())
    } finally {
        dst.release()
    }
}

private fun opRotate(src: Mat, params: kotlinx.serialization.json.JsonObject): CvOpResult {
    val degrees = params["degrees"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    val center = Point(src.cols() / 2.0, src.rows() / 2.0)
    val rotation = Imgproc.getRotationMatrix2D(center, degrees, 1.0)
    val dst = Mat()
    try {
        Imgproc.warpAffine(src, dst, rotation, src.size())
        val json = buildJsonObject {
            put("degrees", degrees)
            put("width", dst.cols())
            put("height", dst.rows())
        }
        return CvOpResult(json, toPngBytes(dst), dst.cols(), dst.rows())
    } finally {
        rotation.release()
        dst.release()
    }
}

private suspend fun opOcr(
    src: Mat,
    context: Context,
    devicePath: String,
): CvOpResult {
    val blocks = recognizeTextBlocks(context, devicePath)
    val out = src.clone()
    val color = Scalar(0.0, 255.0, 0.0)
    val thickness = max(2, src.cols() / 400)
    val fontScale = (src.cols() / 1000f).coerceIn(0.4f, 1.3f).toDouble()
    val textThickness = max(1, (fontScale * 2).toInt())

    val items = blocks.mapIndexed { index, block ->
        val rect = block.rect
        val cx = rect.left + rect.width() / 2
        val cy = rect.top + rect.height() / 2
        Imgproc.rectangle(
            out,
            Point(rect.left.toDouble(), rect.top.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            color,
            thickness,
            Imgproc.LINE_AA,
        )
        drawCrosshair(out, cx, cy, color, thickness)
        drawTextPair(
            out,
            "($cx,$cy)",
            Point(rect.left.toDouble(), max(12, rect.top - 6).toDouble()),
            fontScale,
            textThickness,
        )
        buildJsonObject {
            put("index", index)
            put("text", block.text)
            put("rect", buildJsonObject {
                put("x", rect.left)
                put("y", rect.top)
                put("width", rect.width())
                put("height", rect.height())
            })
            put("center", buildJsonObject {
                put("x", cx)
                put("y", cy)
            })
        }
    }

    val json = buildJsonObject {
        put("count", items.size)
        put("full_text", blocks.joinToString("\n") { it.text })
        put("text_blocks", buildJsonArray { addAll(items) })
    }
    return CvOpResult(json, toPngBytes(out), src.cols(), src.rows()).also { out.release() }
}

private fun drawCrosshair(mat: Mat, cx: Int, cy: Int, color: Scalar, thickness: Int) {
    val r = max(6, thickness * 3)
    Imgproc.line(
        mat,
        Point((cx - r).toDouble(), cy.toDouble()),
        Point((cx + r).toDouble(), cy.toDouble()),
        color,
        thickness,
        Imgproc.LINE_AA,
    )
    Imgproc.line(
        mat,
        Point(cx.toDouble(), (cy - r).toDouble()),
        Point(cx.toDouble(), (cy + r).toDouble()),
        color,
        thickness,
        Imgproc.LINE_AA,
    )
}

internal fun toPngBytes(mat: Mat): ByteArray {
    val buf = MatOfByte()
    try {
        Imgcodecs.imencode(".png", mat, buf)
        return buf.toArray()
    } finally {
        buf.release()
    }
}

/** 白线 + 黑色偏移副线, 保证任意底色下网格清晰可见 */
private fun drawLinePair(mat: Mat, start: Point, end: Point, width: Int) {
    val thickness = max(1, width)
    Imgproc.line(mat, start, end, Scalar(255.0, 255.0, 255.0), thickness, Imgproc.LINE_AA)
    Imgproc.line(mat, Point(start.x + 1, start.y + 1), Point(end.x + 1, end.y + 1), Scalar(0.0, 0.0, 0.0), thickness, Imgproc.LINE_AA)
}

/** 白色文字 + 黑色偏移副文字(阴影), 任意底色下可读 */
private fun drawTextPair(mat: Mat, text: String, origin: Point, fontScale: Double, thickness: Int) {
    val font = Imgproc.FONT_HERSHEY_SIMPLEX
    Imgproc.putText(mat, text, origin, font, fontScale, Scalar(255.0, 255.0, 255.0), thickness, Imgproc.LINE_AA, false)
    Imgproc.putText(mat, text, Point(origin.x + 1, origin.y + 1), font, fontScale, Scalar(0.0, 0.0, 0.0), thickness, Imgproc.LINE_AA, false)
}
