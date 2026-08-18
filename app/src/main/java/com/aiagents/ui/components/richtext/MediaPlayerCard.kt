package com.aiagents.ui.components.richtext

import android.webkit.WebSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aiagents.ui.components.webview.WEB_VIEW_BASE_URL
import com.aiagents.ui.components.webview.WebView
import com.aiagents.ui.components.webview.rememberWebViewState

/**
 * 内置媒体播放器卡片
 *
 * 用嵌入式浏览器容器(WebView)播放视频/音频, 支持 file:// 与 content:// 来源,
 * 无需跳转外部播放器。HTML5 `<video>` / `<audio>` 控件直接内嵌在消息里。
 */
@Composable
fun MediaPlayerCard(
    url: String,
    mimeType: String? = null,
    modifier: Modifier = Modifier,
) {
    val isVideo = mimeType?.startsWith("video/") == true ||
        (mimeType == null && url.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS)
    val html = remember(url, isVideo) {
        buildMediaHtml(url, isVideo)
    }

    val settings: WebSettings.() -> Unit = {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        settings = settings,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .height(if (isVideo) 240.dp else 64.dp),
        )
    }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "avi", "mov", "3gp", "flv", "ts", "m4v")

private fun buildMediaHtml(url: String, isVideo: Boolean): String {
    val escaped = url
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return if (isVideo) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
                video{width:100%;height:100%;object-fit:contain;background:#000;display:block}
            </style>
        </head>
        <body>
            <video src="$escaped" controls autoplay playsinline></video>
        </body>
        </html>
        """.trimIndent()
    } else {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                html,body{margin:0;padding:0;height:100%;background:#0b0e14;overflow:hidden}
                audio{width:100%;height:100%;display:block}
            </style>
        </head>
        <body>
            <audio src="$escaped" controls autoplay></audio>
        </body>
        </html>
        """.trimIndent()
    }
}
