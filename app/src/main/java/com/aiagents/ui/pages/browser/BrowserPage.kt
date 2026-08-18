package com.aiagents.ui.pages.browser

import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aiagents.data.automation.BrowserSession
import com.aiagents.ui.components.browser.BrowserWebChromeClient
import com.aiagents.ui.components.browser.BrowserWebViewClient
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.components.webview.WebView
import com.aiagents.utils.openUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh01

/** AI 内置浏览器自动化界面(应用内全屏版): WebView 由 BrowserSession 持有, 供 browser_* 工具控制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPage() {
    val context = LocalContext.current
    val session = BrowserSession
    val state = session.state
    var urlInput by remember { mutableStateOf("") }

    LaunchedEffect(state.currentUrl) {
        state.currentUrl?.takeIf { it.isNotBlank() }?.let { urlInput = it }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        placeholder = { Text(state.currentUrl ?: "https://") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { session.loadUrl(urlInput) }),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { session.goBack() }, enabled = state.canGoBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "Back")
                    }
                    IconButton(onClick = { session.goForward() }, enabled = state.canGoForward) {
                        Icon(HugeIcons.ArrowRight01, contentDescription = "Forward")
                    }
                    IconButton(onClick = { session.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Reload")
                    }
                    IconButton(onClick = { context.openUrl(state.currentUrl ?: urlInput) }) {
                        Icon(HugeIcons.Earth, contentDescription = "Open in system browser")
                    }
                },
            )
        },
    ) { padding ->
        val vw = session.viewportWidth
        val vh = session.viewportHeight
        if (vw > 0 && vh > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
            ) {
                WebView(
                    state = state,
                    modifier = Modifier.size(vw.dp, vh.dp),
                    onCreated = attachAndConfigure,
                    onUpdated = attachAndConfigure,
                )
            }
        } else {
            WebView(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreated = attachAndConfigure,
                onUpdated = attachAndConfigure,
            )
        }
    }
}
