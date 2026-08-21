package com.aiagents.data.ai.tools.local

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台 Node.js 进程管理 (WebView-based, 后续替换为 libnode.so 原生)
 *
 * 每个后台 JS 执行分配一个独立 WebView, AI 通过 ID 读取输出或终止。
 */
class NodeProcessManager(private val context: Context) {

    data class NodeProcess(
        val id: String,
        val code: String,
        val webView: WebView,
        val output: StringBuilder = StringBuilder(),
        var isRunning: Boolean = true,
        var result: String? = null,
    )

    private val processes = ConcurrentHashMap<String, NodeProcess>()

    /** 启动一个后台 JS 执行 */
    fun start(code: String, workspaceDir: File): String {
        val id = UUID.randomUUID().toString().take(8)
        val bridge = NodeJsBridge(workspaceDir)
        val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = true
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
        }
        webView.addJavascriptInterface(bridge, "nodeBridge")

        val proc = NodeProcess(id = id, code = code, webView = webView)
        processes[id] = proc

        // 加载空白页 → 注入模块 → 执行代码
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectModules(view!!, proc)
            }
        }
        webView.loadDataWithBaseURL(
            "https://sandbox.local",
            "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body></body></html>",
            "text/html", "UTF-8", null
        )

        return id
    }

    /** 读取输出 */
    fun readOutput(id: String): Map<String, Any>? {
        val proc = processes[id] ?: return null
        return mapOf(
            "id" to proc.id,
            "running" to proc.isRunning,
            "output" to proc.output.toString().trim(),
            "result" to (proc.result ?: ""),
        )
    }

    /** 终止执行 */
    fun kill(id: String): Boolean {
        val proc = processes[id] ?: return false
        proc.isRunning = false
        try { proc.webView.destroy() } catch (_: Exception) {}
        processes.remove(id)
        return true
    }

    /** 列出所有进程 */
    fun list(): List<Map<String, Any>> {
        return processes.values.map { p ->
            mapOf("id" to p.id, "running" to p.isRunning, "code" to p.code.take(100))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectModules(webView: WebView, proc: NodeProcess) {
        // 注入 Node.js 模块 + console 捕获
        webView.evaluateJavascript(
            """
            (function() {
                var _out = [];
                var _origLog = console.log;
                console.log = function() {
                    var args = Array.prototype.slice.call(arguments).map(function(a) {
                        return typeof a === 'object' ? JSON.stringify(a) : String(a);
                    });
                    _out.push(args.join(' '));
                    _origLog.apply(console, arguments);
                };
                console.warn = function() {
                    var args = Array.prototype.slice.call(arguments).map(function(a) {
                        return typeof a === 'object' ? JSON.stringify(a) : String(a);
                    });
                    _out.push('[WARN] ' + args.join(' '));
                    _origLog.apply(console, arguments);
                };
                console.error = function() {
                    var args = Array.prototype.slice.call(arguments).map(function(a) {
                        return typeof a === 'object' ? JSON.stringify(a) : String(a);
                    });
                    _out.push('[ERROR] ' + args.join(' '));
                    _origLog.apply(console, arguments);
                };
                window.__getOutput = function() { return _out; };

                // fs/path/os/child_process/process 注入 (同 eval_javascript)
                var fs = {
                    readFileSync: function(p) { return nodeBridge.readFileSync(p); },
                    writeFileSync: function(p, c) { return nodeBridge.writeFileSync(p, c); },
                    appendFileSync: function(p, c) { return nodeBridge.appendFileSync(p, c); },
                    existsSync: function(p) { return nodeBridge.existsSync(p); },
                    mkdirSync: function(p, o) { return nodeBridge.mkdirSync(p, !!(o && o.recursive)); },
                    rmSync: function(p, o) { return nodeBridge.rmSync(p, !!(o && o.recursive)); },
                    cpSync: function(s, d) { return nodeBridge.cpSync(s, d); },
                    mvSync: function(s, d) { return nodeBridge.mvSync(s, d); },
                    readdirSync: function(p) {
                        var raw = nodeBridge.readdirSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    statSync: function(p) {
                        var raw = nodeBridge.statSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    readJsonSync: function(p) {
                        var raw = nodeBridge.readJsonSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    writeJsonSync: function(p, o) { return nodeBridge.writeJsonSync(p, typeof o === 'string' ? o : JSON.stringify(o, null, 2)); },
                    unlinkSync: function(p) { return nodeBridge.unlinkSync(p); },
                    renameSync: function(o, n) { return nodeBridge.renameSync(o, n); },
                    chmodSync: function(p, m) { return nodeBridge.chmodSync(p, m); },
                    promises: {
                        readFile: function(p) { return Promise.resolve(fs.readFileSync(p)); },
                        writeFile: function(p, c) { return Promise.resolve(fs.writeFileSync(p, c)); },
                        readdir: function(p) { return Promise.resolve(fs.readdirSync(p)); },
                        stat: function(p) { return Promise.resolve(fs.statSync(p)); }
                    }
                };
                var child_process = {
                    execSync: function(cmd) {
                        var raw = nodeBridge.execSync(cmd);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    }
                };
                var path = {
                    join: function() { return nodeBridge.pathJoin.apply(null, arguments); },
                    resolve: function() { return nodeBridge.pathResolve.apply(null, arguments); },
                    basename: function(p) { return nodeBridge.pathBasename(p); },
                    dirname: function(p) { return nodeBridge.pathDirname(p); },
                    extname: function(p) { return nodeBridge.pathExtname(p); },
                    sep: '/'
                };
                var os = {
                    hostname: function() { return nodeBridge.osHostname(); },
                    platform: function() { return nodeBridge.osPlatform(); },
                    arch: function() { return nodeBridge.osArch(); },
                    tmpdir: function() { return nodeBridge.osTmpdir(); },
                    EOL: '\n'
                };
                var process = {
                    cwd: function() { return nodeBridge.processCwd(); },
                    env: (function() { try { return JSON.parse(nodeBridge.processEnv()); } catch(e) { return {}; } })(),
                    argv: ['node'], version: 'v20.0.0', platform: 'android', arch: os.arch()
                };
                function require(m) {
                    var map = {'fs':fs,'path':path,'os':os,'child_process':child_process,'process':process};
                    return map[m] || {};
                }
                var module = { exports: {} };
            })();
            """, null
        )

        // 执行用户代码
        val wrappedCode = """
            (async function() {
                try {
                    var __r = (function() { ${proc.code} })();
                    if (__r && typeof __r.then === 'function') __r = await __r;
                    return typeof __r === 'object' ? JSON.stringify(__r) : String(__r);
                } catch(e) { return JSON.stringify({error: e.message, stack: e.stack}); }
            })()
        """.trimIndent()
        webView.evaluateJavascript(wrappedCode) { raw ->
            val value = raw?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
            proc.result = value
            proc.isRunning = false

            // 收集 console 输出
            webView.evaluateJavascript("JSON.stringify(window.__getLogs ? window.__getLogs() : window.__getOutput ? window.__getOutput() : [])") { logsRaw ->
                try {
                    val clean = logsRaw?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?.replace("\\n", "\n")
                    if (clean != null) {
                        val arr = org.json.JSONArray(clean)
                        proc.output.append(arr.toString())
                    }
                } catch (_: Exception) {}
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  后台 Node.js 工具
// ──────────────────────────────────────────────────────────────────────

internal fun buildBackgroundNodeTools(
    nodeManager: NodeProcessManager,
    workspaceRepository: WorkspaceRepository,
    context: Context,
): List<Tool> = listOf(

    // ── node_bg: 后台执行 JS ──
    Tool(
        name = "node_bg",
        description = "Execute JavaScript in background with Node.js-like APIs (fs/path/child_process/os). Returns a process ID. Use node_output to read result, node_kill to terminate.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "JavaScript code to execute in background")
                    })
                },
                required = listOf("code")
            )
        },
        execute = { args ->
            val code = args.jsonObject["code"]?.jsonPrimitive?.contentOrNull
            if (code.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"code is required"}"""))
            } else {
                val wsDir = runCatching {
                    val ws = kotlinx.coroutines.runBlocking {
                        workspaceRepository.getDefaultWorkspace()
                    }
                    if (ws != null) File(context.filesDir, "workspaces/${ws.id}/files").also { it.mkdirs() }
                    else File(context.filesDir, "workspace").also { it.mkdirs() }
                }.getOrDefault(File(context.filesDir, "workspace").also { it.mkdirs() })

                val id = nodeManager.start(code, wsDir)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("id", id)
                    put("message", "Background Node.js started. Use node_output('$id') to read result.")
                }.toString()))
            }
        }
    ),

    // ── node_output: 读取输出 ──
    Tool(
        name = "node_output",
        description = "Read output/result from a background Node.js process.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Process ID from node_bg")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (id.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"id is required"}"""))
            } else {
                val output = nodeManager.readOutput(id)
                if (output == null) {
                    listOf(UIMessagePart.Text("""{"error":"Process $id not found"}"""))
                } else {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("running", JsonPrimitive(output["running"] as Boolean))
                        put("output", JsonPrimitive(output["output"] as String))
                        put("result", JsonPrimitive(output["result"] as String))
                    }.toString()))
                }
            }
        }
    ),

    // ── node_kill: 终止 ──
    Tool(
        name = "node_kill",
        description = "Kill a background Node.js process.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Process ID from node_bg")
                    })
                },
                required = listOf("id")
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (id.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"id is required"}"""))
            } else {
                val ok = nodeManager.kill(id)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", JsonPrimitive(ok))
                    put("message", if (ok) "Process $id killed" else "Process $id not found")
                }.toString()))
            }
        }
    ),

    // ── node_list: 列出所有进程 ──
    Tool(
        name = "node_list",
        description = "List all background Node.js processes.",
        parameters = { null },
        execute = {
            val list = nodeManager.list()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("processes", JsonPrimitive(list.toString()))
            }.toString()))
        }
    ),
)
