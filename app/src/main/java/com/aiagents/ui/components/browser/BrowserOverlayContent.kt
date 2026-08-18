package com.aiagents.ui.components.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiagents.data.automation.BrowserSession
import com.aiagents.data.automation.FloatingWindowController
import com.aiagents.ui.components.webview.WebView
import com.aiagents.utils.openUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Maximize01
import me.rerere.hugeicons.stroke.Minimize01
import me.rerere.hugeicons.stroke.Refresh01

/** AI 内置浏览器悬浮窗内容: 小型可拖动窗口, 不遮主界面, 可悬浮在其他应用之上。 */
@Composable
fun BrowserOverlayContent(
    context: Context,
    dragState: BrowserOverlayDragState,
    onClose: () -> Unit,
) {
    val session = BrowserSession
    val state = session.state
    val window = FloatingWindowController.get(FloatingWindowController.BROWSER)
    val minimized = window?.isMinimized ?: false

    val webChromeClient = remember { BrowserWebChromeClient(state, session) }
    val webViewClient = remember { BrowserWebViewClient(state, session) }
    val attachAndConfigure = { wv: WebView ->
        wv.webChromeClient = webChromeClient
        wv.webViewClient = webViewClient
        session.attach(wv)
    }

    DisposableEffect(Unit) {
        onDispose { session.clear() }
    }

    if (minimized) {
        // 最小化: 只显示一个小圆角药丸, 可拖动, 点击恢复
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 10.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragState.dragBy(dragAmount.x.toInt(), dragAmount.y.toInt())
                        }
                    },
            ) {
                IconButton(
                    onClick = { window?.setMinimizedState(!minimized) },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        HugeIcons.Maximize01,
                        contentDescription = "Restore",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 2.dp, end = 10.dp),
                )
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        modifier = Modifier.width(320.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题栏(可拖动)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragState.dragBy(dragAmount.x.toInt(), dragAmount.y.toInt())
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = state.pageTitle ?: state.currentUrl ?: "AI Browser",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { session.goBack() }, enabled = state.canGoBack, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { session.goForward() }, enabled = state.canGoForward, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.ArrowRight01, contentDescription = "Forward", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { session.reload() }, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.Refresh01, contentDescription = "Reload", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { window?.setMinimizedState(!minimized) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (minimized) HugeIcons.Maximize01 else HugeIcons.Minimize01,
                        contentDescription = "Minimize",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { onClose() }, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.Cancel01, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                }
            }

            if (!minimized) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    WebView(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        onCreated = attachAndConfigure,
                        onUpdated = attachAndConfigure,
                    )
                }

                // 底部操作行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = state.currentUrl ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            state.currentUrl?.let { context.openUrl(it) }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(HugeIcons.Earth, contentDescription = "Open in system browser", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
