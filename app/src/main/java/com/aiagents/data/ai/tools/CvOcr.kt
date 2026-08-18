package com.aiagents.data.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OCR 识别出的文本块, rect 为原图像素坐标, 原点在左上角。 */
internal data class CvTextBlock(
    val text: String,
    val rect: Rect,
)

/**
 * 本地 OCR(ML Kit 中文+拉丁 bundled 模型, 离线可用)。
 * 直接读取设备上的图片文件并返回识别文本块及其像素坐标, 供 AI 拿取屏幕/图片文本并定位。
 */
internal suspend fun recognizeTextBlocks(context: Context, imagePath: String): List<CvTextBlock> =
    withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val bitmap = BitmapFactory.decodeFile(imagePath)
                ?: error("无法解码图片: $imagePath")
            val result = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            } finally {
                bitmap.recycle()
            }
            result.textBlocks.map { block ->
                CvTextBlock(
                    text = block.text,
                    rect = block.boundingBox ?: Rect(),
                )
            }
        } finally {
            recognizer.close()
        }
    }
