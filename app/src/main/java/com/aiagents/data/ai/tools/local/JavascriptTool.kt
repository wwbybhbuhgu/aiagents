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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.resume

// ──────────────────────────────────────────────────────────────────────
//  Node.js-like Workspace Bridge (fs / child_process / path / os)
// ──────────────────────────────────────────────────────────────────────

/**
 * Node.js 风格的工作区操作桥接, 注入到 WebView 的 JS 上下文中。
 *
 * 模块:
 * - `fs`        — 文件系统 (readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, rmSync, cpSync, mvSync, existsSync, appendFileSync, readBase64Sync, writeBase64Sync, unlinkSync, renameSync, readlinkSync, symlinkSync, lstatSync, readJsonSync, writeJsonSync)
 * - `child_process` — 子进程 (execSync, exec)
 * - `path`      — 路径工具 (join, resolve, basename, dirname, extname, sep, normalize)
 * - `os`        — 系统信息 (hostname, platform, arch, type, release, tmpdir, EOL)
 * - `process`   — 进程信息 (cwd, env, exitCode)
 */
@SuppressLint("SetJavaScriptEnabled")
internal class NodeJsBridge(
    private val workspaceDir: File,
) {

    // ── fs 模块 ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun readFileSync(path: String): String = try {
        resolveFile(path).readText()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun readBase64Sync(path: String): String = try {
        android.util.Base64.encodeToString(
            resolveFile(path).readBytes(),
            android.util.Base64.NO_WRAP
        )
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun writeFileSync(path: String, content: String): String = try {
        val f = resolveFile(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun writeBase64Sync(path: String, base64: String): String = try {
        val f = resolveFile(path)
        f.parentFile?.mkdirs()
        f.writeBytes(android.util.Base64.decode(base64, android.util.Base64.NO_WRAP))
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun appendFileSync(path: String, content: String): String = try {
        val f = resolveFile(path)
        f.parentFile?.mkdirs()
        f.appendText(content)
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun existsSync(path: String): Boolean = resolveFile(path).exists()

    @JavascriptInterface
    fun mkdirSync(path: String, recursive: Boolean): String = try {
        val f = resolveFile(path)
        if (recursive) f.mkdirs() else f.mkdir()
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun rmSync(path: String, recursive: Boolean): String = try {
        val f = resolveFile(path)
        if (recursive) f.deleteRecursively() else f.delete()
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun cpSync(src: String, dest: String): String = try {
        val s = resolveFile(src)
        val d = resolveFile(dest)
        d.parentFile?.mkdirs()
        if (s.isDirectory) s.copyRecursively(d, overwrite = true) else s.copyTo(d, overwrite = true)
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun mvSync(src: String, dest: String): String = try {
        val s = resolveFile(src)
        val d = resolveFile(dest)
        d.parentFile?.mkdirs()
        s.renameTo(d)
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun unlinkSync(path: String): String = try {
        resolveFile(path).delete()
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun renameSync(oldPath: String, newPath: String): String = try {
        resolveFile(oldPath).renameTo(resolveFile(newPath))
        "OK"
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun readdirSync(path: String): String = try {
        val dir = resolveFile(path)
        if (!dir.exists()) return "[]"
        val entries = dir.listFiles()?.map { entry ->
            org.json.JSONObject().apply {
                put("name", entry.name)
                put("isFile", entry.isFile)
                put("isDirectory", entry.isDirectory)
                put("isSymbolicLink", false)
                put("size", entry.length())
                put("mtimeMs", entry.lastModified())
            }
        } ?: emptyList()
        org.json.JSONArray(entries).toString()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun statSync(path: String): String = try {
        val f = resolveFile(path)
        if (!f.exists()) return "ERROR: ENOENT: no such file or directory, stat '${path}'"
        org.json.JSONObject().apply {
            put("size", f.length())
            put("isFile", f.isFile)
            put("isDirectory", f.isDirectory)
            put("isSymbolicLink", false)
            put("mtimeMs", f.lastModified())
            put("atimeMs", f.lastModified())
            put("birthtimeMs", f.lastModified())
            put("mode", if (f.isDirectory) 16877L else 33206L)
        }.toString()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun lstatSync(path: String): String = statSync(path)

    @JavascriptInterface
    fun readlinkSync(path: String): String = "ERROR: not a symlink"

    @JavascriptInterface
    fun symlinkSync(target: String, path: String): String = "ERROR: symlink not supported on Android"

    @JavascriptInterface
    fun chmodSync(path: String, mode: Int): String = "OK"

    @JavascriptInterface
    fun readJsonSync(path: String): String = readFileSync(path)

    @JavascriptInterface
    fun writeJsonSync(path: String, content: String): String = writeFileSync(path, content)

    // ── child_process 模块 ──────────────────────────────────────────

    @JavascriptInterface
    fun execSync(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .directory(workspaceDir)
            .redirectErrorStream(true)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        org.json.JSONObject().apply {
            put("stdout", stdout)
            put("stderr", "")
            put("exitCode", exitCode)
        }.toString()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    @JavascriptInterface
    fun execSyncWithTimeout(command: String, timeoutMs: Int): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .directory(workspaceDir)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor().toLong() < 0
        val stdout = if (finished) "" else process.inputStream.bufferedReader().readText()
        val exitCode = if (finished) -1 else process.exitValue()
        org.json.JSONObject().apply {
            put("stdout", stdout)
            put("stderr", "")
            put("exitCode", exitCode)
            put("timedOut", finished)
        }.toString()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    // ── path 模块 ───────────────────────────────────────────────────

    @JavascriptInterface
    fun pathJoin(vararg parts: String): String = parts.joinToString("/").replace(Regex("/+"), "/")

    @JavascriptInterface
    fun pathResolve(vararg parts: String): String {
        var result = workspaceDir.absolutePath
        for (part in parts) {
            if (part.startsWith("/")) {
                result = part
            } else {
                result = "$result/$part"
            }
        }
        return result.replace(Regex("/+"), "/")
    }

    @JavascriptInterface
    fun pathBasename(path: String): String = File(path).name

    @JavascriptInterface
    fun pathDirname(path: String): String = File(path).parent ?: path

    @JavascriptInterface
    fun pathExtname(path: String): String {
        val name = File(path).name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    @JavascriptInterface
    fun pathNormalize(path: String): String = path.replace(Regex("/+"), "/").trimEnd('/')

    @JavascriptInterface
    fun pathSep(): String = "/"

    // ── os 模块 ─────────────────────────────────────────────────────

    @JavascriptInterface
    fun osHostname(): String = android.os.Build.HOST ?: "unknown"

    @JavascriptInterface
    fun osPlatform(): String = "android"

    @JavascriptInterface
    fun osArch(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    @JavascriptInterface
    fun osType(): String = "Linux"

    @JavascriptInterface
    fun osRelease(): String = System.getProperty("os.version") ?: android.os.Build.VERSION.RELEASE

    @JavascriptInterface
    fun osTmpdir(): String = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp").absolutePath

    @JavascriptInterface
    fun osEol(): String = "\n"

    @JavascriptInterface
    fun osTotalmem(): Long = Runtime.getRuntime().maxMemory()

    @JavascriptInterface
    fun osFreemem(): Long = Runtime.getRuntime().freeMemory()

    // ── process 信息 ────────────────────────────────────────────────

    @JavascriptInterface
    fun processCwd(): String = workspaceDir.absolutePath

    @JavascriptInterface
    fun processEnv(): String = org.json.JSONObject(System.getenv() as Map<*, *>).toString()

    // ── 内部工具 ────────────────────────────────────────────────────

    private fun resolveFile(path: String): File {
        val p = path.trim()
        return if (p.startsWith("/")) {
            // 绝对路径: 限制在 workspaceDir 内
            File(workspaceDir, p.removePrefix("/"))
        } else {
            File(workspaceDir, p)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Tool Definition
// ──────────────────────────────────────────────────────────────────────

/**
 * 使用内置 WebView 执行 JavaScript (Node.js 风格 API + 浏览器能力)。
 *
 * 注入的全局模块:
 * - `fs`             — 文件系统操作
 * - `child_process`  — 子进程 (execSync)
 * - `path`           — 路径工具
 * - `os`             — 系统信息
 * - `process`        — 进程信息 (cwd, env)
 */
internal fun buildJavascriptTool(
    context: Context,
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript with Node.js-like APIs (fs, child_process, path, os, process).
        Network requests: use shell_bg/node_bg with curl, NOT fetch/XHR (WebView cannot access external network).
        Paths relative to workspace root.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
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
            // 获取默认工作区路径
            val wsDir = runCatching {
                val ws = kotlinx.coroutines.runBlocking {
                    workspaceRepository.getDefaultWorkspace()
                }
                if (ws != null) {
                    File(context.filesDir, "workspaces/${ws.id}/files").also { it.mkdirs() }
                } else {
                    File(context.filesDir, "workspace").also { it.mkdirs() }
                }
            }.getOrDefault(File(context.filesDir, "workspace").also { it.mkdirs() })

            executeInWebView(context, code, wsDir)
        }
    }
)

@SuppressLint("SetJavaScriptEnabled")
private suspend fun executeInWebView(
    context: Context,
    code: String,
    workspaceDir: File,
): List<UIMessagePart> {
    return withContext(Dispatchers.Main) {
        val logs = arrayListOf<String>()
        val bridge = NodeJsBridge(workspaceDir)
        val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = true
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    msg?.let {
                        logs.add(it.message())
                    }
                    return true
                }
            }
        }
        webView.addJavascriptInterface(bridge, "nodeBridge")

        try {
            // 加载空白页, 等待就绪
            suspendCancellableCoroutine { cont ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                webView.loadDataWithBaseURL(
                    "https://sandbox.local",
                    "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body></body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
                cont.invokeOnCancellation { webView.destroy() }
            }

            // 注入 Node.js 风格全局模块 + require() + console.log 捕获
            webView.evaluateJavascript(
                """
                // ── console.log 捕获 (结果里返回) ──
                (function() {
                    var _logs = [];
                    var _origLog = console.log;
                    var _origWarn = console.warn;
                    var _origError = console.error;
                    var _origInfo = console.info;
                    console.log = function() {
                        var args = Array.prototype.slice.call(arguments).map(function(a) {
                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                        });
                        _logs.push(args.join(' '));
                        _origLog.apply(console, arguments);
                    };
                    console.warn = function() {
                        var args = Array.prototype.slice.call(arguments).map(function(a) {
                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                        });
                        _logs.push('[WARN] ' + args.join(' '));
                        _origWarn.apply(console, arguments);
                    };
                    console.error = function() {
                        var args = Array.prototype.slice.call(arguments).map(function(a) {
                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                        });
                        _logs.push('[ERROR] ' + args.join(' '));
                        _origError.apply(console, arguments);
                    };
                    console.info = function() {
                        var args = Array.prototype.slice.call(arguments).map(function(a) {
                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                        });
                        _logs.push('[INFO] ' + args.join(' '));
                        _origInfo.apply(console, arguments);
                    };
                    window.__getLogs = function() { return _logs; };
                })();

                // ── fs 模块 ──
                var fs = {
                    readFileSync: function(p, enc) { return nodeBridge.readFileSync(p); },
                    readBase64Sync: function(p) { return nodeBridge.readBase64Sync(p); },
                    writeFileSync: function(p, c, enc) { return nodeBridge.writeFileSync(p, c); },
                    writeBase64Sync: function(p, b) { return nodeBridge.writeBase64Sync(p, b); },
                    appendFileSync: function(p, c, enc) { return nodeBridge.appendFileSync(p, c); },
                    existsSync: function(p) { return nodeBridge.existsSync(p); },
                    mkdirSync: function(p, o) { return nodeBridge.mkdirSync(p, !!(o && o.recursive)); },
                    rmSync: function(p, o) { return nodeBridge.rmSync(p, !!(o && o.recursive)); },
                    cpSync: function(s, d) { return nodeBridge.cpSync(s, d); },
                    mvSync: function(s, d) { return nodeBridge.mvSync(s, d); },
                    unlinkSync: function(p) { return nodeBridge.unlinkSync(p); },
                    renameSync: function(o, n) { return nodeBridge.renameSync(o, n); },
                    readdirSync: function(p) {
                        var raw = nodeBridge.readdirSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    statSync: function(p) {
                        var raw = nodeBridge.statSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    lstatSync: function(p) {
                        var raw = nodeBridge.lstatSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    readlinkSync: function(p) { return nodeBridge.readlinkSync(p); },
                    symlinkSync: function(t, p) { return nodeBridge.symlinkSync(t, p); },
                    chmodSync: function(p, m) { return nodeBridge.chmodSync(p, m); },
                    readJsonSync: function(p) {
                        var raw = nodeBridge.readJsonSync(p);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    writeJsonSync: function(p, o) { return nodeBridge.writeJsonSync(p, typeof o === 'string' ? o : JSON.stringify(o, null, 2)); },
                    constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 },
                    promises: {
                        readFile: function(p, o) { return Promise.resolve(fs.readFileSync(p)); },
                        writeFile: function(p, c, o) { return Promise.resolve(fs.writeFileSync(p, c)); },
                        readdir: function(p) { return Promise.resolve(fs.readdirSync(p)); },
                        stat: function(p) { return Promise.resolve(fs.statSync(p)); },
                        mkdir: function(p, o) { return Promise.resolve(fs.mkdirSync(p, o)); },
                        rm: function(p, o) { return Promise.resolve(fs.rmSync(p, o)); },
                        access: function(p) { return Promise.resolve(fs.existsSync(p)); }
                    }
                };

                // ── child_process 模块 ──
                var child_process = {
                    execSync: function(cmd, opts) {
                        var timeout = (opts && opts.timeout) || 0;
                        var raw = timeout > 0
                            ? nodeBridge.execSyncWithTimeout(cmd, timeout)
                            : nodeBridge.execSync(cmd);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    exec: function(cmd, opts, cb) {
                        if (typeof opts === 'function') { cb = opts; opts = {}; }
                        var result = child_process.execSync(cmd, opts);
                        if (cb) cb(null, result.stdout, result.stderr);
                        return result;
                    }
                };

                // ── path 模块 ──
                var path = {
                    join: function() { return nodeBridge.pathJoin.apply(null, arguments); },
                    resolve: function() { return nodeBridge.pathResolve.apply(null, arguments); },
                    basename: function(p, ext) {
                        var name = nodeBridge.pathBasename(p);
                        if (ext && name.endsWith(ext)) name = name.slice(0, -ext.length);
                        return name;
                    },
                    dirname: function(p) { return nodeBridge.pathDirname(p); },
                    extname: function(p) { return nodeBridge.pathExtname(p); },
                    normalize: function(p) { return nodeBridge.pathNormalize(p); },
                    sep: '/',
                    delimiter: ':',
                    parse: function(p) {
                        var ext = path.extname(p);
                        var base = path.basename(p, ext);
                        return { root: '/', dir: path.dirname(p), base: base, ext: ext, name: base };
                    },
                    isAbsolute: function(p) { return p.startsWith('/'); },
                    relative: function(from, to) {
                        var a = from.split('/').filter(Boolean);
                        var b = to.split('/').filter(Boolean);
                        while (a.length && b.length && a[0] === b[0]) { a.shift(); b.shift(); }
                        return a.map(function(){ return '..'; }).concat(b).join('/');
                    }
                };

                // ── os 模块 ──
                var os = {
                    hostname: function() { return nodeBridge.osHostname(); },
                    platform: function() { return nodeBridge.osPlatform(); },
                    arch: function() { return nodeBridge.osArch(); },
                    type: function() { return nodeBridge.osType(); },
                    release: function() { return nodeBridge.osRelease(); },
                    tmpdir: function() { return nodeBridge.osTmpdir(); },
                    EOL: '\n',
                    totalmem: function() { return nodeBridge.osTotalmem(); },
                    freemem: function() { return nodeBridge.osFreemem(); },
                    cpus: function() { return []; },
                    networkInterfaces: function() { return {}; },
                    homedir: function() { return '/data'; }
                };

                // ── process 信息 ──
                var process = {
                    cwd: function() { return nodeBridge.processCwd(); },
                    env: (function() { try { return JSON.parse(nodeBridge.processEnv()); } catch(e) { return {}; } })(),
                    exitCode: 0,
                    argv: ['node', 'eval_javascript'],
                    version: 'v20.0.0',
                    versions: { node: '20.0.0', v8: '11.0.0' },
                    platform: 'android',
                    arch: os.arch()
                };

                // ── require() 兼容 ──
                var module = { exports: {} };
                function require(modName) {
                    var modules = {
                        'fs': fs, 'fs/promises': fs.promises,
                        'path': path, 'os': os, 'child_process': child_process,
                        'process': process, 'crypto': { randomBytes: function(n) { return Array.from({length:n},function(){return Math.floor(Math.random()*256)}); } },
                        'buffer': { Buffer: { from: function(s) { return s; }, isBuffer: function(){ return false; } } },
                        'util': { format: function() { return Array.prototype.slice.call(arguments).join(' '); } },
                        'events': { EventEmitter: function() {} },
                        'stream': { Readable: function() {} },
                        'http': {}, 'https': {},
                        'url': { parse: function(u) { try { return new URL(u); } catch(e) { return {}; } } },
                        'assert': { ok: function(v) { if (!v) throw new Error('AssertionError'); }, strictEqual: function(a,b) { if (a!==b) throw new Error('AssertionError'); } }
                    };
                    return modules[modName] || {};
                }

                """, null
            )

            // 执行用户代码 (支持 async/await)
            val result = suspendCancellableCoroutine<String> { cont ->
                val wrappedCode = """
                    (async function() {
                        try {
                            var __result = (function() { $code })();
                            if (__result && typeof __result.then === 'function') {
                                __result = await __result;
                            }
                            if (typeof __result === 'undefined') return '__UNDEFINED__';
                            if (typeof __result === 'object') return JSON.stringify(__result);
                            return String(__result);
                        } catch(e) {
                            return JSON.stringify({error: e.message || String(e), stack: e.stack || ''});
                        }
                    })()
                """.trimIndent()
                webView.evaluateJavascript(wrappedCode) { raw ->
                    val value = raw?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                    if (cont.isActive) cont.resume(value ?: "")
                }
            }

            // 收集 console.log 输出
            val consoleLogs = suspendCancellableCoroutine<List<String>> { cont ->
                webView.evaluateJavascript("JSON.stringify(window.__getLogs())") { raw ->
                    val parsed = try {
                        val clean = raw?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\")
                            ?.replace("\\n", "\n")
                        if (clean != null) {
                            val arr = org.json.JSONArray(clean)
                            (0 until arr.length()).map { arr.getString(it) }
                        } else emptyList()
                    } catch (_: Exception) { emptyList() }
                    if (cont.isActive) cont.resume(parsed)
                }
            }

            // 合并 console 输出 + 结果
            val allLogs = (logs + consoleLogs).distinct()
            val finalResult = if (result == "__UNDEFINED__" && allLogs.isNotEmpty()) {
                // 没有显式 return 但有 console.log 输出, 用日志作为结果
                allLogs.joinToString("\n")
            } else {
                result
            }
            val payload = buildJsonObject {
                if (allLogs.isNotEmpty()) {
                    put("logs", JsonPrimitive(allLogs.joinToString("\n")))
                }
                put(
                    key = "result",
                    element = when (finalResult) {
                        "__UNDEFINED__", "null" -> JsonNull
                        else -> JsonPrimitive(finalResult)
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } finally {
            webView.destroy()
        }
    }
}
