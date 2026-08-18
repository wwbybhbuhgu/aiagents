package com.aiagents.ui.components.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * 提供带 FLAG_ACTIVITY_NEW_TASK 的 UriHandler。
 *
 * 悬浮窗/Service 的 ComposeView 里, Compose 默认的 [UriHandler] 用 Service Context 直接
 * startActivity, 会抛 `Calling startActivity() from outside of an Activity context`。
 * 该包装器统一加 NEW_TASK, 并兜底捕获找不到处理器的异常。
 */
@Composable
fun ProvideSafeUriHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val handler = remember(context) { SafeUriHandler(context) }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}

private class SafeUriHandler(
    private val context: Context,
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            if (context is android.app.Activity) {
                context.startActivity(intent)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            Log.w("SafeUriHandler", "No activity to handle $uri", e)
        } catch (e: Exception) {
            Log.w("SafeUriHandler", "Failed to open $uri", e)
        }
    }
}
