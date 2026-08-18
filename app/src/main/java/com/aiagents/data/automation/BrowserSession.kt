package com.aiagents.data.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiagents.ui.components.webview.WebContent
import com.aiagents.ui.components.webview.WebViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import kotlin.coroutines.resume

/** 浏览器弹出的 JS 对话框信息。 */
data class BrowserDialogInfo(
    val type: String,           // alert / confirm / prompt
    val message: String,
    val defaultInput: String? = null,
)

/** 页面网络请求记录。 */
data class BrowserNetworkRequest(
    val url: String,
    val kind: String,           // navigation / resource
)

/**
 * AI 内置浏览器自动化会话(单例)。
 * [BrowserPage] 把实际 WebView attach 到这里, 浏览器工具通过它控制内置浏览器。
 * 所有 WebView 操作必须发生在主线程。
 */
object BrowserSession {

    /** 驱动 Compose WebView 的状态(URL/前进后退/标题/控制台等)。 */
    val state = WebViewState(initialContent = WebContent.Url("https://www.google.com"))

    @Volatile
    private var webView: WebView? = null

    /** 自定义 User-Agent; null 表示使用默认。 */
    @Volatile
    var userAgent: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 保证 WebView 操作在主线程执行(工具在后台协程里调用)。 */
    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    var attached by mutableStateOf(false)
        private set

    /** 视口尺寸(px)。-1 表示跟随容器填满。 */
    var viewportWidth by mutableIntStateOf(-1)
        private set
    var viewportHeight by mutableIntStateOf(-1)
        private set

    private val _networkRequests = Collections.synchronizedList(mutableListOf<BrowserNetworkRequest>())
    private val networkRequestsLock = Any()

    @Volatile
    private var pendingAlert: BrowserDialogInfo? = null
    @Volatile
    private var pendingConfirm: Pair<BrowserDialogInfo, JsResult?>? = null
    @Volatile
    private var pendingPrompt: Pair<BrowserDialogInfo, JsPromptResult?>? = null

    fun attach(view: WebView) {
        webView = view
        attached = true
        view.settings.userAgentString = userAgent
    }

    fun detach(view: WebView) {
        if (webView === view) {
            webView = null
            attached = false
        }
    }

    /** 清空会话引用(浏览器页面销毁时调用)。 */
    fun clear() {
        webView = null
        attached = false
    }

    /** 设置自定义 User-Agent(空字符串重置为默认), 应用到当前 WebView。 */
    fun updateUserAgent(ua: String?) {
        userAgent = ua?.takeIf { it.isNotBlank() }
        postMain {
            webView?.settings?.userAgentString = userAgent
        }
    }

    fun loadUrl(raw: String) {
        val url = raw.trim().let {
            when {
                it.startsWith("http://") || it.startsWith("https://") -> it
                else -> "https://$it"
            }
        }
        state.loadUrl(url)
    }

    fun goBack() = postMain { state.goBack() }

    fun goForward() = postMain { state.goForward() }

    fun reload() = postMain { state.reload() }

    fun stop() = postMain { state.stopLoading() }

    fun currentUrl(): String = state.currentUrl ?: ""

    /** 记录一次页面网络请求(由 BrowserPage 的 WebViewClient 调用)。 */
    fun recordNetworkRequest(url: String, kind: String = "resource") {
        if (url.isBlank()) return
        synchronized(networkRequestsLock) {
            _networkRequests.add(BrowserNetworkRequest(url.take(1024), kind))
            while (_networkRequests.size > 200) _networkRequests.removeAt(0)
        }
    }

    /** 最近 N 条网络请求。 */
    fun recentNetworkRequests(limit: Int = 20): List<BrowserNetworkRequest> =
        synchronized(networkRequestsLock) {
            _networkRequests.takeLast(limit).toList()
        }

    /** 队列一个 JS 对话框。 */
    fun queueDialog(type: String, message: String, defaultInput: String? = null) {
        val info = BrowserDialogInfo(type, message, defaultInput)
        when (type) {
            "alert" -> pendingAlert = info
            "confirm" -> pendingConfirm = info to null
            "prompt" -> pendingPrompt = info to null
        }
    }

    /** 关联确认框的 JsResult, 由浏览器页 WebChromeClient 调用。 */
    fun attachConfirmResult(result: JsResult?) {
        pendingConfirm = pendingConfirm?.let { it.first to result }
    }

    /** 关联输入框的 JsPromptResult, 由浏览器页 WebChromeClient 调用。 */
    fun attachPromptResult(result: JsPromptResult?) {
        pendingPrompt = pendingPrompt?.let { it.first to result }
    }

    /** 当前是否有待处理的 JS 对话框。 */
    fun currentDialog(): BrowserDialogInfo? =
        pendingAlert ?: pendingConfirm?.first ?: pendingPrompt?.first

    /** 应答待处理的 JS 对话框。 */
    fun answerDialog(accept: Boolean, input: String? = null): String {
        pendingAlert?.let {
            pendingAlert = null
            return if (accept) "alert dismissed" else "alert ignored"
        }
        pendingConfirm?.let { (info, result) ->
            pendingConfirm = null
            if (result != null) {
                if (accept) result.confirm() else result.cancel()
            }
            return "confirm ${if (accept) "accepted" else "cancelled"}"
        }
        pendingPrompt?.let { (info, result) ->
            pendingPrompt = null
            if (result != null) {
                if (accept) result.confirm(input ?: info.defaultInput ?: "") else result.cancel()
            }
            return "prompt ${if (accept) "accepted" else "cancelled"}"
        }
        return "no pending dialog"
    }

    /** 视口尺寸(px)。-1 表示跟随容器填满。 */
    fun setViewport(widthPx: Int, heightPx: Int) {
        viewportWidth = widthPx.coerceIn(320, 4096)
        viewportHeight = heightPx.coerceIn(320, 8192)
        reload()
    }

    fun resetViewport() {
        viewportWidth = -1
        viewportHeight = -1
        reload()
    }

    /** 执行页面 JS 并返回结果字符串(通常是 JSON 序列化的值)。 */
    suspend fun evaluateJs(script: String): Result<String> = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext Result.failure(IllegalStateException("Browser is not attached"))
        if (view.url.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("No page loaded in browser"))
        }
        suspendCancellableCoroutine { cont ->
            try {
                view.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result ?: "")
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
                e.printStackTrace()
            }
        }.let { Result.success(it) }
    }

    /** 把当前 WebView 视口渲染为 PNG, 保存到 [file]。 */
    suspend fun screenshot(file: File): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return@withContext false
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return@withContext false
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        view.draw(canvas)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
        }
        bitmap.recycle()
        ok
    }

    /** 在 [x],[y](WebView 视图内坐标) 上模拟一次真实触摸点击(派发到主线程)。 */
    fun click(x: Int, y: Int): Boolean {
        postMain { dispatchClick(x, y) }
        return true
    }

    private fun dispatchClick(x: Int, y: Int) {
        val view = webView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** 模拟一次触摸拖拽(从 x1,y1 到 x2,y2, 派发到主线程)。 */
    fun drag(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        postMain { dispatchDrag(x1, y1, x2, y2) }
        return true
    }

    private fun dispatchDrag(x1: Int, y1: Int, x2: Int, y2: Int) {
        val view = webView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0)
        val move = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_MOVE, x2.toFloat(), y2.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 160, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(move)
        view.dispatchTouchEvent(up)
        down.recycle()
        move.recycle()
        up.recycle()
    }

    /** 通过 JS 滚动页面(派发到主线程)。 */
    fun scroll(dx: Int, dy: Int) {
        postMain {
            val view = webView ?: return@postMain
            if (view.url.isNullOrBlank()) return@postMain
            view.evaluateJavascript("window.scrollBy($dx, $dy); 'ok'", null)
        }
    }

    /** 通过 JS 滚动到绝对位置(派发到主线程)。 */
    fun scrollTo(x: Int, y: Int) {
        postMain {
            val view = webView ?: return@postMain
            if (view.url.isNullOrBlank()) return@postMain
            view.evaluateJavascript("window.scrollTo($x, $y); 'ok'", null)
        }
    }

    /** 通过 JS 把文本安全地嵌入脚本。 */
    fun jsStringLiteral(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}
