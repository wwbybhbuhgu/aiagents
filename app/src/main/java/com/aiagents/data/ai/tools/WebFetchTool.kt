package com.aiagents.data.ai.tools

import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.proxy.ProxyManager
import com.aiagents.data.proxy.ProxyType

private const val WEB_FETCH_MAX_BYTES = 512 * 1024
private const val WEB_FETCH_MAX_CHARS = 64 * 1024

/**
 * 构建 web_fetch 工具。与宿主 Agent 的 web_fetch 工具 1:1 对齐：
 * 抓取 URL 内容并返回文本（自动剥离 HTML 标签），供主/子 Agent 阅读网页。
 * [compress] 用设置项的"压缩"模型把抓取内容加工成简短摘要后再返回。
 * [proxyManager] 代理管理器，用于配置代理
 */
fun buildWebFetchTool(
    proxyManager: ProxyManager? = null,
    compress: suspend (toolParams: JsonObject, content: String) -> String = { _, content -> content },
): Tool {
    val builder = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)

    // SSL 宽容：信任所有证书（用于代理场景）
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCerts, SecureRandom())
    builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
    builder.hostnameVerifier { _, _ -> true }

    // 配置代理
    if (proxyManager != null && proxyManager.needProxy(ProxyType.TOOL)) {
        val proxyAddress = proxyManager.getProxyAddress()
        if (proxyAddress != null) {
            val parts = proxyAddress.split(":")
            if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 7890
                builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
            }
        }
    }

    val client = builder.build()

    return Tool(
        name = "web_fetch",
        description = """
            Fetches the content of a web page (URL) and returns it as plain text.
            HTML tags are stripped automatically. Use this to read documentation,
            articles, or API responses. The page content is limited to $WEB_FETCH_MAX_CHARS characters.
            Use `prompt` to describe what information you want to extract; it guides result formatting.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "The fully-formed URL to fetch (http or https)")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "What information to extract from the page")
                    })
                },
                required = listOf("url"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "url must be a fully-formed http(s) URL"
            }
            val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AIAgents/2.4.5 (Android)")
                .header("Accept", "text/html,text/plain,application/json,*/*")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyBytes = response.body?.bytes()
                    val body = bodyBytes?.decodeBody() ?: ""
                    val fullText = stripHtml(body)

                    // 返回结果，包含状态码和内容
                    val result = buildJsonObject {
                        put("url", url)
                        put("status", response.code)
                        put("statusMessage", response.message)
                        put("contentType", response.header("Content-Type") ?: "")
                        if (prompt != null) put("prompt", prompt)

                        if (response.isSuccessful) {
                            val fallbackText = fullText.take(WEB_FETCH_MAX_CHARS)
                            val processed = runCatching { compress(it.jsonObject, fullText) }
                                .getOrElse { fallbackText }
                            put("text", processed)
                            if (fullText.length > WEB_FETCH_MAX_CHARS) put("truncated", true)
                        } else {
                            // 错误时也返回响应内容（可能包含错误信息）
                            val errorText = if (fullText.isNotEmpty()) {
                                fullText.take(2000)
                            } else {
                                "HTTP ${response.code} ${response.message}"
                            }
                            put("text", errorText)
                            put("error", "HTTP ${response.code} ${response.message}")
                        }
                    }

                    listOf(UIMessagePart.Text(result.toString()))
                }
            } catch (e: IOException) {
                // 网络错误时返回错误信息而不是抛出异常
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("url", url)
                            put("error", "Failed to fetch: ${e.message}")
                            put("text", "Failed to fetch $url: ${e.message}")
                        }.toString()
                    )
                )
            }
        },
    )
}

private fun ByteArray.decodeBody(): String {
    // 优先按 UTF-8 解码；失败时按平台默认编码兜底
    val utf8 = try {
        toString(Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
    return utf8 ?: String(this)
}

/** 粗略的 HTML -> 纯文本转换：剥标签、解实体、压空行 */
private fun stripHtml(html: String): String {
    var text = html
    // 去掉 script/style 块
    text = text.replace(Regex("(?is)<(script|style|head|noscript)[^>]*>.*?</\\1\\s*>"), " ")
    // 去掉注释
    text = text.replace(Regex("(?s)<!--.*?-->"), " ")
    // 标签 -> 换行
    text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
    text = text.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article)>"), "\n")
    text = text.replace(Regex("<[^>]+>"), "")
    // 解实体
    text = text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    // 压缩空白
    text = text.replace(Regex("[ \\t]+"), " ")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    return text.trim()
}
