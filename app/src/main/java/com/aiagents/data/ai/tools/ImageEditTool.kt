package com.aiagents.data.ai.tools

import android.content.Context
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.ai.tools.local.WorkspacePathResolver
import com.aiagents.data.files.FilesManager
import com.aiagents.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * edit_image 工具: 基于 OpenCV 的图片编辑/合成工具。
 * 支持把图片贴到另一张图、拼图、裁剪、缩放、旋转、翻转、加文字/图形标注、
 * 高斯模糊、亮度对比度、扩画布等, 输出保存到共享 screenshots 目录供本地使用。
 */
fun buildImageEditTool(
    context: Context,
    workspaceRepository: WorkspaceRepository,
    filesManager: FilesManager,
): Tool {
    val pathResolver = WorkspacePathResolver(context, workspaceRepository)
    return Tool(
        name = "edit_image",
        description = """
            Edit / compose images with OpenCV. `path` accepts workspace paths (/workspace/... /screenshots/... /sd/...) or Android paths (/storage/emulated/0/...).
            Coordinate system: origin (0,0) top-left, x rightward, y downward. Result image is saved to the shared screenshots dir (device AI-Agent/screenshots + workspace /screenshots) and returned via image_path / image_workspace_path.
            Operations:
            - paste: paste `overlay_path` onto `path` at (x, y). Optional `scale` resizes the overlay, `alpha` (0-1) sets opacity; PNG overlays keep transparency.
            - crop: crop region (x, y, width, height).
            - resize: resize by `width` or `scale`.
            - rotate: rotate by `degrees`.
            - flip: flip image; `direction` = h | v | both.
            - concat: join two images side by side or stacked; `path2` + `direction` = h | v.
            - extend: place image onto a larger canvas (canvas_width, canvas_height, x, y, background hex like #FFFFFF).
            - draw_text: draw `text` at (x, y) with `size`, `color` (hex), `thickness`.
            - draw_shape: draw shape (rect | circle | line) with coordinates and `color` (hex); `fill`=true to fill.
            - blur: gaussian blur with `radius`.
            - adjust: adjust `brightness` (-255..255) and `contrast` (0.0-3.0, 1.0 = none).
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("description", "Operation: paste, crop, resize, rotate, flip, concat, extend, draw_text, draw_shape, blur, adjust")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Source image (background for paste/concat/extend): workspace or Android path")
                    })
                    put("path2", buildJsonObject {
                        put("type", "string")
                        put("description", "Second image (concat): workspace or Android path")
                    })
                    put("overlay_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Image to paste onto the background (paste)")
                    })
                    put("x", buildJsonObject { put("type", "integer"); put("description", "X coordinate (default 0)") })
                    put("y", buildJsonObject { put("type", "integer"); put("description", "Y coordinate (default 0)") })
                    put("width", buildJsonObject { put("type", "integer"); put("description", "Target width (resize) or crop width") })
                    put("height", buildJsonObject { put("type", "integer"); put("description", "Crop height") })
                    put("scale", buildJsonObject { put("type", "number"); put("description", "Scale factor (paste overlay / resize, default 1.0)") })
                    put("degrees", buildJsonObject { put("type", "number"); put("description", "Rotation degrees (rotate)") })
                    put("direction", buildJsonObject { put("type", "string"); put("description", "flip/concat direction: h | v | both") })
                    put("canvas_width", buildJsonObject { put("type", "integer"); put("description", "Canvas width (extend)") })
                    put("canvas_height", buildJsonObject { put("type", "integer"); put("description", "Canvas height (extend)") })
                    put("background", buildJsonObject { put("type", "string"); put("description", "Background color hex #RRGGBB (extend, default #FFFFFF)") })
                    put("alpha", buildJsonObject { put("type", "number"); put("description", "Overlay opacity 0-1 (paste, default 1.0)") })
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text to draw (draw_text)") })
                    put("size", buildJsonObject { put("type", "number"); put("description", "Font scale (draw_text, default 1.0)") })
                    put("color", buildJsonObject { put("type", "string"); put("description", "Color hex #RRGGBB (draw_text / draw_shape, default #FFFFFF)") })
                    put("thickness", buildJsonObject { put("type", "integer"); put("description", "Line thickness (draw_text / draw_shape, default 2)") })
                    put("shape", buildJsonObject { put("type", "string"); put("description", "Shape type: rect | circle | line (draw_shape)") })
                    put("x2", buildJsonObject { put("type", "integer"); put("description", "Second X (rect/line bottom-right or end)") })
                    put("y2", buildJsonObject { put("type", "integer"); put("description", "Second Y (rect/line bottom-right or end)") })
                    put("radius", buildJsonObject { put("type", "integer"); put("description", "Circle radius (draw_shape circle)") })
                    put("fill", buildJsonObject { put("type", "boolean"); put("description", "Fill the shape (draw_shape, default false)") })
                    put("radius_blur", buildJsonObject { put("type", "integer"); put("description", "Blur radius (blur, default 5)") })
                    put("brightness", buildJsonObject { put("type", "integer"); put("description", "Brightness -255..255 (adjust, default 0)") })
                    put("contrast", buildJsonObject { put("type", "number"); put("description", "Contrast 0.0-3.0 (adjust, default 1.0)") })
                },
                required = listOf("operation", "path"),
            )
        },
        needsApproval = { false },
        execute = { payload ->
            val params = payload.jsonObject
            val operation = params["operation"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("operation is required")
            ensureOpenCvLoaded()
            val result = when (operation) {
                "paste" -> opPaste(params, pathResolver)
                "crop" -> opCrop(params, pathResolver)
                "resize" -> opResize(params, pathResolver)
                "rotate" -> opRotate(params, pathResolver)
                "flip" -> opFlip(params, pathResolver)
                "concat" -> opConcat(params, pathResolver)
                "extend" -> opExtend(params, pathResolver)
                "draw_text" -> opDrawText(params, pathResolver)
                "draw_shape" -> opDrawShape(params, pathResolver)
                "blur" -> opBlur(params, pathResolver)
                "adjust" -> opAdjust(params, pathResolver)
                else -> error("未知 operation: $operation")
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
                            put("path", params["path"]?.jsonPrimitive?.contentOrNull)
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

private class EditResult(
    val json: JsonObject,
    val imageBytes: ByteArray?,
    val width: Int,
    val height: Int,
)

private suspend fun loadImage(params: kotlinx.serialization.json.JsonObject, key: String, resolver: WorkspacePathResolver): Mat {
    val path = params[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("$key is required")
    val device = resolver.toDevicePath(path) ?: error("找不到图片: $path")
    val mat = Imgcodecs.imread(device, Imgcodecs.IMREAD_UNCHANGED)
    if (mat.empty()) {
        mat.release()
        error("无法解码图片: $path")
    }
    return mat
}

private fun toBgr(mat: Mat): Mat = if (mat.channels() >= 4) {
    val bgr = Mat()
    Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_BGRA2BGR)
    mat.release()
    bgr
} else {
    mat
}

private fun hexColor(hex: String?): Scalar {
    val h = hex?.trim()?.trimStart('#') ?: return Scalar(255.0, 255.0, 255.0)
    if (h.length != 6) return Scalar(255.0, 255.0, 255.0)
    val r = h.substring(0, 2).toIntOrNull(16) ?: return Scalar(255.0, 255.0, 255.0)
    val g = h.substring(2, 4).toIntOrNull(16) ?: return Scalar(255.0, 255.0, 255.0)
    val b = h.substring(4, 6).toIntOrNull(16) ?: return Scalar(255.0, 255.0, 255.0)
    return Scalar(b.toDouble(), g.toDouble(), r.toDouble())
}

private fun clampCoord(v: Int, max: Int): Int = v.coerceIn(0, max)

private suspend fun opPaste(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val bg = loadImage(params, "path", resolver)
    val overlay = loadImage(params, "overlay_path", resolver)
    try {
        val scale = params["scale"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        val alpha = params["alpha"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        var x = params["x"]?.jsonPrimitive?.intOrNull ?: 0
        var y = params["y"]?.jsonPrimitive?.intOrNull ?: 0

        var ov = overlay
        if (scale != 1.0) {
            val nw = max(1, (ov.cols() * scale).toInt())
            val nh = max(1, (ov.rows() * scale).toInt())
            val tmp = Mat()
            Imgproc.resize(ov, tmp, Size(nw.toDouble(), nh.toDouble()))
            ov.release()
            ov = tmp
        }

        if (x >= bg.cols() || y >= bg.rows()) error("paste 位置超出背景范围")
        if (x < 0) x = 0
        if (y < 0) y = 0
        val roiW = min(ov.cols(), bg.cols() - x)
        val roiH = min(ov.rows(), bg.rows() - y)
        if (roiW <= 0 || roiH <= 0) error("paste 区域为空")

        val ovCrop = if (roiW == ov.cols() && roiH == ov.rows()) ov else ov.submat(0, roiH, 0, roiW)
        val roi = bg.submat(y, y + roiH, x, x + roiW)

        if (ovCrop.channels() >= 4) {
            // PNG 透明通道 + 可选整体透明度: result = roi*(255-mask)/255 + overlay*mask/255
            val bgr = Mat()
            Imgproc.cvtColor(ovCrop, bgr, Imgproc.COLOR_BGRA2BGR)
            val mask = Mat()
            Core.extractChannel(ovCrop, mask, 3)
            val mask3 = Mat()
            Imgproc.cvtColor(mask, mask3, Imgproc.COLOR_GRAY2BGR)
            if (alpha < 1.0) Core.multiply(mask3, Scalar.all(alpha), mask3)
            val inv = Mat()
            Core.absdiff(mask3, Scalar.all(255.0), inv)
            val t1 = Mat()
            Core.multiply(roi, inv, t1, 1.0 / 255.0)
            val t2 = Mat()
            Core.multiply(bgr, mask3, t2, 1.0 / 255.0)
            Core.add(t1, t2, roi)
            bgr.release(); mask.release(); mask3.release(); inv.release(); t1.release(); t2.release()
        } else {
            if (alpha >= 1.0) {
                ovCrop.copyTo(roi)
            } else {
                Core.addWeighted(roi, 1 - alpha, ovCrop, alpha, 0.0, roi)
            }
        }
        if (ovCrop !== ov) ovCrop.release()

        val json = buildJsonObject {
            put("x", x)
            put("y", y)
            put("width", roiW)
            put("height", roiH)
            put("scale", scale)
            put("alpha", alpha)
        }
        return EditResult(json, toPngBytes(bg), bg.cols(), bg.rows())
    } finally {
        bg.release()
        overlay.release()
    }
}

private suspend fun opCrop(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val x = clampCoord(params["x"]?.jsonPrimitive?.intOrNull ?: 0, src.cols() - 1)
        val y = clampCoord(params["y"]?.jsonPrimitive?.intOrNull ?: 0, src.rows() - 1)
        val w = (params["width"]?.jsonPrimitive?.intOrNull ?: src.cols()).coerceAtLeast(1)
        val h = (params["height"]?.jsonPrimitive?.intOrNull ?: src.rows()).coerceAtLeast(1)
        val rect = Rect(x, y, min(w, src.cols() - x), min(h, src.rows() - y))
        if (rect.width <= 0 || rect.height <= 0) error("crop 区域为空")
        val out = src.submat(rect)
        val json = buildJsonObject {
            put("x", rect.x)
            put("y", rect.y)
            put("width", rect.width)
            put("height", rect.height)
        }
        return EditResult(json, toPngBytes(out), rect.width, rect.height).also { out.release() }
    } finally {
        src.release()
    }
}

private suspend fun opResize(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val width = params["width"]?.jsonPrimitive?.intOrNull
        val scale = params["scale"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        val dst = Mat()
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
        return EditResult(json, toPngBytes(dst), dst.cols(), dst.rows())
    } finally {
        src.release()
    }
}

private suspend fun opRotate(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val degrees = params["degrees"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rotation = Imgproc.getRotationMatrix2D(center, degrees, 1.0)
        val dst = Mat()
        Imgproc.warpAffine(src, dst, rotation, src.size())
        rotation.release()
        val json = buildJsonObject {
            put("degrees", degrees)
            put("width", dst.cols())
            put("height", dst.rows())
        }
        return EditResult(json, toPngBytes(dst), dst.cols(), dst.rows())
    } finally {
        src.release()
    }
}

private suspend fun opFlip(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "h"
        val code = when (direction) {
            "h" -> 1
            "v" -> 0
            "both" -> -1
            else -> error("direction 必须是 h / v / both")
        }
        val out = Mat()
        Core.flip(src, out, code)
        val json = buildJsonObject { put("direction", direction) }
        return EditResult(json, toPngBytes(out), out.cols(), out.rows())
    } finally {
        src.release()
    }
}

private suspend fun opConcat(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val a = toBgr(loadImage(params, "path", resolver))
    val b = toBgr(loadImage(params, "path2", resolver))
    try {
        val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "h"
        val canvas: Mat
        val json: JsonObject
        if (direction == "v") {
            val targetW = a.cols()
            val b2 = Mat()
            Imgproc.resize(b, b2, Size(targetW.toDouble(), max(1, (b.rows() * targetW.toDouble() / b.cols()).toInt()).toDouble()))
            canvas = Mat(a.rows() + b2.rows(), targetW, a.type(), Scalar.all(255.0))
            a.copyTo(canvas.submat(0, a.rows(), 0, targetW))
            b2.copyTo(canvas.submat(a.rows(), a.rows() + b2.rows(), 0, targetW))
            b2.release()
            json = buildJsonObject {
                put("direction", "v")
                put("width", canvas.cols())
                put("height", canvas.rows())
            }
        } else {
            val targetH = a.rows()
            val b2 = Mat()
            Imgproc.resize(b, b2, Size(max(1, (b.cols() * targetH.toDouble() / b.rows()).toInt()).toDouble(), targetH.toDouble()))
            canvas = Mat(targetH, a.cols() + b2.cols(), a.type(), Scalar.all(255.0))
            a.copyTo(canvas.submat(0, targetH, 0, a.cols()))
            b2.copyTo(canvas.submat(0, targetH, a.cols(), a.cols() + b2.cols()))
            b2.release()
            json = buildJsonObject {
                put("direction", "h")
                put("width", canvas.cols())
                put("height", canvas.rows())
            }
        }
        return EditResult(json, toPngBytes(canvas), canvas.cols(), canvas.rows()).also { canvas.release() }
    } finally {
        a.release()
        b.release()
    }
}

private suspend fun opExtend(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val cw = (params["canvas_width"]?.jsonPrimitive?.intOrNull ?: src.cols()).coerceAtLeast(src.cols())
        val ch = (params["canvas_height"]?.jsonPrimitive?.intOrNull ?: src.rows()).coerceAtLeast(src.rows())
        val x = params["x"]?.jsonPrimitive?.intOrNull ?: 0
        val y = params["y"]?.jsonPrimitive?.intOrNull ?: 0
        val canvas = Mat(ch, cw, src.type(), hexColor(params["background"]?.jsonPrimitive?.contentOrNull))
        val px = x.coerceIn(0, cw)
        val py = y.coerceIn(0, ch)
        val dw = min(src.cols(), cw - px)
        val dh = min(src.rows(), ch - py)
        if (dw > 0 && dh > 0) {
            src.submat(0, dh, 0, dw).copyTo(canvas.submat(py, py + dh, px, px + dw))
        }
        val json = buildJsonObject {
            put("x", px)
            put("y", py)
            put("width", cw)
            put("height", ch)
        }
        return EditResult(json, toPngBytes(canvas), cw, ch).also { canvas.release() }
    } finally {
        src.release()
    }
}

private suspend fun opDrawText(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val text = params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("draw_text 需要 text")
        val x = params["x"]?.jsonPrimitive?.intOrNull ?: 0
        val y = params["y"]?.jsonPrimitive?.intOrNull ?: 20
        val fontScale = params["size"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        val color = hexColor(params["color"]?.jsonPrimitive?.contentOrNull)
        val thickness = max(1, params["thickness"]?.jsonPrimitive?.intOrNull ?: (fontScale * 2).toInt())
        val font = Imgproc.FONT_HERSHEY_SIMPLEX
        Imgproc.putText(src, text, Point(x + 1.0, y + 1.0), font, fontScale, Scalar(0.0, 0.0, 0.0), thickness, Imgproc.LINE_AA, false)
        Imgproc.putText(src, text, Point(x.toDouble(), y.toDouble()), font, fontScale, color, thickness, Imgproc.LINE_AA, false)
        val json = buildJsonObject {
            put("text", text)
            put("x", x)
            put("y", y)
        }
        return EditResult(json, toPngBytes(src), src.cols(), src.rows())
    } finally {
        src.release()
    }
}

private suspend fun opDrawShape(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val shape = params["shape"]?.jsonPrimitive?.contentOrNull ?: "rect"
        val color = hexColor(params["color"]?.jsonPrimitive?.contentOrNull)
        val thickness = max(1, params["thickness"]?.jsonPrimitive?.intOrNull ?: 2)
        val fill = params["fill"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val t = if (fill) Imgproc.FILLED else thickness
        val x1 = params["x"]?.jsonPrimitive?.intOrNull ?: 0
        val y1 = params["y"]?.jsonPrimitive?.intOrNull ?: 0
        val x2 = params["x2"]?.jsonPrimitive?.intOrNull ?: x1
        val y2 = params["y2"]?.jsonPrimitive?.intOrNull ?: y1
        when (shape) {
            "rect" -> Imgproc.rectangle(src, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), color, t, Imgproc.LINE_AA)
            "circle" -> {
                val r = params["radius"]?.jsonPrimitive?.intOrNull ?: 10
                Imgproc.circle(src, Point(x1.toDouble(), y1.toDouble()), r, color, t, Imgproc.LINE_AA)
            }
            "line" -> Imgproc.line(src, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), color, t, Imgproc.LINE_AA)
            else -> error("shape 必须是 rect / circle / line")
        }
        val json = buildJsonObject { put("shape", shape) }
        return EditResult(json, toPngBytes(src), src.cols(), src.rows())
    } finally {
        src.release()
    }
}

private suspend fun opBlur(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val radius = (params["radius_blur"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 50)
        val out = Mat()
        val k = radius * 2 + 1
        Imgproc.GaussianBlur(src, out, Size(k.toDouble(), k.toDouble()), 0.0)
        val json = buildJsonObject { put("radius", radius) }
        return EditResult(json, toPngBytes(out), out.cols(), out.rows())
    } finally {
        src.release()
    }
}

private suspend fun opAdjust(params: kotlinx.serialization.json.JsonObject, resolver: WorkspacePathResolver): EditResult {
    val src = toBgr(loadImage(params, "path", resolver))
    try {
        val brightness = params["brightness"]?.jsonPrimitive?.intOrNull ?: 0
        val contrast = params["contrast"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        val out = Mat()
        src.convertTo(out, -1, contrast, brightness.toDouble())
        val json = buildJsonObject {
            put("brightness", brightness)
            put("contrast", contrast)
        }
        return EditResult(json, toPngBytes(out), out.cols(), out.rows())
    } finally {
        src.release()
    }
}
