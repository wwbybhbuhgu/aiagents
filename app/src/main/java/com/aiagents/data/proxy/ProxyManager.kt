package com.aiagents.data.proxy

import com.aiagents.data.datastore.ProxyConfig
import com.follow.clash.core.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 代理类型枚举 */
enum class ProxyType {
    TOOL,       // 工具调用（搜索、网页抓取等）
    LLM_API,    // LLM API 调用
    WORKSPACE,  // 工作区 shell
    SEARCH,     // 搜索服务
}

/** 代理分组信息 */
data class ProxyGroup(
    val name: String,
    val type: String,  // select, url-test, fallback 等
    val now: String,   // 当前选中的节点
    val all: List<String>, // 所有可用节点
)

/** 节点延迟信息 */
data class ProxyDelay(
    val name: String,
    val delay: Int,  // 毫秒，-1 表示超时
)

/**
 * mihomo 内核控制器（应用内本地代理模式）。
 *
 * 复刻 FlClash 的 Go 桥 / Core.kt JNI 协议：
 * - 通过 [Core.invokeMethod] 下发 JSON 方法调用（initClash/setupConfig/startListener/
 *   getProxies/changeProxy/getTraffic/shutdown 等）
 * - 内核在 [config.port] 端口起 mixed-port（HTTP + SOCKS5）本地监听，不接管整机网络
 * - 默认直连：未开启时不初始化内核、不监听任何端口
 */
class ProxyManager(
    private val filesDir: File,
) {
    private val started = AtomicBoolean(false)
    private var homeDir: File? = null
    private val callId = java.util.concurrent.atomic.AtomicLong(0)
    private var currentConfig: ProxyConfig? = null

    val isRunning: Boolean get() = started.get()

    /** 当前生效的本地代理地址（未运行时为 null） */
    val localProxyAddress: String?
        get() = if (started.get()) "127.0.0.1:${homeDir?.let { readPort(it) } ?: 7890}" else null

    /** 生成代理环境变量（未开启代理时返回空 Map = 直连） */
    fun proxyEnv(): Map<String, String> {
        val addr = localProxyAddress ?: return emptyMap()
        val proxy = "http://$addr"
        return mapOf(
            "HTTP_PROXY" to proxy,
            "HTTPS_PROXY" to proxy,
            "ALL_PROXY" to proxy,
        )
    }

    /** 根据配置判断是否需要代理（用于各模块调用） */
    fun needProxy(type: ProxyType): Boolean {
        if (!started.get()) return false
        val config = currentConfig ?: return false
        return when (config.proxyMode) {
            0 -> true // 全部走代理
            1 -> type == ProxyType.TOOL || type == ProxyType.SEARCH // 仅工具和搜索
            2 -> type == ProxyType.LLM_API // 仅 LLM API
            3 -> type == ProxyType.WORKSPACE // 仅工作区
            4 -> when (type) {
                ProxyType.TOOL -> config.proxyTools
                ProxyType.LLM_API -> config.proxyLlmApi
                ProxyType.WORKSPACE -> config.proxyWorkspace
                ProxyType.SEARCH -> config.proxySearch
            }
            else -> false
        }
    }

    /** 获取代理地址（供需要显式配置代理的模块使用） */
    fun getProxyAddress(): String? {
        if (!started.get()) return null
        return localProxyAddress
    }

    /** 创建带代理的 OkHttpClient */
    fun createOkHttpClient(): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)

        // SSL 宽容
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }

        // 配置代理
        val proxyAddress = localProxyAddress
        if (proxyAddress != null) {
            val parts = proxyAddress.split(":")
            if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 7890
                builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
            }
        }

        return builder.build()
    }

    /**
     * 开启代理：写 config.yaml 并启动内核监听。
     */
    suspend fun start(config: ProxyConfig): Boolean {
        if (started.get()) return true
        return try {
            val dir = withContext(Dispatchers.IO) { File(filesDir, "mihomo").apply { mkdirs() } }
            homeDir = dir
            currentConfig = config
            withContext(Dispatchers.IO) { writeConfig(dir, config) }

            android.util.Log.i(TAG, "Starting proxy, config dir: ${dir.absolutePath}")

            // 初始化内核
            val initResult = invokeSuspending("""{"method":"initClash","arguments":{"home-dir":"${dir.absolutePath}","version":1}}""")
            android.util.Log.i(TAG, "initClash result: $initResult")

            // 设置配置
            val setupResult = invokeSuspending("""{"method":"setupConfig","arguments":{"test-url":"https://www.gstatic.com/generate_204","selected-map":{}}}""")
            android.util.Log.i(TAG, "setupConfig result: $setupResult")

            // 启动监听
            val listenerResult = invokeSuspending("""{"method":"startListener","arguments":{}}""")
            android.util.Log.i(TAG, "startListener result: $listenerResult")

            started.set(true)

            // 恢复上次选择的代理节点
            restoreSelectedProxies(config)

            true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "start failed", e)
            false
        }
    }

    /** 恢复上次选择的代理节点 */
    private suspend fun restoreSelectedProxies(config: ProxyConfig) {
        if (config.selectedProxies.isEmpty()) return
        config.selectedProxies.forEach { (groupName, proxyName) ->
            android.util.Log.i(TAG, "Restoring proxy: $groupName -> $proxyName")
            changeProxy(groupName, proxyName)
        }
    }

    /** 保存当前选择的代理节点 */
    fun saveSelectedProxy(groupName: String, proxyName: String, config: ProxyConfig): ProxyConfig {
        val newSelected = config.selectedProxies.toMutableMap()
        newSelected[groupName] = proxyName
        return config.copy(selectedProxies = newSelected)
    }

    /** 停止内核并关闭本地监听 */
    suspend fun stop(): Boolean {
        if (!started.get()) return true
        return try {
            android.util.Log.i(TAG, "Stopping proxy")
            withContext(Dispatchers.IO) {
                runCatching {
                    invokeSuspending("""{"method":"stopListener","arguments":{}}""")
                    invokeSuspending("""{"method":"shutdown","arguments":{}}""")
                }
            }
            started.set(false)
            android.util.Log.i(TAG, "Proxy stopped")
            true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "stop failed", e)
            false
        }
    }

    /** 获取节点列表（返回解析后的分组信息） */
    suspend fun getProxyGroups(): List<ProxyGroup> = withContext(Dispatchers.IO) {
        if (!started.get()) return@withContext emptyList()
        val json = invokeSuspending("""{"method":"getProxies","arguments":{}}""") ?: return@withContext emptyList()
        parseProxyGroups(json)
    }

    /** 切换节点 */
    suspend fun changeProxy(groupName: String, proxyName: String): String? =
        withContext(Dispatchers.IO) {
            if (!started.get()) return@withContext null
            val args = "{\"group-name\":\"${groupName.escapeJson()}\",\"proxy-name\":\"${proxyName.escapeJson()}\"}"
            invokeSuspending("""{"method":"changeProxy","arguments":$args}""")
        }

    /** 测试单个节点延迟 */
    private suspend fun testDelay(proxyName: String, testUrl: String = "https://www.gstatic.com/generate_204"): Int {
        val args = """{"proxy-name":"${proxyName.escapeJson()}","timeout":5000,"test-url":"$testUrl"}"""
        val json = invokeSuspending("""{"method":"asyncTestDelay","arguments":$args}""")
        return parseDelay(json)
    }

    /** 批量测试节点延迟（并行） */
    suspend fun testAllDelays(proxyNames: List<String>, testUrl: String = "https://www.gstatic.com/generate_204"): Map<String, Int> =
        withContext(Dispatchers.IO) {
            if (!started.get()) return@withContext emptyMap()
            // 并行测试所有节点
            val deferreds = proxyNames.map { name ->
                async {
                    val args = """{"proxy-name":"${name.escapeJson()}","timeout":5000,"test-url":"$testUrl"}"""
                    val json = invokeSuspending("""{"method":"asyncTestDelay","arguments":$args}""")
                    name to parseDelay(json)
                }
            }
            deferreds.awaitAll().toMap()
        }

    /** 解析延迟结果 */
    private fun parseDelay(json: String?): Int {
        if (json == null) return -1
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            val result = root["result"]?.jsonObject ?: root
            result["value"]?.toString()?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /** 当前流量（格式化显示） */
    suspend fun getTrafficText(): String = withContext(Dispatchers.IO) {
        if (!started.get()) return@withContext "未运行"
        val json = Core.getTraffic(true) ?: return@withContext "无数据"
        formatTraffic(json)
    }

    /** 总流量（格式化显示） */
    suspend fun getTotalTrafficText(): String = withContext(Dispatchers.IO) {
        if (!started.get()) return@withContext "未运行"
        val json = Core.getTotalTraffic(true) ?: return@withContext "无数据"
        formatTraffic(json)
    }

    /** 解析流量 JSON 并格式化 */
    private fun formatTraffic(json: String): String {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            // 返回格式可能是 {"result":{"up":...,"down":...}} 或直接 {"up":...,"down":...}
            val obj = root["result"]?.jsonObject ?: root
            val up = obj["up"]?.toString()?.toLongOrNull() ?: 0L
            val down = obj["down"]?.toString()?.toLongOrNull() ?: 0L
            "↑ ${formatBytes(up)}  ↓ ${formatBytes(down)}"
        } catch (e: Exception) {
            android.util.Log.e(TAG, "formatTraffic error: $json", e)
            json.take(100) // 截断避免过长
        }
    }

    /** 格式化字节数 */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)}MB"
            else -> "${"%.2f".format(bytes / 1024.0 / 1024.0 / 1024.0)}GB"
        }
    }

    /** 解析代理分组 JSON */
    private fun parseProxyGroups(json: String): List<ProxyGroup> {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            // 返回格式是 {"result":{"proxies":{...}}}，需要提取 proxies
            val result = root["result"]?.jsonObject ?: root
            val proxies = result["proxies"]?.jsonObject ?: result
            proxies.mapNotNull { (name, value) ->
                val group = value.jsonObject
                val type = group["type"]?.toString()?.trim('"') ?: ""
                val now = group["now"]?.toString()?.trim('"') ?: ""
                val all = group["all"]?.jsonArray?.map { it.toString().trim('"') } ?: emptyList()
                // 只返回可切换的组
                if (type == "Selector" || type == "select" || type == "URLTest" || type == "url-test" || type == "Fallback" || type == "fallback") {
                    ProxyGroup(name = name, type = type, now = now, all = all)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "parseProxyGroups error", e)
            emptyList()
        }
    }

    private fun readPort(dir: File): Int {
        val cfg = File(dir, "config.yaml")
        if (!cfg.exists()) return 7890
        val m = Regex("""mixed-port:\s*(\d+)""").find(cfg.readText())
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 7890
    }

    /**
     * 写 mihomo config.yaml。
     * - 如果 configContent 不为空，直接使用用户导入的配置文件内容
     * - 否则根据 subscription 和其他选项生成模板配置
     */
    private fun writeConfig(dir: File, config: ProxyConfig) {
        // 优先使用用户导入的配置文件内容
        if (config.configContent.isNotBlank()) {
            dir.mkdirs()
            File(dir, "config.yaml").writeText(config.configContent)
            return
        }

        // 否则生成模板配置
        val yaml = buildString {
            appendLine("mixed-port: ${config.port}")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: info")
            appendLine("ipv6: false")
            appendLine("")
            if (config.subscription.isNotBlank()) {
                appendLine("proxy-providers:")
                appendLine("  aiagents:")
                appendLine("    type: http")
                appendLine("    url: \"${config.subscription}\"")
                appendLine("    interval: 3600")
                appendLine("    path: providers/aiagents.yaml")
                appendLine("    health-check:")
                appendLine("      enable: true")
                appendLine("      url: https://www.gstatic.com/generate_204")
                appendLine("      interval: 300")
                appendLine("")
                appendLine("proxy-groups:")
                appendLine("  - name: PROXY")
                appendLine("    type: select")
                appendLine("    proxies:")
                appendLine("      - AUTO")
                appendLine("      - DIRECT")
                appendLine("      - \"use aiagents\"")
                appendLine("  - name: AUTO")
                appendLine("    type: url-test")
                appendLine("    url: https://www.gstatic.com/generate_204")
                appendLine("    interval: 300")
                appendLine("    proxies:")
                appendLine("      - \"use aiagents\"")
            } else {
                appendLine("proxy-groups:")
                appendLine("  - name: PROXY")
                appendLine("    type: select")
                appendLine("    proxies:")
                appendLine("      - DIRECT")
            }
            appendLine("")
            appendLine("rules:")
            appendLine("  - MATCH,PROXY")
        }
        dir.mkdirs()
        File(dir, "config.yaml").writeText(yaml)
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    /** 调用内核方法并等待结果（suspend 版本，不阻塞主线程） */
    private suspend fun invokeSuspending(json: String): String? {
        return try {
            android.util.Log.d(TAG, "invoke: $json")
            val deferred = CompletableDeferred<String?>()
            // 在 IO 线程调用 JNI，回调会在 Go 线程执行
            withContext(Dispatchers.IO) {
                Core.invokeMethod(json) { result ->
                    android.util.Log.d(TAG, "invoke callback: $result")
                    // 回调在 Go 线程执行，直接 complete（线程安全）
                    deferred.complete(result)
                }
            }
            // 带超时等待结果
            val result = withTimeout(15_000L) {
                deferred.await()
            }
            android.util.Log.d(TAG, "invoke result: $result")
            result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "invoke error: ${e.message}", e)
            null
        }
    }

    companion object {
        const val TAG = "ProxyManager"
    }
}