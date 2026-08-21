package com.aiagents.ui.components.richtext

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.aiagents.ai.provider.Model
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.ui.components.webview.WEB_VIEW_BASE_URL
import com.aiagents.ui.components.webview.WebView
import com.aiagents.ui.components.webview.rememberWebViewState

/**
 * 交互式 HTML 卡片
 *
 * 用 WebView 渲染 AI 生成的 HTML(或外部 URL), 并通过 [HtmlCardBridge] 把
 * 工作区访问(文件/目录/shell)、联网搜索与当前会话模型调用能力暴露为 JS 全局对象 `AIAgents`。
 *
 * 嵌套滚动: WebView 外层包裹的 NestedScrollConnection 会检测 WebView 是否还能往该方向滚动。
 * 能滚 → 消费事件, 阻止外层 LazyColumn 抢滚动; 滚到底/顶 → 放行, 让 LazyColumn 接管。
 */
@Composable
fun HtmlCardView(
    part: UIMessagePart.HtmlCard,
    modifier: Modifier = Modifier,
    model: Model? = null,
) {
    val context = LocalContext.current

    val workspaceId = part.metadata?.let { meta ->
        meta["workspaceId"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
    } ?: ""

    val html = remember(part.html) {
        wrapHtmlCard(part.html)
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember(workspaceId, model) {
        HtmlCardBridge(
            context = context,
            workspaceId = workspaceId.takeIf { it.isNotBlank() },
            cwd = workspaceId.takeIf { it.isNotBlank() }?.let { "/workspace" },
            modelRef = { model },
            webViewRef = { webViewInstance },
        )
    }

    val settings: WebSettings.() -> Unit = {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowContentAccess = true
        allowFileAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    val webViewState = if (part.url.isNotBlank() && html.isBlank()) {
        rememberWebViewState(
            url = part.url,
            interfaces = mapOf("AIAgents" to bridge),
            settings = settings,
        )
    } else {
        rememberWebViewState(
            data = html,
            baseUrl = WEB_VIEW_BASE_URL,
            mimeType = "text/html",
            encoding = "UTF-8",
            interfaces = mapOf("AIAgents" to bridge),
            settings = settings,
        )
    }

    // 嵌套滚动连接: 检测 WebView 是否还能往该方向滚动
    val nestedScrollConnection = remember(webViewInstance) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val wv = webViewInstance ?: return androidx.compose.ui.geometry.Offset.Zero
                val dy = available.y
                return when {
                    // 向下滚 (手指上滑) 且 WebView 底部还有内容 → 消费, 阻止 LazyColumn
                    dy < 0 && wv.canScrollVertically(1) -> available.copy(x = 0f)
                    // 向上滚 (手指下滑) 且 WebView 顶部还有内容 → 消费, 阻止 LazyColumn
                    dy > 0 && wv.canScrollVertically(-1) -> available.copy(x = 0f)
                    // WebView 滚到头了 → 不消费, 放行给 LazyColumn
                    else -> androidx.compose.ui.geometry.Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val wv = webViewInstance ?: return Velocity.Zero
                return when {
                    available.y < 0 && wv.canScrollVertically(1) -> available
                    available.y > 0 && wv.canScrollVertically(-1) -> available
                    else -> Velocity.Zero
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .height(part.height.coerceIn(160, 960).dp)
                .nestedScroll(nestedScrollConnection),
            onCreated = { webViewInstance = it },
        )
    }
}

/** 把 AI 生成的 HTML 片段包装成完整页面(已是完整文档时原样返回) */
private fun wrapHtmlCard(fragment: String): String {
    val trimmed = fragment.trim()
    val full = if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
        trimmed.startsWith("<html", ignoreCase = true)
    ) {
        trimmed
    } else {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
        </head>
        <body style="margin:0; padding:8px; font-family:sans-serif;">
            $trimmed
        </body>
        </html>
        """.trimIndent()
    }
    // 预处理: 卡片内不支持"新标签页", 把所有 target="_blank" 链接/新窗口请求改为当前 WebView 内跳转
    return full.replace("</head>", newTabPreprocessScript() + "\n</head>")
}

/** 注入 JS: 拦截新标签页/新窗口请求 + 自动解析 AIAgents JSON 返回值 */
private fun newTabPreprocessScript(): String = """
<script>
(function() {
  // ---- 1) 新标签页/新窗口 -> 当前卡片内跳转 ----
  function hijackNewTab() {
    document.addEventListener('click', function(e) {
      var a = e.target.closest ? e.target.closest('a[target="_blank"]') : null;
      if (a) {
        e.preventDefault();
        e.stopPropagation();
        var href = a.getAttribute('href');
        if (href && href !== '#' && href !== '') {
          if (/^(https?:|content:|file:)/i.test(href)) {
            window.location.href = href;
          } else {
            var base = new URL(window.location.href);
            window.location.href = new URL(href, base).href;
          }
        }
      }
    }, true);
    var origOpen = window.open;
    window.open = function(url, name, features) {
      if (url) {
        if (/^(https?:|content:|file:)/i.test(url)) {
          window.location.href = url;
        } else {
          var base = new URL(window.location.href);
          window.location.href = new URL(url, base).href;
        }
      }
      return null;
    };
    document.querySelectorAll('a[target="_blank"]').forEach(function(a) {
      a.removeAttribute('target');
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', hijackNewTab);
  } else {
    hijackNewTab();
  }

  // ---- 2) 包装 AIAgents: 同步方法自动 JSON.parse, 返回原生对象 ----
  (function(){
    var raw = window.AIAgents;
    if (!raw) return;
    var SYNC = ['getInfo','listFiles','readText','readBase64','writeText','shell','search','generate'];
    function wrap(fn){
      return function(){
        var res;
        try { res = fn.apply(raw, arguments); }
        catch(e) { return { ok:false, error: String(e && e.message || e) }; }
        if (typeof res === 'string') {
          try { return JSON.parse(res); }
          catch(e2) { return res; }
        }
        return res;
      };
    }
    var w = {};
    SYNC.forEach(function(m){
      if (typeof raw[m] === 'function') w[m] = wrap(raw[m]);
    });
    if (typeof raw.generateStream === 'function') w.generateStream = raw.generateStream.bind(raw);
    if (typeof raw.generate === 'function') w.generate = wrap(raw.generate);
    window.AIAgents = w;
  })();
})();
</script>
""".trimIndent()
