package com.aiagents.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

private const val TAG = "TtsModelDownloader"

/**
 * TTS 模型下载器，支持代理。
 *
 * MOSS-TTS-Nano 模型下载地址（示例）:
 * - decoder.onnx: https://huggingface.co/yl4579/MossTTS/resolve/main/onnx/decoder.onnx
 * - encoder.onnx: https://huggingface.co/yl4579/MossTTS/resolve/main/onnx/encoder.onnx
 */
object TtsModelDownloader {

    /** 默认模型下载基础 URL（HuggingFace） */
    private const val DEFAULT_BASE_URL = "https://huggingface.co/yl4579/MossTTS/resolve/main/onnx"

    /** 代理主机（从系统属性读取，或空 = 直连） */
    private val proxyHost: String? = System.getProperty("http.proxyHost")
    private val proxyPort: Int = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 7890

    /**
     * 下载 MOSS-TTS-Nano 模型文件到指定目录。
     *
     * @param modelDir 目标目录
     * @param useProxy 是否使用代理（国外镜像站需要）
     * @param onProgress 进度回调 (fileName, progress)
     */
    suspend fun downloadModels(
        modelDir: File,
        useProxy: Boolean = true,
        onProgress: ((String, Float) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            modelDir.mkdirs()

            val files = listOf(
                "decoder.onnx" to "$DEFAULT_BASE_URL/decoder.onnx",
                "encoder.onnx" to "$DEFAULT_BASE_URL/encoder.onnx",
            )

            files.forEach { (fileName, url) ->
                val targetFile = File(modelDir, fileName)
                if (targetFile.exists()) {
                    Log.i(TAG, "downloadModels: $fileName already exists, skipping")
                    onProgress?.invoke(fileName, 1.0f)
                    return@forEach
                }

                Log.i(TAG, "downloadModels: downloading $fileName from $url")
                downloadFile(url, targetFile, useProxy) { progress ->
                    onProgress?.invoke(fileName, progress)
                }
                Log.i(TAG, "downloadModels: $fileName downloaded (${targetFile.length()} bytes)")
            }
        }
    }

    /**
     * 下载单个文件。
     *
     * @param fileUrl 下载 URL
     * @param targetFile 目标文件
     * @param useProxy 是否使用代理
     * @param onProgress 进度回调 (0.0 ~ 1.0)
     */
    private fun downloadFile(
        fileUrl: String,
        targetFile: File,
        useProxy: Boolean,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val connection = if (useProxy && !proxyHost.isNullOrBlank()) {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            URL(fileUrl).openConnection(proxy) as HttpURLConnection
        } else {
            URL(fileUrl).openConnection() as HttpURLConnection
        }

        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.connect()

        val contentLength = connection.contentLengthLong
        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(targetFile)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytesRead = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead

            if (contentLength > 0) {
                onProgress?.invoke(totalBytesRead.toFloat() / contentLength.toFloat())
            }
        }

        outputStream.close()
        inputStream.close()
        connection.disconnect()
    }
}