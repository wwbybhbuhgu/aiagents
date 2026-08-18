package com.aiagents.ui.components.browser

import android.graphics.Bitmap
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aiagents.data.automation.BrowserSession
import com.aiagents.ui.components.webview.MyWebChromeClient
import com.aiagents.ui.components.webview.MyWebViewClient
import com.aiagents.ui.components.webview.WebViewState

/** 把页面对话框转发到 BrowserSession, 供 browser_handle_dialog 应答。 */
internal class BrowserWebChromeClient(
    state: WebViewState,
    private val session: BrowserSession,
) : MyWebChromeClient(state) {
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        session.queueDialog("alert", message ?: "")
        result?.confirm()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        session.queueDialog("confirm", message ?: "")
        session.attachConfirmResult(result)
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?,
    ): Boolean {
        session.queueDialog("prompt", message ?: "", defaultValue)
        session.attachPromptResult(result)
        return true
    }

    /**
     * 页面按钮点击 target="_blank" / window.open() 时, WebView 会尝试开新窗口(标签页)。
     * 这里拦截并改为在当前浏览器 WebView 中直接跳转, 而不是另开新窗口。
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        val hostView = view ?: return true
        val tempView = WebView(hostView.context)
        tempView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                newView: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // 改为在当前浏览器中跳转
                hostView.loadUrl(url)
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(newView: WebView?, url: String?): Boolean {
                url?.let { hostView.loadUrl(it) }
                return true
            }
        }
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = tempView
        resultMsg.sendToTarget()
        return true
    }
}

/** 记录页面网络请求到 BrowserSession。 */
internal class BrowserWebViewClient(
    state: WebViewState,
    private val session: BrowserSession,
) : MyWebViewClient(state) {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        session.recordNetworkRequest(url ?: "", "navigation")
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
        session.recordNetworkRequest(url ?: "")
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        session.recordNetworkRequest(request.url.toString())
        return super.shouldInterceptRequest(view, request)
    }
}
